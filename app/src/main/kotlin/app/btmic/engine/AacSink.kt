package app.btmic.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * PCM16/16k/mono -> AAC-LC .m4a via MediaCodec + MediaMuxer.
 * Output stays in app-internal storage (audit F6).
 */
class AacSink(private val out: File) : RecordEngine.PcmSink {

    private val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var pts: Long = 0
    private var started = false

    override fun open() {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 32000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        track = muxer.addTrack(codec.outputFormat)
        muxer.start()
        started = true
    }

    override fun write(pcm: ShortArray) {
        if (!started || pcm.isEmpty()) return
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val ib = codec.getInputBuffer(idx)!!
            ib.clear()
            for (s in pcm) ib.putShort(s)
            codec.queueInputBuffer(idx, 0, pcm.size * 2, pts, 0)
            pts += pcm.size * 1_000_000L / 16000
        }
        drain(end = false)
    }

    override fun close() {
        if (started) {
            codec.queueInputBuffer(
                codec.dequeueInputBuffer(10_000), 0, 0, pts,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(end = true)
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
    }

    private fun drain(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (end) 100_000 else 0)
            if (o >= 0) {
                val ob = codec.getOutputBuffer(o)!!
                muxer.writeSampleData(track, ob, info)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && !end) break
        }
    }
}
