package com.spectroflac.queue

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/** Name, size and modification time of a file behind a content or file URI. */
object FileInfo {

    fun resolve(context: Context, uri: Uri): NewFile {
        if (uri.scheme == "file") {
            val file = File(uri.path.orEmpty())
            return NewFile(uri.toString(), file.name.ifEmpty { "audio.flac" }, file.length(), file.lastModified())
        }
        var name: String? = null
        var size = 0L
        var modified = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                    modified = if (c.isNull(2)) 0L else c.getLong(2)
                }
            }
        }.onFailure {
            // Providers that do not know the document columns reject the query: ask for the basics only.
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = if (c.isNull(1)) 0L else c.getLong(1)
                    }
                }
            }
        }
        return NewFile(uri.toString(), name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio.flac", size, modified)
    }

    fun lastModified(context: Context, uri: Uri): Long = resolve(context, uri).lastModified
}
