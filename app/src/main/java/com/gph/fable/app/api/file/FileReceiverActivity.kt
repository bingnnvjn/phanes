package com.gph.fable.app.api.file

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import com.gph.fable.R
import com.gph.fable.app.FableService
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.data.IntentUtils
import com.gph.fable.shared.file.SafeFilePaths
import com.gph.fable.shared.interact.MessageDialogUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.net.uri.UriScheme
import com.gph.fable.shared.net.uri.UriUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.gph.fable.shared.termux.interact.TextInputDialogUtils
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

open class FileReceiverActivity : AppCompatActivity() {

    @JvmField
    var mFinishOnDismissNameDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null || !savedInstanceState.getBoolean(STATE_INTENT_HANDLED)) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INTENT_HANDLED, true)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type
        val scheme = intent.scheme

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null)

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

            when {
                sharedUri != null -> handleContentUri(sharedUri, sharedTitle)
                sharedText != null -> {
                    if (isSharedTextAnUrl(sharedText)) {
                        handleUrlAndFinish(sharedText)
                    } else {
                        var subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null)
                        if (subject == null) subject = sharedTitle
                        if (subject != null) subject += ".txt"
                        val sharedBytes = sharedText.toByteArray(StandardCharsets.UTF_8)
                        promptNameAndSave(InputSource { ByteArrayInputStream(sharedBytes) }, subject)
                    }
                }
                else -> showErrorDialogAndQuit(getString(R.string.error_send_action_without_content))
            }
            return
        }

        val dataUri = intent.data
        if (dataUri == null) {
            showErrorDialogAndQuit(getString(R.string.error_data_uri_not_passed))
            return
        }

        when (scheme) {
            UriScheme.SCHEME_CONTENT -> handleContentUri(dataUri, sharedTitle)
            UriScheme.SCHEME_FILE -> {
                Logger.logVerbose(
                    LOG_TAG,
                    "uri: \"$dataUri\", path: \"${dataUri.path}\", fragment: \"${dataUri.fragment}\""
                )
                val path = UriUtils.getUriFilePathWithFragment(dataUri)
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit(getString(R.string.error_file_path_invalid))
                    return
                }
                val file = File(path)
                promptNameAndSave(InputSource { FileInputStream(file) }, file.name)
            }
            else -> showErrorDialogAndQuit(getString(R.string.error_unable_to_receive_file_or_url))
        }
    }

    fun showErrorDialogAndQuit(message: String) {
        mFinishOnDismissNameDialog = false
        MessageDialogUtils.showMessage(
            this,
            API_TAG,
            message,
            null,
            { _, _ -> finish() },
            null,
            null,
            { finish() }
        )
    }

    fun handleContentUri(uri: Uri, subjectFromIntent: String?) {
        try {
            Logger.logVerbose(
                LOG_TAG,
                "uri: \"$uri\", path: \"${uri.path}\", fragment: \"${uri.fragment}\""
            )

            var attachmentFileName: String? = null
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            getContentResolver().query(uri, projection, null, null, null).use { cursor: Cursor? ->
                if (cursor != null && cursor.moveToFirst()) {
                    val fileNameColumnId = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (fileNameColumnId >= 0) attachmentFileName = cursor.getString(fileNameColumnId)
                }
            }

            if (attachmentFileName == null) attachmentFileName = subjectFromIntent
            if (attachmentFileName == null) attachmentFileName = UriUtils.getUriFileBasename(uri, true)

            promptNameAndSave(
                InputSource {
                    getContentResolver().openInputStream(uri)
                        ?: throw FileNotFoundException("Content provider returned no stream")
                },
                attachmentFileName
            )
        } catch (e: Exception) {
            showErrorDialogAndQuit(getString(R.string.error_unable_to_handle_shared_content, e.message))
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=$uri) failed", e)
        }
    }

    fun promptNameAndSave(source: InputSource, attachmentFileName: String?) {
        TextInputDialogUtils.textInput(
            this,
            R.string.title_file_received,
            attachmentFileName,
            R.string.action_file_received_edit,
            { text ->
                val outFile = saveSourceWithName(source, text)
                if (outFile == null) return@textInput

                val editorProgramFile = File(EDITOR_PROGRAM)
                if (!editorProgramFile.isFile) {
                    showErrorDialogAndQuit(getString(R.string.error_file_editor_missing))
                    return@textInput
                }
                editorProgramFile.setExecutable(true)

                val executeIntent = Intent(
                    TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
                    UriUtils.getFileUri(EDITOR_PROGRAM)
                )
                executeIntent.setClass(this@FileReceiverActivity, FableService::class.java)
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(outFile.absolutePath))
                startService(executeIntent)
                finish()
            },
            R.string.action_file_received_open_directory,
            { text ->
                if (saveSourceWithName(source, text) == null) return@textInput

                val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR)
                executeIntent.setClass(this@FileReceiverActivity, FableService::class.java)
                startService(executeIntent)
                finish()
            },
            android.R.string.cancel,
            { finish() },
            {
                if (mFinishOnDismissNameDialog) finish()
            }
        )
    }

    fun saveSourceWithName(source: InputSource, attachmentFileName: String?): File? {
        val receiveDir = File(TERMUX_RECEIVEDIR)

        if (DataUtils.isNullOrEmpty(attachmentFileName)) {
            showErrorDialogAndQuit(getString(R.string.error_file_name_null))
            return null
        }

        if (!receiveDir.isDirectory && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit(
                getString(R.string.error_cannot_create_directory, receiveDir.absolutePath)
            )
            return null
        }

        try {
            if (SafeFilePaths.resolveLeaf(receiveDir, attachmentFileName) == null) {
                showErrorDialogAndQuit(getString(R.string.error_file_name_invalid))
                return null
            }
            val outFile = SafeFilePaths.createNewLeaf(receiveDir, attachmentFileName)
            if (outFile == null) {
                showErrorDialogAndQuit(getString(R.string.error_file_already_exists))
                return null
            }
            source.open().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(4096)
                    var readBytes: Int
                    while (input.read(buffer).also { readBytes = it } > 0) {
                        output.write(buffer, 0, readBytes)
                    }
                }
            }
            return outFile
        } catch (e: IOException) {
            SafeFilePaths.resolveLeaf(receiveDir, attachmentFileName)?.delete()
            showErrorDialogAndQuit(getString(R.string.error_saving_file, e))
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e)
            return null
        }
    }

    fun interface InputSource {
        @Throws(IOException::class)
        fun open(): InputStream
    }

    fun handleUrlAndFinish(url: String) {
        val urlOpenerProgramFile = File(URL_OPENER_PROGRAM)
        if (!urlOpenerProgramFile.isFile) {
            showErrorDialogAndQuit(getString(R.string.error_url_opener_missing))
            return
        }
        urlOpenerProgramFile.setExecutable(true)

        val executeIntent = Intent(
            TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
            UriUtils.getFileUri(URL_OPENER_PROGRAM)
        )
        executeIntent.setClass(this@FileReceiverActivity, FableService::class.java)
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(url))
        startService(executeIntent)
        finish()
    }

    companion object {
        private const val STATE_INTENT_HANDLED = "intent_handled"
        @JvmField val TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads"
        @JvmField val EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor"
        @JvmField val URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener"
        private const val API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver"
        private const val LOG_TAG = "FileReceiverActivity"

        @JvmStatic
        fun isSharedTextAnUrl(sharedText: String?): Boolean =
            !sharedText.isNullOrEmpty() &&
                (Patterns.WEB_URL.matcher(sharedText).matches() ||
                    Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText))

        @JvmStatic
        fun updateFileReceiverActivityComponentsState(context: Context) {
            Thread {
                val properties = FableAppSharedProperties.getProperties()

                var state = !properties!!.isFileShareReceiverDisabled()
                Logger.logVerbose(
                    LOG_TAG,
                    "Setting ${TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state to $state"
                )
                var errmsg = PackageUtils.setComponentState(
                    context,
                    TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    state,
                    null,
                    false,
                    false
                )
                if (errmsg != null) Logger.logError(LOG_TAG, errmsg)

                state = !properties!!.isFileViewReceiverDisabled()
                Logger.logVerbose(
                    LOG_TAG,
                    "Setting ${TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state to $state"
                )
                errmsg = PackageUtils.setComponentState(
                    context,
                    TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    state,
                    null,
                    false,
                    false
                )
                if (errmsg != null) Logger.logError(LOG_TAG, errmsg)
            }.start()
        }
    }
}
