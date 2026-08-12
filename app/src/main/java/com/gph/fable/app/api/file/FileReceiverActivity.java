package com.gph.fable.app.api.file;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.gph.fable.R;
import com.gph.fable.shared.android.PackageUtils;
import com.gph.fable.shared.data.DataUtils;
import com.gph.fable.shared.data.IntentUtils;
import com.gph.fable.shared.file.SafeFilePaths;
import com.gph.fable.shared.net.uri.UriUtils;
import com.gph.fable.shared.interact.MessageDialogUtils;
import com.gph.fable.shared.net.uri.UriScheme;
import com.gph.fable.shared.termux.interact.TextInputDialogUtils;
import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP;
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.gph.fable.app.FableService;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties;
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class FileReceiverActivity extends AppCompatActivity {

    private static final String STATE_INTENT_HANDLED = "intent_handled";

    static final String TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads";
    static final String EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor";
    static final String URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener";

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    boolean mFinishOnDismissNameDialog = true;

    private static final String API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver";

    private static final String LOG_TAG = "FileReceiverActivity";

    static boolean isSharedTextAnUrl(String sharedText) {
        if (sharedText == null || sharedText.isEmpty()) return false;

        return Patterns.WEB_URL.matcher(sharedText).matches()
            || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null || !savedInstanceState.getBoolean(STATE_INTENT_HANDLED)) {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_INTENT_HANDLED, true);
        super.onSaveInstanceState(outState);
    }

    private void handleIntent(@NonNull Intent intent) {
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        final String sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle);
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText);
                } else {
                    String subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);
                    if (subject == null) subject = sharedTitle;
                    if (subject != null) subject += ".txt";
                    byte[] sharedBytes = sharedText.getBytes(StandardCharsets.UTF_8);
                    promptNameAndSave(() -> new ByteArrayInputStream(sharedBytes), subject);
                }
            } else {
                showErrorDialogAndQuit(getString(R.string.error_send_action_without_content));
            }
        } else {
            Uri dataUri = intent.getData();

            if (dataUri == null) {
                showErrorDialogAndQuit(getString(R.string.error_data_uri_not_passed));
                return;
            }

            if (UriScheme.SCHEME_CONTENT.equals(scheme)) {
                handleContentUri(dataUri, sharedTitle);
            } else if (UriScheme.SCHEME_FILE.equals(scheme)) {
                Logger.logVerbose(LOG_TAG, "uri: \"" + dataUri + "\", path: \"" + dataUri.getPath() + "\", fragment: \"" + dataUri.getFragment() + "\"");

                // Get full path including fragment (anything after last "#")
                String path = UriUtils.getUriFilePathWithFragment(dataUri);
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit(getString(R.string.error_file_path_invalid));
                    return;
                }

                File file = new File(path);
                promptNameAndSave(() -> new FileInputStream(file), file.getName());
            } else {
                showErrorDialogAndQuit(getString(R.string.error_unable_to_receive_file_or_url));
            }
        }
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    void handleContentUri(@NonNull final Uri uri, String subjectFromIntent) {
        try {
            Logger.logVerbose(LOG_TAG, "uri: \"" + uri + "\", path: \"" + uri.getPath() + "\", fragment: \"" + uri.getFragment() + "\"");

            String attachmentFileName = null;

            String[] projection = new String[]{OpenableColumns.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    final int fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId);
                }
            }

            if (attachmentFileName == null) attachmentFileName = subjectFromIntent;
            if (attachmentFileName == null) attachmentFileName = UriUtils.getUriFileBasename(uri, true);

            promptNameAndSave(() -> {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new FileNotFoundException("Content provider returned no stream");
                return in;
            }, attachmentFileName);
        } catch (Exception e) {
            showErrorDialogAndQuit(getString(R.string.error_unable_to_handle_shared_content, e.getMessage()));
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=" + uri + ") failed", e);
        }
    }

    void promptNameAndSave(final InputSource source, final String attachmentFileName) {
        TextInputDialogUtils.textInput(this, R.string.title_file_received, attachmentFileName,
            R.string.action_file_received_edit, text -> {
                File outFile = saveSourceWithName(source, text);
                if (outFile == null) return;

                final File editorProgramFile = new File(EDITOR_PROGRAM);
                if (!editorProgramFile.isFile()) {
                    showErrorDialogAndQuit(getString(R.string.error_file_editor_missing));
                    return;
                }

                // Do this for the user if necessary:
                //noinspection ResultOfMethodCallIgnored
                editorProgramFile.setExecutable(true);

                final Uri scriptUri = UriUtils.getFileUri(EDITOR_PROGRAM);

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
                executeIntent.setClass(FileReceiverActivity.this, FableService.class);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{outFile.getAbsolutePath()});
                startService(executeIntent);
                finish();
            },
            R.string.action_file_received_open_directory, text -> {
                if (saveSourceWithName(source, text) == null) return;

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR);
                executeIntent.setClass(FileReceiverActivity.this, FableService.class);
                startService(executeIntent);
                finish();
            },
            android.R.string.cancel, text -> finish(), dialog -> {
                if (mFinishOnDismissNameDialog) finish();
            });
    }

    public File saveSourceWithName(InputSource source, String attachmentFileName) {
        File receiveDir = new File(TERMUX_RECEIVEDIR);

        if (DataUtils.isNullOrEmpty(attachmentFileName)) {
            showErrorDialogAndQuit(getString(R.string.error_file_name_null));
            return null;
        }

        if (!receiveDir.isDirectory() && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit(getString(R.string.error_cannot_create_directory, receiveDir.getAbsolutePath()));
            return null;
        }

        try {
            if (SafeFilePaths.resolveLeaf(receiveDir, attachmentFileName) == null) {
                showErrorDialogAndQuit(getString(R.string.error_file_name_invalid));
                return null;
            }
            final File outFile = SafeFilePaths.createNewLeaf(receiveDir, attachmentFileName);
            if (outFile == null) {
                showErrorDialogAndQuit(getString(R.string.error_file_already_exists));
                return null;
            }
            try (InputStream in = source.open();
                 FileOutputStream f = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int readBytes;
                while ((readBytes = in.read(buffer)) > 0) {
                    f.write(buffer, 0, readBytes);
                }
            }
            return outFile;
        } catch (IOException e) {
            File incompleteFile = SafeFilePaths.resolveLeaf(receiveDir, attachmentFileName);
            if (incompleteFile != null) {
                //noinspection ResultOfMethodCallIgnored
                incompleteFile.delete();
            }
            showErrorDialogAndQuit(getString(R.string.error_saving_file, e));
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e);
            return null;
        }
    }

    @FunctionalInterface
    interface InputSource {
        InputStream open() throws IOException;
    }

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit(getString(R.string.error_url_opener_missing));
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true);

        final Uri urlOpenerProgramUri = UriUtils.getFileUri(URL_OPENER_PROGRAM);

        Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri);
        executeIntent.setClass(FileReceiverActivity.this, FableService.class);
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{url});
        startService(executeIntent);
        finish();
    }

    /**
     * Update {@link TERMUX_APP#FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_SHARE_RECEIVER} value and
     * {@link TERMUX_APP#FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_VIEW_RECEIVER} value.
     */
    public static void updateFileReceiverActivityComponentsState(@NonNull Context context) {
        new Thread() {
            @Override
            public void run() {
                FableAppSharedProperties properties = FableAppSharedProperties.getProperties();

                String errmsg;
                boolean state;

                state = !properties.isFileShareReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

                state = !properties.isFileViewReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

            }
        }.start();
    }

}
