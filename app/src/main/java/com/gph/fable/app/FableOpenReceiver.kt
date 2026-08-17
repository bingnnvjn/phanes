package com.gph.fable.app

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.data.IntentUtils
import com.gph.fable.shared.file.SafeFilePaths
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.net.uri.UriScheme
import com.gph.fable.shared.net.uri.UriUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.plugins.FablePluginUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

class FableOpenReceiver : BroadcastReceiver() {

    companion object {
        private const val LOG_TAG = "FableOpenReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data
        if (data == null) {
            Logger.logError(LOG_TAG, "Called without intent data")
            return
        }

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))
        Logger.logVerbose(
            LOG_TAG,
            "uri: \"" + data + "\", path: \"" + data.path + "\", fragment: \"" + data.fragment + "\""
        )

        val contentTypeExtra = intent.getStringExtra("content-type")
        val useChooser = intent.getBooleanExtra("chooser", false)
        val intentAction = intent.action ?: Intent.ACTION_VIEW
        when (intentAction) {
            Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
                // Ok.
            }
            else -> Logger.logError(LOG_TAG, "Invalid action '$intentAction', using 'view'")
        }

        val scheme = data.scheme
        if (scheme != null && UriScheme.SCHEME_FILE != scheme) {
            val urlIntent = Intent(intentAction, data)
            if (intentAction == Intent.ACTION_SEND) {
                urlIntent.putExtra(Intent.EXTRA_TEXT, data.toString())
                urlIntent.setData(null)
            } else if (contentTypeExtra != null) {
                urlIntent.setDataAndType(data, contentTypeExtra)
            }
            urlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(urlIntent)
            } catch (_: ActivityNotFoundException) {
                Logger.logError(LOG_TAG, "No app handles the url $data")
            }
            return
        }

        // Get full path including fragment (anything after last "#")
        val filePath = UriUtils.getUriFilePathWithFragment(data)
        if (DataUtils.isNullOrEmpty(filePath)) {
            Logger.logError(LOG_TAG, "filePath is null or empty")
            return
        }

        val fileToShare = File(filePath!!)
        if (!(fileToShare.isFile && fileToShare.canRead())) {
            Logger.logError(LOG_TAG, "Not a readable file: '${fileToShare.absolutePath}'")
            return
        }

        var sendIntent = Intent()
        sendIntent.action = intentAction
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val contentTypeToUse = if (contentTypeExtra == null) {
            val fileName = fileToShare.name
            val lastDotIndex = fileName.lastIndexOf('.')
            val fileExtension = fileName.substring(lastDotIndex + 1)
            val mimeTypes = MimeTypeMap.getSingleton()
            // Lower casing makes it work with e.g. "JPG":
            mimeTypes.getMimeTypeFromExtension(fileExtension.lowercase(Locale.getDefault()))
                ?: "application/octet-stream"
        } else {
            contentTypeExtra
        }

        // Do not create Uri with Uri.parse() and use Uri.Builder().path(), check UriUtils.getUriFilePath().
        val uriToShare = UriUtils.getContentUri(
            TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY,
            fileToShare.absolutePath
        )

        if (Intent.ACTION_SEND == intentAction) {
            sendIntent.putExtra(Intent.EXTRA_STREAM, uriToShare)
            sendIntent.type = contentTypeToUse
        } else {
            sendIntent.setDataAndType(uriToShare, contentTypeToUse)
        }

        if (useChooser) {
            sendIntent = Intent.createChooser(sendIntent, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(sendIntent)
        } catch (_: ActivityNotFoundException) {
            Logger.logError(LOG_TAG, "No app handles the url $data")
        }
    }

    /**
     * Content provider backing the file-sharing URI authority.
     *
     * This remains a nested (rather than inner) class so its generated name stays
     * `FableOpenReceiver$ContentProvider`, matching the manifest and provider ABI.
     */
    class ContentProvider : android.content.ContentProvider() {

        companion object {
            private const val LOG_TAG = "FableContentProvider"
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val file = File(uri.path!!)
            val columns = projection ?: arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns._ID
            )

            val row = arrayOfNulls<Any>(columns.size)
            for (i in columns.indices) {
                val value: Any? = when (columns[i]) {
                    MediaStore.MediaColumns.DISPLAY_NAME -> file.name
                    MediaStore.MediaColumns.SIZE -> file.length().toInt()
                    MediaStore.MediaColumns._ID -> 1
                    else -> null
                }
                row[i] = value
            }

            return MatrixCursor(columns).also { it.addRow(row) }
        }

        override fun getType(uri: Uri): String? {
            val path = uri.lastPathSegment!!
            val extIndex = path.lastIndexOf('.') + 1
            if (extIndex > 0) {
                val mimeMap = MimeTypeMap.getSingleton()
                val ext = path.substring(extIndex).lowercase(Locale.getDefault())
                return mimeMap.getMimeTypeFromExtension(ext)
            }
            return null
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        @Throws(FileNotFoundException::class)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val requestedPath = uri.path
                ?: throw FileNotFoundException("Missing file path")
            val requestedFile = File(requestedPath)
            var file: File
            var modeToUse = mode
            try {
                val filesRoot = File(TermuxConstants.TERMUX_FILES_DIR_PATH)
                val storageRoot = Environment.getExternalStorageDirectory()
                file = SafeFilePaths.resolveWithin(filesRoot, requestedFile, true)
                    ?: SafeFilePaths.resolveWithin(storageRoot, requestedFile, true)
                    ?: throw IllegalArgumentException("Invalid path: $requestedFile")
                val path = file.canonicalPath
                val callingPackageName = callingPackage
                Logger.logDebug(
                    LOG_TAG,
                    "Open file request received from $callingPackageName for \"$path\" with mode \"$modeToUse\""
                )

                // If TermuxConstants.PROP_ALLOW_EXTERNAL_APPS property to not set to "true", then throw exception
                val errmsg = FablePluginUtils.checkIfAllowExternalAppsPolicyIsViolated(
                    context,
                    LOG_TAG
                )
                if (errmsg != null) {
                    throw IllegalArgumentException(errmsg)
                }

                // **DO NOT** allow these files to be modified by ContentProvider exposed to external
                // apps, since they may silently modify the values for security properties like
                // TermuxConstants.PROP_ALLOW_EXTERNAL_APPS set by users without their explicit consent.
                if (TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST.contains(path) ||
                    TermuxConstants.TERMUX_FLOAT_PROPERTIES_FILE_PATHS_LIST.contains(path)
                ) {
                    modeToUse = "r"
                }
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }

            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(modeToUse))
        }
    }
}
