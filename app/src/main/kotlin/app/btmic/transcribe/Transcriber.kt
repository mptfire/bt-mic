package app.btmic.transcribe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaCodec
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * SKILL.md recipe 10 with the audit hardenings (PR jooray/android-bluetooth-
 * microphone#1):
 *  - the capability probe is existence-only, so AUDIO_URI goes ONLY to the
 *    real OSTT package; every other recognizer gets EXTRA_AUDIO_SOURCE (a
 *    PCM pipe — no Uri grant to hold);
 *  - the Uri grant is revoked and the recognizer destroyed on EVERY exit
 *    path, including a caller-side timeout that no callback answers;
 *  - busy answers (ERROR_RECOGNIZER_BUSY/AUDIO/SERVER/SERVER_DISCONNECTED)
 *    retry with backoff ~10 min; ERROR_NO_MATCH does not retry.
 */
class Transcriber(private val context: Context) {

    companion object {
        const val OSTT = "app.offlinespeechtotext"
        const val ACTION_TRANSCRIBE_AUDIO = "$OSTT.action.TRANSCRIBE_AUDIO"
        const val EXTRA_AUDIO_URI = "$OSTT.extra.AUDIO_URI"
        private val BUSY = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
    }

    /** SKILL.md:328 — OSTT first, then any other recognition service. */
    fun providers(): List<ComponentName> =
        context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.exported && it.serviceInfo.enabled }
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { if (it.packageName == OSTT) 0 else 1 }

    fun supportsFileApi(c: ComponentName): Boolean =
        context.packageManager.queryIntentActivities(
            Intent(ACTION_TRANSCRIBE_AUDIO).setPackage(c.packageName)
                .setData(Uri.parse("content://probe/audio")), 0
        ).isNotEmpty()

    /** Audit F1: the probe proves existence, not identity. Pin the package. */
    fun trustsFileApi(c: ComponentName): Boolean =
        c.packageName == OSTT && supportsFileApi(c)

    /** Optional stricter pin: match the signing cert digest you verified. */
    fun certDigest(pkg: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.signingCertificateHistory?.firstOrNull()
            ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Entry point: transcription of a finished note.m4a into note.txt. */
    fun transcribeFile(m4a: File, txt: File, scope: CoroutineScope? = null) {
        val s = scope ?: CoroutineScope(Job() + Dispatchers.Main)
        s.launch(Dispatchers.Main) {
            val durationMs = durationOf(m4a)
            for (c in providers()) {
                val ok = withContext(Dispatchers.IO) {
                    if (trustsFileApi(c)) viaFileApi(c, m4a, txt, durationMs)
                    else viaAudioSource(c, m4a, txt, durationMs)
                }
                if (ok) return@launch
            }
        }
    }

    private fun durationOf(f: File): Long = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val d = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        r.release()
        d ?: 0L
    }.getOrDefault(0L)

    // ---- contract 1: file API (trusted OSTT only) -----------------------
    private suspend fun viaFileApi(
        c: ComponentName, m4a: File, txt: File, durationMs: Long,
    ): Boolean {
        val dir = m4a.parentFile?.name ?: return false
        val uri = app.btmic.store.MiniFileProvider.uriFor(dir, m4a.name)
        val r = SpeechRecognizer.createSpeechRecognizer(context, c)
        var done = false
        val deadline = System.currentTimeMillis() + 180_000 + 2 * durationMs

        fun cleanup() {
            runCatching { context.revokeUriPermission(
                c.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            r.destroy()
        }

        return try {
            val result = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: android.os.Bundle?) {
                        cleanup()
                        val text = b?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            app.btmic.store.NoteStore.saveText(txt, text)
                            cont.resume(true) {}
                        } else cont.resume(false) {} // ERROR_NO_MATCH: do not retry
                    }
                    override fun onError(e: Int) {
                        cleanup()
                        cont.resume(if (e in BUSY) false else false) {}
                    }
                    override fun onReadyForSpeech(p: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: android.os.Bundle?) {}
                    override fun onEvent(t: Int, p: android.os.Bundle?) {}
                })
                context.grantUriPermission(
                    c.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(EXTRA_AUDIO_URI, uri))
                cont.invokeOnCancellation { cleanup() }
            }
            result
        } catch (t: Throwable) { false } finally { if (!done) runCatching { r.destroy() } }
        // A missing answer counts as busy (a newer request can retire ours
        // without a callback): the outer retry loop re-tries until ~deadline.
    }

    // ---- contract 2: caller audio (Android 13+, any recognizer) ---------
    private suspend fun viaAudioSource(
        c: ComponentName, m4a: File, txt: File, durationMs: Long,
    ): Boolean = try {
        val pipe = ParcelFileDescriptor.createPipe()
        val r = SpeechRecognizer.createSpeechRecognizer(context, c)
        val writeEnd = pipe[1]
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        scope.launch { PcmDecoder.decodeTo16kMono(m4a) { pcm ->
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).let {
                val bytes = ByteArray(pcm.size * 2)
                java.nio.ByteBuffer.wrap(bytes).order(
                    java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
                runCatching { it.write(bytes) }
            }
        }; runCatching { writeEnd.close() } }
        val text = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: android.os.Bundle?) {
                    cont.resume(b?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) {}
                }
                override fun onError(e: Int) { cont.resume(null) {} }
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: android.os.Bundle?) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000))
            cont.invokeOnCancellation { runCatching { r.destroy() } }
        }
        runCatching { r.destroy() }
        text?.let { app.btmic.store.NoteStore.saveText(txt, it) }
        !text.isNullOrBlank()
    } catch (t: Throwable) { false }
}
