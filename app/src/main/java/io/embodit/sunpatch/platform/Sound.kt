package io.embodit.sunpatch.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The app's two sounds (design `container.notes`, D15): a short wooden "嗒" when a corner / marker goes down and one
 * soft "叮" when the day sweep ends. Both are synthesised once at start (-12 dBFS) into static AudioTracks; no ambient
 * loop. [enabled] is the mute switch (design setting `sound`, default on).
 */
class Sound {
    private var click: AudioTrack? = null
    private var ding: AudioTrack? = null

    @Volatile var enabled = true

    fun start() {
        if (click != null) return
        click = runCatching { track(synth(0.06f) { t -> 0.9 * sin(2 * PI * 1250 * t) * exp(-t * 90) + 0.4 * sin(2 * PI * 2600 * t) * exp(-t * 160) }) }.getOrNull()
        ding = runCatching { track(synth(1.1f) { t -> sin(2 * PI * 1568 * t) * exp(-t * 4.5) * 0.8 + sin(2 * PI * 3136 * t) * exp(-t * 7.0) * 0.25 }) }.getOrNull()
        SpLog.i("SOUND ready click=${click != null} ding=${ding != null}")
    }

    fun release() {
        runCatching { click?.release() }
        runCatching { ding?.release() }
        click = null
        ding = null
    }

    fun click() = play(click, "click")

    fun ding() = play(ding, "ding")

    private fun play(t: AudioTrack?, name: String) {
        if (!enabled || t == null) return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }.onFailure { SpLog.w("SOUND $name failed: $it") }
        SpLog.i("SOUND $name")
    }

    private fun synth(seconds: Float, f: (Double) -> Double): ShortArray {
        val n = (RATE * seconds).toInt()
        val gain = 0.25 // -12 dBFS
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val fade = if (i > n - 200) (n - i) / 200.0 else 1.0
            (f(t) * gain * fade * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private companion object {
        const val RATE = 44100
    }
}
