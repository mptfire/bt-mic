package app.btmic.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * SKILL.md recipe 6: capture loop with a first-sound watchdog.
 * Before the first non-zero sample the ONLY limit is the generous start
 * timeout; stall/zero limits apply only after sound arrived (pitfall 5/11).
 */
class Recorder(private val rec: AudioRecord) {

    @Volatile var running = true
        private set
    val pcm = ByteArrayOutputStream()
    var onFailure: ((String) -> Unit)? = null
    var onFirstSound: (() -> Unit)? = null

    companion object {
        const val RATE = 16000

        @SuppressLint("MissingPermission")
        fun open(preferred: AudioDeviceInfo?): AudioRecord {
            val min = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 4, RATE * 2)) // >= 1 s
                .build()
                .apply { if (preferred != null) setPreferredDevice(preferred) }
        }
    }

    fun start(startTimeoutMs: Long = 8000) = thread(name = "bt-rec") {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(1600) // 100 ms
        val t0 = SystemClock.elapsedRealtime()
        var soundSeen = false
        var emptyReads = 0
        rec.startRecording()
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n < 0) { fail("read error $n"); break }
            if (n == 0) {
                if (soundSeen && ++emptyReads > 10) { fail("stalled"); break }
                if (!soundSeen && SystemClock.elapsedRealtime() - t0 > startTimeoutMs) {
                    fail("no sound in ${startTimeoutMs} ms"); break
                }
                Thread.sleep(10); continue
            }
            emptyReads = 0
            if (!soundSeen) {
                if (buf.take(n).any { it.toInt() != 0 }) {
                    soundSeen = true; onFirstSound?.invoke()
                } else if (SystemClock.elapsedRealtime() - t0 > startTimeoutMs) {
                    fail("only zeros for ${startTimeoutMs} ms"); break
                }
                continue // zero frames carry no words; timing continuity is enough
            }
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort(buf[i])
            synchronized(pcm) { pcm.write(bb.array()) }
        }
    }

    fun drain(): ShortArray = synchronized(pcm) {
        val bytes = pcm.toByteArray()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        out
    }

    fun stopAndRelease() {
        running = false
        runCatching { rec.stop() }
        rec.release()
    }

    private fun fail(msg: String) {
        running = false
        onFailure?.invoke(msg)
    }
}
