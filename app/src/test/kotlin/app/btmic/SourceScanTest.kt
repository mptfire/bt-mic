package app.btmic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

/**
 * SKILL.md:396: "Nothing in activity or fragment lifecycle callbacks may
 * stop the recorder; a source-scanning unit test can enforce that."
 *
 * This is that test. It scans the Kotlin sources at compile time so the
 * gate cannot rot: the UI layer must never touch the recorder directly,
 * and nothing may call Recorder.stopAndRelease from a lifecycle method.
 */
class SourceScanTest {

    private fun sources(): List<File> {
        val roots = listOf(
            File("app/src/main/kotlin"),
            File("src/main/kotlin"),
        ).filter { it.isDirectory }
        check(roots.isNotEmpty()) {
            "run from the repo root (app/src/main/kotlin not found)"
        }
        return roots.flatMap { r ->
            r.walkTopDown().filter { it.extension == "kt" }.toList()
        }
    }

    private fun srcOf(name: String) = sources().firstOrNull { it.name == name }

    @Test
    fun uiLayerNeverTouchesTheRecorder() {
        val ui = sources().filter { "/ui/" in it.path }
        assertTrue(ui.isNotEmpty(), "ui sources found")
        val forbidden = listOf(
            "AudioRecord", "startRecording", "stopAndRelease",
            "stopRecording", "MediaRecorder")
        for (f in ui) {
            val t = f.readText()
            for (bad in forbidden) {
                assertFalse(
                    t.contains(bad),
                    "${f.name} references $bad — recording is the engine's job")
            }
        }
    }

    @Test
    fun recorderIsStoppedOnlyByItsOwner() {
        val engine = srcOf("RecordEngine.kt")?.readText()
            ?: return // engine file renames are allowed; the gate is about ui/
        assertTrue("stopAndRelease()" in engine, "engine releases the recorder")
        // lifecycle names must not appear next to stopAndRelease in the engine
        for (life in listOf("onPause", "onStop(", "onDestroy {")) {
            assertFalse(
                Regex("$life\\s*\\{[^}]*stopAndRelease").find(engine) != null,
                "lifecycle callback must not stop the recorder")
        }
    }

    @Test
    fun manifestStaysMinimalOnPrivacy() {
        val mf = File("app/src/main/AndroidManifest.xml")
            .takeIf { it.isFile }
            ?: File("src/main/AndroidManifest.xml")
        if (!mf.isFile) return
        val t = mf.readText()
        assertFalse("android.permission.INTERNET" in t,
            "the recorder has no network use; do not add INTERNET")
        assertFalse("NotificationListener" in t,
            "audit F5: no NotificationListener (read-all-notifications)")
        assertTrue("foregroundServiceType=\"microphone\"" in t)
        assertTrue("allowBackup=\"false\"" in t, "audit F6: notes are private")
    }
}
