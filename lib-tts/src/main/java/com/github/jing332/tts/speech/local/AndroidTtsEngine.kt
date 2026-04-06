package com.github.jing332.tts.speech.local

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.source.LocalTtsParameter
import com.github.jing332.database.entities.systts.source.LocalTtsSource
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Locale
import kotlin.coroutines.resume

class AndroidTtsEngine(
    val context: Context,
    val cacheDir: String = context.externalCacheDir!!.absolutePath + "${File.separator}AndroidTTS",
    private val callbackTimeoutMillis: () -> Long = { 60_000L },
) {
    private var mTts: TextToSpeech? = null
    private var mEngineName: String = ""

    suspend fun init(engineName: String): Boolean = coroutineScope {
        if (mEngineName != engineName) {
            mEngineName = engineName
            release()
        }

        suspendCancellableCoroutine { continuation ->
            mTts = TextToSpeech(context, { status ->
                continuation.resume(status == TextToSpeech.SUCCESS)
            }, engineName)

            continuation.invokeOnCancellation {
                release()
            }
        }
    }

    val voices: List<Voice>
        get() = mTts?.voices?.toList() ?: emptyList()

    val locales: List<Locale>
        get() = mTts?.availableLanguages?.toList()?.sortedBy { it.toString() } ?: emptyList()

    val voice: Voice?
        get() = mTts?.voice

    fun setVoice(voice: Voice): Boolean {
        return mTts?.setVoice(voice) == TextToSpeech.SUCCESS
    }

    private fun setEnginePlayParams(
        engine: TextToSpeech,
        locale: String,
        voice: String,
        extraParams: List<LocalTtsParameter>?,
        params: AudioParams,
    ): Bundle {
        engine.apply {
            if (locale.isNotEmpty()) {
                language = Locale.forLanguageTag(locale)
            }

            if (voice.isNotEmpty()) {
                voices?.forEach {
                    if (it.name == voice) this.voice = it
                }
            }

            setSpeechRate(params.speed)
            setPitch(params.pitch)
            return Bundle().apply {
                if (params.volume != LocalTtsSource.VOLUME_FOLLOW) {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, params.volume)
                }
                extraParams?.forEach { it.putValueFromBundle(this) }
            }
        }
    }

    private val mutex = Mutex()

    private suspend fun <T> awaitEngineResult(
        tts: TextToSpeech,
        onCancel: () -> Unit = {},
        block: (resume: (Result<T, TtsEngineError>) -> Unit) -> Unit,
    ): Result<T, TtsEngineError> {
        return try {
            withTimeout(callbackTimeoutMillis().coerceAtLeast(1L)) {
                suspendCancellableCoroutine { continuation ->
                    val resume: (Result<T, TtsEngineError>) -> Unit = { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }

                    block(resume)

                    continuation.invokeOnCancellation {
                        onCancel()
                        runCatching { tts.stop() }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            onCancel()
            runCatching { tts.stop() }
            release()
            Err(TtsEngineError.Timeout)
        }
    }

    suspend fun getFile(
        text: String,
        locale: String = "",
        voice: String = "",
        extraParams: List<LocalTtsParameter> = emptyList(),
        params: AudioParams = AudioParams(),
    ): Result<File, TtsEngineError> = mutex.withLock {
        val tts = mTts ?: return@withLock Err(TtsEngineError.Initialization)
        coroutineScope {
            val filename = System.currentTimeMillis().toString() + ".wav"
            val file = File(cacheDir, filename)
            if (file.parentFile?.exists() != true && file.parentFile?.mkdirs() != true) {
                return@coroutineScope Err(TtsEngineError.File)
            }

            fun delete() {
                runCatching { file.delete() }
            }

            val bundle = setEnginePlayParams(tts, locale, voice, extraParams, params)
            awaitEngineResult<File>(tts, onCancel = ::delete) { resume ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == filename) {
                            resume(Ok(file))
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        if (utteranceId == filename) {
                            delete()
                            resume(Err(TtsEngineError.Engine))
                        }
                    }
                })

                val ret = tts.synthesizeToFile(text, bundle, file, filename)
                if (ret != TextToSpeech.SUCCESS) {
                    delete()
                    resume(Err(TtsEngineError.Engine))
                }
            }
        }
    }

    suspend fun getStream(
        text: String,
        locale: String = "",
        voice: String = "",
        extraParams: List<LocalTtsParameter> = emptyList(),
        params: AudioParams = AudioParams(),
    ): Result<InputStream, TtsEngineError> = mutex.withLock {
        val tts = mTts ?: return@withLock Err(TtsEngineError.Initialization)

        coroutineScope {
            val filename = System.currentTimeMillis().toString() + ".wav"
            val file = File(cacheDir, filename)
            if (file.parentFile?.exists() != true && file.parentFile?.mkdirs() != true) {
                return@coroutineScope Err(TtsEngineError.File)
            }

            fun delete() {
                runCatching { file.delete() }
            }

            val bundle = setEnginePlayParams(tts, locale, voice, extraParams, params)
            awaitEngineResult<InputStream>(tts, onCancel = ::delete) { resume ->
                val pos = PipedOutputStream()
                val pis = PipedInputStream(pos)

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        if (utteranceId == filename) {
                            resume(Ok(pis))
                        }
                    }

                    override fun onDone(utteranceId: String) {
                        if (utteranceId != filename) return
                        runCatching { pos.close() }
                        delete()
                    }

                    override fun onError(utteranceId: String) {
                        if (utteranceId != filename) return
                        runCatching { pos.close() }
                        delete()
                        resume(Err(TtsEngineError.Engine))
                    }

                    override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                        super.onAudioAvailable(utteranceId, audio)
                        if (utteranceId != filename) return
                        pos.write(audio)
                    }
                })

                val ret = tts.synthesizeToFile(text, bundle, file, filename)
                if (ret != TextToSpeech.SUCCESS) {
                    runCatching { pos.close() }
                    delete()
                    resume(Err(TtsEngineError.Engine))
                }
            }
        }
    }

    suspend fun getAudio(
        text: String,
        locale: String = "",
        voice: String = "",
        extraParams: List<LocalTtsParameter> = emptyList(),
        params: AudioParams = AudioParams(),
        listener: Listener,
    ): Result<Unit, TtsEngineError> = mutex.withLock {
        val tts = mTts ?: return@withLock Err(TtsEngineError.Initialization)

        coroutineScope {
            val filename = System.currentTimeMillis().toString() + ".wav"
            val file = File(cacheDir, filename)
            if (file.parentFile?.exists() != true && file.parentFile?.mkdirs() != true) {
                return@coroutineScope Err(TtsEngineError.File)
            }

            fun delete() {
                runCatching { file.delete() }
            }

            val bundle = setEnginePlayParams(tts, locale, voice, extraParams, params)
            awaitEngineResult<Unit>(tts, onCancel = ::delete) { resume ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        if (utteranceId == filename) {
                            listener.start()
                        }
                    }

                    override fun onDone(utteranceId: String) {
                        if (utteranceId != filename) return
                        listener.done()
                        delete()
                        resume(Ok(Unit))
                    }

                    override fun onError(utteranceId: String) {
                        if (utteranceId != filename) return
                        delete()
                        resume(Err(TtsEngineError.Engine))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        super.onError(utteranceId, errorCode)
                    }

                    override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                        super.onAudioAvailable(utteranceId, audio)
                        if (utteranceId == filename) {
                            listener.available(audio)
                        }
                    }
                })

                val ret = tts.synthesizeToFile(text, bundle, file, filename)
                if (ret != TextToSpeech.SUCCESS) {
                    delete()
                    resume(Err(TtsEngineError.Engine))
                }
            }
        }
    }

    suspend fun play(
        text: String,
        locale: String = "",
        voice: String = "",
        extraParams: List<LocalTtsParameter> = emptyList(),
        params: AudioParams = AudioParams(),
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
    ): Result<Unit, TtsEngineError> = mutex.withLock {
        val tts = mTts ?: return@withLock Err(TtsEngineError.Initialization)
        val bundle = setEnginePlayParams(tts, locale, voice, extraParams, params)
        val expectedUtteranceId = System.currentTimeMillis().toString()

        awaitEngineResult<Unit>(tts) { resume ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == expectedUtteranceId) {
                        resume(Ok(Unit))
                    }
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == expectedUtteranceId) {
                        resume(Err(TtsEngineError.Engine))
                    }
                }
            })

            val ret = tts.speak(text, queueMode, bundle, expectedUtteranceId)
            if (ret != TextToSpeech.SUCCESS) {
                resume(Err(TtsEngineError.Engine))
            }
        }
    }

    fun release() {
        mTts?.shutdown()
        mTts = null
    }

    interface Listener {
        fun start()
        fun available(audio: ByteArray)
        fun done()
    }
}
