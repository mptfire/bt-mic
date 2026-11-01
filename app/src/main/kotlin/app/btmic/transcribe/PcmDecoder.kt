package app.btmic.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** m4a (AAC) -> PCM16 16 kHz mono shorts, streamed chunk by chunk. */
object PcmDecoder {

    fun decodeTo16kMono(src: File, onChunk: (ShortArray) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src.path)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; fmt = f; break
            }
        }
        if (track < 0 || fmt == null) { ex.release(); return }
        ex.selectTrack(track)
        val inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (!eos) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val ib = codec.getInputBuffer(i)!!
                val n = ex.readSampleData(ib, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eos = true
                } else {
                    codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                    ex.advance()
                }
            }
            var o = codec.dequeueOutputBuffer(info, 10_000)
            while (o >= 0) {
                val ob = codec.getOutputBuffer(o)!!
                val sb = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val raw = ShortArray(sb.remaining()).also { sb.get(it) }
                onChunk(mixToMono(raw, inCh))
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
                o = codec.dequeueOutputBuffer(info, 0)
            }
        }
        codec.stop(); codec.release(); ex.release()
    }

    /** downmix + naive linear resample (our notes are already 16k mono) */
    fun mixToMono(raw: ShortArray, channels: Int, fromRate: Int = 16000, toRate: Int = 16000): ShortArray {
        val mono = if (channels <= 1) raw else ShortArray(raw.size / channels) { i ->
            var acc = 0
            for (c in 0 until channels) acc += raw[i * channels + c].toInt()
            (acc / channels).toShort()
        }
        if (fromRate == toRate) return mono
        val out = ShortArray((mono.size.toLong() * toRate / fromRate).toInt())
        for (j in out.indices) {
            val p = j.toLong() * fromRate / toRate
            out[j] = mono.getOrElse(p.toInt()) { 0 }
        }
        return out
    }
}
