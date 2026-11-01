package app.btmic.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * SKILL.md recipe 5, pitfall 6: a silent LE input can stay silent unless
 * something plays to the headset. Zero-filled MODE_STATIC AudioTrack.
 */
class Primer {

    private var track: AudioTrack? = null

    fun start(seconds: Int = 3) {
        val rate = 16000
        val bytes = rate * 2 * seconds
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(ByteArray(bytes), 0, bytes) // zeros
        t.play()
        track = t
    }

    fun release() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
