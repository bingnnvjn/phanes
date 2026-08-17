package com.gph.fable.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import android.util.Pair
import android.view.WindowManager

import com.gph.fable.BuildConfig
import com.gph.fable.R
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.interact.MessageDialogUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.runner.app.AppShell
import com.gph.fable.shared.termux.FableBootstrap
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.crash.FableCrashUtils
import com.gph.fable.shared.termux.file.FableFileUtils
import com.gph.fable.shared.termux.shell.command.environment.FableShellEnvironment
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

import com.gph.fable.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH

/**
 * Install the Fable bootstrap packages if necessary by following the below steps:
 *
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 *
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 *
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 *
 * (4) The zip file is loaded from a shared library.
 *
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 *
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 *
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
object FableInstaller {

    private const val LOG_TAG = "FableInstaller"

    /** Performs bootstrap setup if necessary. */
    @JvmStatic
    fun setupBootstrapIfNeeded(activity: Activity, whenDone: Runnable) {
        var bootstrapErrorMessage: String

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        val filesDirectoryAccessibleError =
            FableFileUtils.isFableFilesDirectoryAccessible(activity, true, true)
        val isFilesDirectoryAccessible = filesDirectoryAccessibleError == null

        // Fable can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !PackageUtils.isCurrentUserThePrimaryUser(activity)
        ) {
            bootstrapErrorMessage = activity.getString(
                R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(
                    TERMUX_PREFIX_DIR_PATH,
                    false
                )
            )
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: $isFilesDirectoryAccessible")
            Logger.logError(LOG_TAG, bootstrapErrorMessage)
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
            MessageDialogUtils.exitAppWithErrorMessage(
                activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage
            )
            return
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError)
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(
                    activity.filesDir.absolutePath.replace(Regex("^/data/user/0/"), "/data/data/")
                )
            ) {
                bootstrapErrorMessage += "\n\n" + activity.getString(
                    R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(
                        TERMUX_PREFIX_DIR_PATH,
                        false
                    )
                )
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage)
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
            MessageDialogUtils.showMessage(
                activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage,
                null
            )
            return
        }

        if (!checkIfMinOrMaxSdkVersionIsIncompatible(
                activity,
                BuildConfig.TERMUX_APP__BOOTSTRAP_MIN_SDK,
                BuildConfig.TERMUX_APP__BOOTSTRAP_MIN_RELEASE,
                BuildConfig.TERMUX_APP__BOOTSTRAP_MAX_SDK,
                BuildConfig.TERMUX_APP__BOOTSTRAP_MAX_RELEASE
            )
        ) {
            return
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (FableFileUtils.isFablePrefixDirectoryEmpty()) {
                Logger.logInfo(
                    LOG_TAG,
                    "The termux prefix directory \"$TERMUX_PREFIX_DIR_PATH\" exists but is empty or only contains specific unimportant files."
                )
            } else {
                suppressFableMotd(activity)
                whenDone.run()
                return
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(
                LOG_TAG,
                "The termux prefix directory \"$TERMUX_PREFIX_DIR_PATH\" does not exist but another file exists at its destination."
            )
        }

        val progress = ProgressDialog.show(
            activity,
            null,
            activity.getString(R.string.bootstrap_installer_body),
            true,
            false
        )
        Thread {
            try {
                Logger.logInfo(
                    LOG_TAG,
                    "Installing ${TermuxConstants.TERMUX_APP_NAME} bootstrap packages."
                )

                // Delete prefix staging directory or any file at its destination
                var error = FileUtils.deleteFile(
                    "termux prefix staging directory",
                    TERMUX_STAGING_PREFIX_DIR_PATH,
                    true
                )
                if (error != null) {
                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                    return@Thread
                }

                // Delete prefix directory or any file at its destination
                error = FileUtils.deleteFile(
                    "termux prefix directory",
                    TERMUX_PREFIX_DIR_PATH,
                    true
                )
                if (error != null) {
                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                    return@Thread
                }

                // Create prefix staging directory if it does not already exist and set required permissions
                error = FableFileUtils.isFablePrefixStagingDirectoryAccessible(true, true)
                if (error != null) {
                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                    return@Thread
                }

                // Create prefix directory if it does not already exist and set required permissions
                error = FableFileUtils.isFablePrefixDirectoryAccessible(true, true)
                if (error != null) {
                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                    return@Thread
                }

                Logger.logInfo(
                    LOG_TAG,
                    "Extracting bootstrap zip to prefix staging directory \"$TERMUX_STAGING_PREFIX_DIR_PATH\"."
                )

                val buffer = ByteArray(8096)
                val symlinks = ArrayList<Pair<String, String>>(50)

                val zipBytes = loadZipBytes()
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
                    var zipEntry: ZipEntry?
                    while (zipInput.nextEntry.also { zipEntry = it } != null) {
                        val entry = zipEntry!!
                        if (entry.name == "SYMLINKS.txt") {
                            // Do not close this reader here: it wraps zipInput and the next zip entry
                            // must remain readable after the symlink list has been consumed.
                            val symlinksReader = BufferedReader(InputStreamReader(zipInput))
                            var line: String?
                            while (symlinksReader.readLine().also { line = it } != null) {
                                val parts = line!!.split("←")
                                if (parts.size != 2) {
                                    throw RuntimeException("Malformed symlink line: ${line!!}")
                                }
                                val oldPath = parts[0]
                                val newPath = "$TERMUX_STAGING_PREFIX_DIR_PATH/${parts[1]}"
                                symlinks.add(Pair.create(oldPath, newPath))

                                error = ensureDirectoryExists(File(newPath).parentFile!!)
                                if (error != null) {
                                    showBootstrapErrorDialog(
                                        activity,
                                        whenDone,
                                        Error.getErrorMarkdownString(error)
                                    )
                                    return@Thread
                                }
                            }
                        } else {
                            val zipEntryName = entry.name
                            val targetFile = File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName)
                            val isDirectory = entry.isDirectory

                            error = ensureDirectoryExists(
                                if (isDirectory) targetFile else targetFile.parentFile!!
                            )
                            if (error != null) {
                                showBootstrapErrorDialog(
                                    activity,
                                    whenDone,
                                    Error.getErrorMarkdownString(error)
                                )
                                return@Thread
                            }

                            if (!isDirectory) {
                                FileOutputStream(targetFile).use { outStream ->
                                    while (true) {
                                        val readBytes = zipInput.read(buffer)
                                        if (readBytes == -1) break
                                        outStream.write(buffer, 0, readBytes)
                                    }
                                }
                                if (zipEntryName.startsWith("bin/") ||
                                    zipEntryName.startsWith("libexec") ||
                                    zipEntryName.startsWith("lib/apt/apt-helper") ||
                                    zipEntryName.startsWith("lib/apt/methods") ||
                                    zipEntryName == "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
                                ) {
                                    //noinspection OctalInteger
                                    Os.chmod(targetFile.absolutePath, 448)
                                }
                            }
                        }
                    }
                }

                if (symlinks.isEmpty()) {
                    throw RuntimeException("No SYMLINKS.txt encountered")
                }
                for (symlink in symlinks) {
                    Os.symlink(symlink.first, symlink.second)
                }

                Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.")

                if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                    throw RuntimeException("Moving termux prefix staging to prefix directory failed")
                }

                // Run Fable bootstrap second stage.
                val termuxBootstrapSecondStageFile =
                    "$TERMUX_PREFIX_DIR_PATH/etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
                if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                    Logger.logInfo(
                        LOG_TAG,
                        "Not running Fable bootstrap second stage since script not found at \"$termuxBootstrapSecondStageFile\" path."
                    )
                } else {
                    if (!FileUtils.fileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", true)) {
                        Logger.logInfo(
                            LOG_TAG,
                            "Not running Fable bootstrap second stage since bash not found."
                        )
                    }
                    Logger.logInfo(LOG_TAG, "Running Fable bootstrap second stage.")

                    val executionCommand = ExecutionCommand(
                        -1,
                        termuxBootstrapSecondStageFile,
                        null,
                        null,
                        null,
                        ExecutionCommand.Runner.APP_SHELL.getName(),
                        false
                    )
                    executionCommand.commandLabel = "Fable Bootstrap Second Stage Command"
                    executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL
                    val appShell = AppShell.execute(
                        activity,
                        executionCommand,
                        null,
                        FableShellEnvironment(),
                        null,
                        true
                    )
                    if (appShell == null ||
                        !executionCommand.isSuccessful() ||
                        executionCommand.resultData.exitCode != 0
                    ) {
                        // Generate debug report before deleting broken prefix directory to get `stat` info at time of failure.
                        showBootstrapErrorDialog(
                            activity,
                            whenDone,
                            MarkdownUtils.getMarkdownCodeForString(
                                executionCommand.toString(),
                                true
                            )
                        )

                        // Delete prefix directory as otherwise when app is restarted, the broken prefix directory would be used and logged into.
                        Logger.logInfo(LOG_TAG, "Deleting broken termux prefix.")
                        error = FileUtils.deleteFile(
                            "termux prefix directory",
                            TERMUX_PREFIX_DIR_PATH,
                            true
                        )
                        if (error != null) {
                            Logger.logErrorExtended(LOG_TAG, error.toString())
                        }
                        return@Thread
                    }
                }

                Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.")

                // Recreate env file since termux prefix was wiped earlier
                FableShellEnvironment.writeEnvironmentToFile(activity)

                suppressFableMotd(activity)
                activity.runOnUiThread(whenDone)
            } catch (e: Exception) {
                showBootstrapErrorDialog(
                    activity,
                    whenDone,
                    Logger.getStackTracesMarkdownString(
                        null,
                        Logger.getStackTracesStringArray(e)
                    )
                )
            } finally {
                activity.runOnUiThread {
                    try {
                        progress.dismiss()
                    } catch (_: RuntimeException) {
                        // Activity already dismissed - ignore.
                    }
                }
            }
        }.start()
    }

    /**
     * 工单 26：主终端只显示 Bash 登录提示——截空 `$PREFIX/etc/motd`。
     * bin/login 登录时 cat 该文件打印 Termux 宣传语（Welcome to Termux! / Docs /
     * Donate / Community / Working with packages）。幂等；保留文件本身（termux-tools
     * 的 conffile，删除会被 pkg upgrade 当作缺失恢复，截空后本地修改不被覆盖）。
     */
    @JvmStatic
    fun suppressFableMotd(context: Context) {
        try {
            val motd = File("${TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH}/motd")
            if (motd.isFile && motd.length() > 0) {
                FileOutputStream(motd, false).use {
                    // 截断为 0 字节（空文件不打印任何欢迎语）。
                }
                Logger.logInfo(
                    LOG_TAG,
                    "Fable motd suppressed (truncated ${motd.absolutePath})"
                )
            }
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "suppressFableMotd failed: $e")
        }
    }

    @JvmStatic
    fun checkIfMinOrMaxSdkVersionIsIncompatible(
        activity: Activity,
        minSdk: Int?,
        minRelease: String?,
        maxSdk: Int?,
        maxRelease: String?
    ): Boolean {
        if (minSdk != null && Build.VERSION.SDK_INT < minSdk) {
            val bootstrapErrorMessage = activity.getString(
                R.string.bootstrap_error_apk_bootstrap_variant_min_sdk_incompatible,
                MarkdownUtils.getMarkdownCodeForString(
                    FableBootstrap.TERMUX_APP_PACKAGE_VARIANT!!.getName(),
                    false
                ),
                MarkdownUtils.getMarkdownCodeForString(Build.VERSION.RELEASE, false),
                Build.VERSION.SDK_INT,
                MarkdownUtils.getMarkdownCodeForString(minRelease, false),
                minSdk
            )
            Logger.logError(LOG_TAG, bootstrapErrorMessage)
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
            MessageDialogUtils.exitAppWithErrorMessage(
                activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage
            )
            return false
        }

        if (maxSdk != null && Build.VERSION.SDK_INT > maxSdk) {
            val bootstrapErrorMessage = activity.getString(
                R.string.bootstrap_error_apk_bootstrap_variant_max_sdk_incompatible,
                MarkdownUtils.getMarkdownCodeForString(
                    FableBootstrap.TERMUX_APP_PACKAGE_VARIANT!!.getName(),
                    false
                ),
                MarkdownUtils.getMarkdownCodeForString(Build.VERSION.RELEASE, false),
                Build.VERSION.SDK_INT,
                MarkdownUtils.getMarkdownCodeForString(maxRelease, false),
                maxSdk
            )
            Logger.logError(LOG_TAG, bootstrapErrorMessage)
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
            MessageDialogUtils.exitAppWithErrorMessage(
                activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage
            )
            return false
        }

        return true
    }

    @JvmStatic
    fun showBootstrapErrorDialog(activity: Activity, whenDone: Runnable, message: String?) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n$message")

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message)

        activity.runOnUiThread {
            try {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.bootstrap_error_title)
                    .setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort) { dialog, _ ->
                        dialog.dismiss()
                        activity.finish()
                    }
                    .setPositiveButton(R.string.bootstrap_error_try_again) { dialog, _ ->
                        dialog.dismiss()
                        FileUtils.deleteFile(
                            "termux prefix directory",
                            TERMUX_PREFIX_DIR_PATH,
                            true
                        )
                        setupBootstrapIfNeeded(activity, whenDone)
                    }
                    .show()
            } catch (_: WindowManager.BadTokenException) {
                // Activity already dismissed - ignore.
            }
        }
    }

    private fun sendBootstrapCrashReportNotification(activity: Activity, message: String?) {
        val title = "${TermuxConstants.TERMUX_APP_NAME} Bootstrap Error"

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Fable app files directory access or exec.
        FableCrashUtils.sendCrashReportNotification(
            activity,
            LOG_TAG,
            title,
            null,
            "## $title\n\n$message\n\n" + FableUtils.getFableDebugMarkdownString(activity),
            true,
            false,
            FableUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES,
            true
        )
    }

    @JvmStatic
    fun setupStorageSymlinks(context: Context) {
        val logTag = "termux-storage"
        val title = "${TermuxConstants.TERMUX_APP_NAME} Setup Storage Error"

        Logger.logInfo(logTag, "Setting up storage symlinks.")

        Thread {
            try {
                var error: Error?
                val storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR

                error = FileUtils.clearDirectory("~/storage", storageDir.absolutePath)
                if (error != null) {
                    Logger.logErrorAndShowToast(context, logTag, error.getMessage())
                    Logger.logErrorExtended(logTag, "Setup Storage Error\n$error")
                    FableCrashUtils.sendCrashReportNotification(
                        context,
                        logTag,
                        title,
                        null,
                        "## $title\n\n" + Error.getErrorMarkdownString(error),
                        true,
                        false,
                        FableUtils.AppInfoMode.TERMUX_PACKAGE,
                        true
                    )
                    return@Thread
                }

                Logger.logInfo(
                    logTag,
                    "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"${Environment.getExternalStorageDirectory().absolutePath}\"."
                )

                // Get primary storage root "/storage/emulated/0" symlink
                val sharedDir = Environment.getExternalStorageDirectory()
                Os.symlink(sharedDir.absolutePath, File(storageDir, "shared").absolutePath)

                val documentsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                Os.symlink(documentsDir.absolutePath, File(storageDir, "documents").absolutePath)

                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                Os.symlink(downloadsDir.absolutePath, File(storageDir, "downloads").absolutePath)

                val dcimDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                Os.symlink(dcimDir.absolutePath, File(storageDir, "dcim").absolutePath)

                val picturesDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                Os.symlink(picturesDir.absolutePath, File(storageDir, "pictures").absolutePath)

                val musicDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                Os.symlink(musicDir.absolutePath, File(storageDir, "music").absolutePath)

                val moviesDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                Os.symlink(moviesDir.absolutePath, File(storageDir, "movies").absolutePath)

                val podcastsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)
                Os.symlink(podcastsDir.absolutePath, File(storageDir, "podcasts").absolutePath)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val audiobooksDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)
                    Os.symlink(audiobooksDir.absolutePath, File(storageDir, "audiobooks").absolutePath)
                }

                // Dir 0 should ideally be for primary storage
                // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                // Create "Android/data/com.gph.fable" symlinks
                var dirs = context.getExternalFilesDirs(null)
                if (dirs != null && dirs.isNotEmpty()) {
                    for (i in dirs.indices) {
                        val dir = dirs[i] ?: continue
                        val symlinkName = "external-$i"
                        Logger.logInfo(
                            logTag,
                            "Setting up storage symlinks at ~/storage/$symlinkName for \"${dir.absolutePath}\"."
                        )
                        Os.symlink(dir.absolutePath, File(storageDir, symlinkName).absolutePath)
                    }
                }

                // Create "Android/media/com.gph.fable" symlinks
                dirs = context.getExternalMediaDirs()
                if (dirs != null && dirs.isNotEmpty()) {
                    for (i in dirs.indices) {
                        val dir = dirs[i] ?: continue
                        val symlinkName = "media-$i"
                        Logger.logInfo(
                            logTag,
                            "Setting up storage symlinks at ~/storage/$symlinkName for \"${dir.absolutePath}\"."
                        )
                        Os.symlink(dir.absolutePath, File(storageDir, symlinkName).absolutePath)
                    }
                }

                Logger.logInfo(logTag, "Storage symlinks created successfully.")
            } catch (e: Exception) {
                Logger.logErrorAndShowToast(context, logTag, e.message)
                Logger.logStackTraceWithMessage(logTag, "Setup Storage Error: Error setting up link", e)
                FableCrashUtils.sendCrashReportNotification(
                    context,
                    logTag,
                    title,
                    null,
                    "## $title\n\n" +
                        Logger.getStackTracesMarkdownString(
                            null,
                            Logger.getStackTracesStringArray(e)
                        ),
                    true,
                    false,
                    FableUtils.AppInfoMode.TERMUX_PACKAGE,
                    true
                )
            }
        }.start()
    }

    private fun ensureDirectoryExists(directory: File): Error? {
        return FileUtils.createDirectoryFile(directory.absolutePath)
    }

    @JvmStatic
    fun loadZipBytes(): ByteArray {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap")
        return getZip()
    }

    @JvmStatic
    external fun getZip(): ByteArray
}
