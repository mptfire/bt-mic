package app.btmic.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Minimal FileProvider replacement (no androidx dependency). Read-only,
 * non-exported; access is given only through grantUriPermission(), which the
 * transcriber revokes on every exit path (audit F1/F2).
 */
class MiniFileProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        // content://app.btmic.notes/note/<dirName>/<fileName>
        val parts = uri.pathSegments
        require(parts.size == 3 && parts[0] == "note") { "bad uri" }
        require(!parts[1].contains('/') && !parts[2].contains('/')) { "bad path" }
        val root = NoteStore.dir(context!!)
        return File(File(root, parts[1]), parts[2])
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "read-only" }
        val f = fileFor(uri)
        return ParcelFileDescriptor.open(
            f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, p: Array<out String>?, q: String?, a: Array<out String>?, o: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".txt") == true) "text/plain"
        else "audio/mp4"

    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        fun uriFor(dirName: String, fileName: String): Uri =
            Uri.parse("content://app.btmic.notes/note/$dirName/$fileName")
    }
}
