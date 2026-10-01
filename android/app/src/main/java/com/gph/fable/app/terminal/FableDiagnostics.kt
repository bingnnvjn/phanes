package com.gph.fable.app.terminal

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** File diagnostics sink used when Fable and Termux logcat views differ. */
object FableDiagnostics {
    private const val LOG_NAME = "fable-render-debug.log.txt"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var mediaUri: Uri? = null

    @JvmStatic
    fun init(value: Context) {
        context = value.applicationContext
        if (Build.VERSION.SDK_INT >= 29) cleanupMediaStoreJunk(context!!)
        mediaUri = null
        append("== Fable diagnostics start ==")
    }

    @JvmStatic
    fun append(message: String) {
        val value = context ?: return
        val bytes = (System.currentTimeMillis().toString() + " " + message + "\n")
            .toByteArray(StandardCharsets.UTF_8)
        if (appendToDownloadFile(value, bytes)) return
        if (Build.VERSION.SDK_INT >= 29 && appendViaMediaStore(value, bytes)) return
        appendToPrivateFile(value, bytes)
    }

    private fun appendToDownloadFile(value: Context, bytes: ByteArray): Boolean {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir == null || (!dir.isDirectory && !dir.mkdirs())) return false
            FileOutputStream(File(dir, LOG_NAME), true).use { it.write(bytes) }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun appendViaMediaStore(value: Context, bytes: ByteArray): Boolean {
        return try {
            val resolver = value.contentResolver
            var uri = mediaUri ?: findDownloadUri(resolver, LOG_NAME)
            val created = uri == null
            if (created) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, LOG_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                mediaUri = uri
            }
            resolver.openOutputStream(uri, "wa")?.use { it.write(bytes) } ?: return false
            if (created) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findDownloadUri(resolver: ContentResolver, name: String): Uri? {
        return try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("$name%"),
                MediaStore.Downloads._ID + " ASC"
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0)
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun appendToPrivateFile(value: Context, bytes: ByteArray) {
        try {
            FileOutputStream(File(value.filesDir, LOG_NAME), true).use { it.write(bytes) }
        } catch (_: IOException) {
            // Diagnostics must never block terminal startup.
        }
    }

    private fun cleanupMediaStoreJunk(value: Context) {
        try {
            value.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("fable-render-debug.log (%)%")
            )
        } catch (_: Exception) {
            // Best-effort cleanup.
        }
    }
}
