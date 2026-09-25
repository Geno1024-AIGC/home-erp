package g.erp.satellite.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_PACKAGE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: error("missing file name in ${uri}")
        val file = File(context!!.cacheDir, "updates/$name")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = error("unsupported query: $uri")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        error("unsupported insert: $uri")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val MIME_PACKAGE = "application/vnd.android.package-archive"

        fun uriFor(context: Context, apk: File): Uri =
            Uri.parse("content://g.erp.satellite.apk/${Uri.encode(apk.name)}")
    }
}