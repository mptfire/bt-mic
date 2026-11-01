package app.btmic.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.btmic.R

/**
 * SKILL.md recipe 8: distinct tones for waiting -> go, and for the fallback.
 * A spoken cue is fine on a BT mic but is recorded on the phone mic, so this
 * app uses tones only. Raw PCM16/16k/mono in res/raw (no wav parser needed).
 */
class CuePlayer(private val context: Context) {

    enum class Cue { GO, FALLBACK }

    private var track: AudioTrack? = null

    fun play(cue: Cue) {
        stop()
        val res = context.resources
        val id = when (cue) {
            Cue.GO -> R.raw.cue_go           // 2 s leading silence (pitfall 9)
            Cue.FALLBACK -> R.raw.cue_fallback
        }
        val pcm = res.openRawResource(id).readBytes()
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(pcm, 0, pcm.size); it.play() }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
