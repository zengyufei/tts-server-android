package com.github.jing332.common.audio.exo

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

@UnstableApi
/**
 * 用于接收从 ExoPlayer 解码后的 PCM 数据，而不是播放到 AudioTrack。
 */
class DecoderAudioSink(
    private val onPcmBuffer: (ByteBuffer) -> Unit,
    private val onEndOfStream: () -> Unit
) : AudioSink {
    private var timeUs: Long = 0L
    private var pcmEncoding: Int = C.ENCODING_PCM_16BIT
    private var channelCount: Int = 1

    companion object {
        const val TAG = "DecoderAudioSink"
        private val logger = KotlinLogging.logger(TAG)
    }

    override fun setListener(listener: AudioSink.Listener) {
    }

    override fun supportsFormat(format: Format): Boolean {
        return format.sampleMimeType == "audio/raw" // 只接收 PCM 格式
    }

    override fun getFormatSupport(format: Format): Int = SINK_FORMAT_SUPPORTED_WITH_TRANSCODING

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = timeUs

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        pcmEncoding = inputFormat.pcmEncoding
        channelCount = inputFormat.channelCount.coerceAtLeast(1)
        logger.info {
            "[DecoderAudioSink] configure sampleRate=${inputFormat.sampleRate}, " +
                "channelCount=${inputFormat.channelCount}, pcmEncoding=${inputFormat.pcmEncoding}, " +
                "sampleMimeType=${inputFormat.sampleMimeType}"
        }
    }

    override fun play() {
    }

    override fun handleDiscontinuity() {

    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        onPcmBuffer.invoke(convertToMono16Bit(buffer))
        timeUs += presentationTimeUs

        return true
    }

    private fun convertToMono16Bit(buffer: ByteBuffer): ByteBuffer {
        val src = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (pcmEncoding == C.ENCODING_PCM_16BIT && channelCount == 1) return src

        return when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> convert16BitToMono(src)
            C.ENCODING_PCM_FLOAT -> convertFloatToMono16Bit(src)
            else -> src
        }
    }

    private fun convert16BitToMono(src: ByteBuffer): ByteBuffer {
        val frameCount = src.remaining() / (channelCount * Short.SIZE_BYTES)
        val out = ByteBuffer.allocate(frameCount * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        repeat(frameCount) {
            var sum = 0
            repeat(channelCount) {
                sum += src.short.toInt()
            }
            out.putShort((sum / channelCount).toShort())
        }

        out.flip()
        return out
    }

    private fun convertFloatToMono16Bit(src: ByteBuffer): ByteBuffer {
        val frameCount = src.remaining() / (channelCount * Float.SIZE_BYTES)
        val out = ByteBuffer.allocate(frameCount * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        repeat(frameCount) {
            var sum = 0f
            repeat(channelCount) {
                sum += src.float
            }

            val mono = (sum / channelCount).coerceIn(-1f, 1f)
            out.putShort((mono * Short.MAX_VALUE).roundToInt().toShort())
        }

        out.flip()
        return out
    }

    override fun playToEndOfStream() = onEndOfStream()

    override fun isEnded(): Boolean = true

    override fun hasPendingData(): Boolean = true

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {

    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(1f)


    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {

    }

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {

    }

    override fun getAudioAttributes(): AudioAttributes? = null

    override fun setAudioSessionId(audioSessionId: Int) {

    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {

    }

    override fun enableTunnelingV21() {

    }

    override fun disableTunneling() {

    }

    override fun setVolume(volume: Float) {

    }

    override fun pause() {

    }

    override fun flush() {

    }


    override fun reset() {


    }
}
