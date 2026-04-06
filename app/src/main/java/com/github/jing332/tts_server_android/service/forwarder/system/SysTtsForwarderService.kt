@file:Suppress("OVERRIDE_DEPRECATION")

package com.github.jing332.tts_server_android.service.forwarder.system

import android.speech.tts.TextToSpeech
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.source.LocalTtsParameter
import com.github.jing332.server.forwarder.Engine
import com.github.jing332.server.forwarder.SystemTtsForwardServer
import com.github.jing332.server.forwarder.TtsParams
import com.github.jing332.server.forwarder.Voice
import com.github.jing332.tts.speech.local.AndroidTtsEngine
import com.github.jing332.tts.speech.local.LocalTtsProvider
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.conf.SystemTtsForwarderConfig
import com.github.jing332.tts_server_android.help.LocalTtsEngineHelper
import com.github.jing332.tts_server_android.service.forwarder.AbsForwarderService
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import com.github.michaelbull.result.onFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

class SysTtsForwarderService(
    override val port: Int = SystemTtsForwarderConfig.port.value,
    override val isWakeLockEnabled: Boolean = SystemTtsForwarderConfig.isWakeLockEnabled.value,
) : AbsForwarderService(
    "SysTtsForwarderService",
    id = 1221,
    actionLog = ACTION_ON_LOG,
    actionStarted = ACTION_ON_STARTED,
    actionClosed = ACTION_ON_CLOSED,
    notificationChanId = "systts_forwarder_status",
    notificationChanTitle = R.string.forwarder_systts,
    notificationIcon = R.drawable.ic_baseline_compare_arrows_24,
    notificationTitle = R.string.forwarder_systts,
) {
    companion object {
        const val TAG = "SysTtsServerService"
        const val ACTION_ON_CLOSED = "ACTION_ON_CLOSED"
        const val ACTION_ON_STARTED = "ACTION_ON_STARTED"
        const val ACTION_ON_LOG = "ACTION_ON_LOG"

        private val logger = KotlinLogging.logger(TAG)


        val isRunning: Boolean
            get() = instance?.isRunning == true

        var instance: SysTtsForwarderService? = null
    }

    private var mServer: SystemTtsForwardServer? = null
    private var mLocalTTS: LocalTtsProvider? = null
    private val mLocalTtsHelper by lazy { LocalTtsEngineHelper(this) }
    private val androidTts by lazy {
        AndroidTtsEngine(this) {
            SystemTtsForwarderConfig.callbackTimeoutMs.value
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun initServer() {
    }

    override fun startServer() {
        mServer = SystemTtsForwardServer(port, object : SystemTtsForwardServer.Callback {
            override fun log(level: Int, message: String) {
                sendLog(level, message)
            }

            override suspend fun tts(params: TtsParams): File? {
                val speed = (params.speed + 100) / 100f
                val pitch = params.pitch / 100f

                logger.debug { "android tts init: $params" }
                if (!androidTts.init(params.engine)) {
                    logger.warn { "android tts init failed: ${params.engine}" }
                    return null
                }

                logger.debug { "android tts get file..." }
                val file = try {
                    withTimeout(SystemTtsForwarderConfig.callbackTimeoutMs.value) {
                        androidTts.getFile(
                            params.text,
                            params.locale,
                            voice = params.voice,
                            extraParams = listOf(
                                LocalTtsParameter(
                                    type = LocalTtsParameter.TYPE_BOOL,
                                    key = SystemTtsService.PARAM_BGM_ENABLED,
                                    value = false.toString()
                                )
                            ),
                            params = AudioParams(speed = speed, pitch = pitch)
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.warn { "android tts callback timed out: $params" }
                    androidTts.release()
                    return null
                }

                return file.onFailure {
                    androidTts.release()
                    return null
                }.value
            }

            override suspend fun voices(engine: String): List<Voice> {
                val ok = mLocalTtsHelper.setEngine(engine)
                if (!ok) throw IllegalStateException(getString(R.string.systts_engine_init_failed_timeout))

                return mLocalTtsHelper.voices.map {
                    Voice(
                        name = it.name,
                        locale = it.locale.toLanguageTag(),
                        localeName = it.locale.getDisplayName(it.locale),
                        features = it.features?.toList()
                    )
                }
            }

            override suspend fun engines(): List<Engine> =
                getSysTtsEngines().map { Engine(name = it.name, it.label) }


        })
        mServer?.start(true,
            onStarted = {
                notifiStarted()
            }, onStopped = {
                notifiClosed()
            }
        )
    }

    override fun closeServer() {
        mServer?.let {
            it.stop()
            mLocalTTS?.onDestroy()
            mLocalTTS = null
        }
        androidTts.release()
        mServer = null
    }

    private fun getSysTtsEngines(): List<TextToSpeech.EngineInfo> {
        val tts = TextToSpeech(App.context, null)
        val engines = tts.engines
        tts.shutdown()
        return engines
    }

    override fun onDestroy() {
        androidTts.release()
        instance = null
        super.onDestroy()
    }

}
