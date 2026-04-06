package com.github.jing332.tts_server_android.compose.systts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drake.net.utils.withMain
import com.github.jing332.common.audio.AudioPlayer
import com.github.jing332.common.utils.messageChain
import com.github.jing332.common.utils.sizeToReadable
import com.github.jing332.compose.widgets.AppDialog
import com.github.jing332.compose.widgets.LoadingContent
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.tts.CachedEngineManager
import com.github.jing332.tts.speech.EngineState
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.PluginTtsProvider
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.jing332.tts.synthesizer.TtsConfiguration.Companion.toVO
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.JsConsoleManager
import com.github.jing332.tts_server_android.conf.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.IOException
import splitties.init.appCtx


private val logger = KotlinLogging.logger("AuditionDialog")

@Composable
fun AuditionDialog(
    systts: SystemTtsV2,
    text: String = AppConfig.testSampleText.value,

    config: TtsConfiguration = (systts.config as TtsConfigurationDTO).toVO(),
    engine: TextToSpeechProvider<TextToSpeechSource>? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val audioPlayer = remember { AudioPlayer(context) }

    DisposableEffect(systts) {
        onDispose {
            audioPlayer.stop()
        }
    }

    LaunchedEffect(systts) {
        launch(Dispatchers.IO) {
            try {
                val e = engine ?: CachedEngineManager.getEngine(appCtx, config.source)
                ?: throw IllegalStateException("engine is null")

                JsConsoleManager.ui.info("[Audition] engine=${e.javaClass.simpleName}, state=${e.state}")
                if (config.source is PluginTtsSource) {
                    val source = config.source as PluginTtsSource
                    JsConsoleManager.ui.info(
                        "[Audition] pluginId=${source.pluginId}, locale=${source.locale}, voice=${source.voice}, data=${source.data}"
                    )
                }

                if (e.state is EngineState.Uninitialized) e.onInit()
                if (e is PluginTtsProvider) {
                    e.engine?.console = JsConsoleManager.ui
                    JsConsoleManager.ui.info("[Audition] plugin engine initialized=${e.engine != null}")
                }

                if (e.isSyncPlay(config.source)) {
                    JsConsoleManager.ui.info("[Audition] syncPlay start")
                    e.syncPlay(SystemParams(text = text), config.source)
                } else {
                    JsConsoleManager.ui.info("[Audition] getStream start")
                    val stream = e.getStream(SystemParams(text = text), config.source)
                    val audio = stream.readBytes()
                    JsConsoleManager.ui.info("[Audition] audio bytes=${audio.size}")
                    if (audio.isEmpty()) {
                        error = context.getString(R.string.error) + ": empty audio"
                        JsConsoleManager.ui.error("[Audition] empty audio returned")
                        return@launch
                    }

                    val rateAndMime =
                        com.github.jing332.common.audio.AudioDecoder.getSampleRateAndMime(audio)
                    withMain {
                        info = context.getString(
                            R.string.systts_test_success_info, audio.size.toLong().sizeToReadable(),
                            rateAndMime.first, rateAndMime.second
                        )
                    }

                    if (config.shouldDecode())
                        audioPlayer.play(audio)
                    else
                        audioPlayer.play(audio, config.audioFormat.sampleRate)
                }
                withContext(Dispatchers.Main) {
                    onDismissRequest()
                }
            } catch (e: IOException) {
                error = e.cause.toString()
                JsConsoleManager.ui.error("[Audition] IOException: ${e.cause}")
            } catch (e: Exception) {
                error = e.messageChain
                logger.warn { e.stackTraceToString() }
                JsConsoleManager.ui.error("[Audition] Exception: ${e.messageChain}")
            }
        }
    }

    AppDialog(onDismissRequest = onDismissRequest,
        title = { Text(stringResource(id = R.string.audition)) },
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        error.ifEmpty { text },
                        color = if (error.isEmpty()) Color.Unspecified else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (error.isEmpty())
                    LoadingContent(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                        isLoading = info.isEmpty()
                    ) {
                        SelectionContainer {
                            Text(info, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

            }
        },
        buttons = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(id = R.string.cancel)) }
        }
    )

}
