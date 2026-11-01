package app.btmic.store

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit F6: notes live ONLY in app-internal storage, which is private and
 * excluded from cloud backup via res/xml/backup_rules.xml + allowBackup=false.
 * Shared storage only through an explicit user share.
 */
object NoteStore {

    fun dir(context: Context): File =
        File(context.filesDir, "notes").apply { mkdirs() }

    fun newNames(context: Context): Pair<File, File> {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val d = File(dir(context), ts).apply { mkdirs() }
        return File(d, "note.m4a") to File(d, "note.txt")
    }

    fun saveText(txt: File, content: String) {
        txt.writeText(content)
    }
}
