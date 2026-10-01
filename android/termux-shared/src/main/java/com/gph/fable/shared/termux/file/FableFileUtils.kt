package com.gph.fable.shared.termux.file

import android.content.Context
import android.os.Environment
import androidx.annotation.NonNull
import com.gph.fable.shared.android.AndroidUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.file.FileUtilsErrno
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.runner.app.AppShell
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import com.gph.fable.shared.termux.shell.command.environment.FableShellEnvironment
import java.io.File
import java.util.ArrayList
import java.util.regex.Pattern

object FableFileUtils {

    private const val LOG_TAG = "FableFileUtils"

    /**
     * Replace "$PREFIX/" or "~/" prefix with Fable absolute paths.
     */
    @JvmStatic
    fun getExpandedFablePaths(paths: List<String>?): List<String?>? {
        if (paths == null) return null
        val expandedPaths = ArrayList<String?>()

        for (i in paths.indices) {
            expandedPaths.add(getExpandedFablePath(paths[i]))
        }

        return expandedPaths
    }

    /**
     * Replace "$PREFIX/" or "~/" prefix with Fable absolute paths.
     */
    @JvmStatic
    fun getExpandedFablePath(path: String?): String? {
        var path = path
        if (path != null && !path.isEmpty()) {
            path = path.replace(Regex("^\\\$PREFIX$"), TermuxConstants.TERMUX_PREFIX_DIR_PATH)
            path = path.replace(Regex("^\\\$PREFIX/"), TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/")
            path = path.replace(Regex("^~/$"), TermuxConstants.TERMUX_HOME_DIR_PATH)
            path = path.replace(Regex("^~/"), TermuxConstants.TERMUX_HOME_DIR_PATH + "/")
        }

        return path
    }

    /**
     * Replace Fable absolute paths with "$PREFIX/" or "~/" prefix.
     */
    @JvmStatic
    fun getUnExpandedFablePaths(paths: List<String>?): List<String?>? {
        if (paths == null) return null
        val unExpandedPaths = ArrayList<String?>()

        for (i in paths.indices) {
            unExpandedPaths.add(getUnExpandedFablePath(paths[i]))
        }

        return unExpandedPaths
    }

    /**
     * Replace Fable absolute paths with "$PREFIX/" or "~/" prefix.
     */
    @JvmStatic
    fun getUnExpandedFablePath(path: String?): String? {
        var path = path
        if (path != null && !path.isEmpty()) {
            path = path.replace(Regex("^" + Pattern.quote(TermuxConstants.TERMUX_PREFIX_DIR_PATH) + "/"), "\\\$PREFIX/")
            path = path.replace(Regex("^" + Pattern.quote(TermuxConstants.TERMUX_HOME_DIR_PATH) + "/"), "~/")
        }

        return path
    }

    /**
     * Get canonical path.
     */
    @JvmStatic
    fun getCanonicalPath(path: String?, prefixForNonAbsolutePath: String?, expandPath: Boolean): String {
        var path = path ?: ""

        if (expandPath)
            path = getExpandedFablePath(path) ?: path

        return FileUtils.getCanonicalPath(path, prefixForNonAbsolutePath)
    }

    /**
     * Check if `path` is under the allowed Fable working directory paths.
     */
    @JvmStatic
    fun getMatchedAllowedFableWorkingDirectoryParentPathForPath(path: String?): String {
        if (path == null || path.isEmpty()) return TermuxConstants.TERMUX_FILES_DIR_PATH

        return when {
            path.startsWith(TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH + "/") ->
                TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH
            path.startsWith(Environment.getExternalStorageDirectory().absolutePath + "/") ->
                Environment.getExternalStorageDirectory().absolutePath
            path.startsWith("/sdcard/") -> "/sdcard"
            else -> TermuxConstants.TERMUX_FILES_DIR_PATH
        }
    }

    /**
     * Validate the existence and permissions of directory file at path as a working directory.
     */
    @JvmStatic
    fun validateDirectoryFileExistenceAndPermissions(label: String?, filePath: String?, createDirectoryIfMissing: Boolean,
                                                     setPermissions: Boolean, setMissingPermissionsOnly: Boolean,
                                                     ignoreErrorsIfPathIsInParentDirPath: Boolean, ignoreIfNotExecutable: Boolean): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(label, filePath,
            FableFileUtils.getMatchedAllowedFableWorkingDirectoryParentPathForPath(filePath), createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, setPermissions, setMissingPermissionsOnly,
            ignoreErrorsIfPathIsInParentDirPath, ignoreIfNotExecutable)
    }

    /**
     * Validate if [TermuxConstants.TERMUX_FILES_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     */
    @JvmStatic
    fun isFableFilesDirectoryAccessible(@NonNull context: Context, createDirectoryIfMissing: Boolean, setMissingPermissions: Boolean): Error? {
        if (createDirectoryIfMissing)
            context.filesDir

        if (!FileUtils.directoryFileExists(TermuxConstants.TERMUX_FILES_DIR_PATH, true))
            return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError("termux files directory", TermuxConstants.TERMUX_FILES_DIR_PATH)

        if (setMissingPermissions)
            FileUtils.setMissingFilePermissions("termux files directory", TermuxConstants.TERMUX_FILES_DIR_PATH,
                FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS)

        return FileUtils.checkMissingFilePermissions("termux files directory", TermuxConstants.TERMUX_FILES_DIR_PATH,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, false)
    }

    /**
     * Validate if [TermuxConstants.TERMUX_PREFIX_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     */
    @JvmStatic
    fun isFablePrefixDirectoryAccessible(createDirectoryIfMissing: Boolean, setMissingPermissions: Boolean): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions("termux prefix directory", TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            null, createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, setMissingPermissions, true,
            false, false)
    }

    /**
     * Validate if [TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     */
    @JvmStatic
    fun isFablePrefixStagingDirectoryAccessible(createDirectoryIfMissing: Boolean, setMissingPermissions: Boolean): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions("termux prefix staging directory", TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            null, createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, setMissingPermissions, true,
            false, false)
    }

    /**
     * Validate if [TermuxConstants.TERMUX_APP.APPS_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     */
    @JvmStatic
    fun isAppsFableAppDirectoryAccessible(createDirectoryIfMissing: Boolean, setMissingPermissions: Boolean): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions("apps/termux-app directory", TermuxConstants.TERMUX_APP.APPS_DIR_PATH,
            null, createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, setMissingPermissions, true,
            false, false)
    }

    /**
     * If [TermuxConstants.TERMUX_PREFIX_DIR_PATH] doesn't exist, is empty or only contains
     * files in [TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY].
     */
    @JvmStatic
    fun isFablePrefixDirectoryEmpty(): Boolean {
        val error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("termux prefix",
            TERMUX_PREFIX_DIR_PATH, TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY, true)
        if (error == null)
            return true

        if (!FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.equalsErrorTypeAndCode(error))
            Logger.logErrorExtended(LOG_TAG, "Failed to check if termux prefix directory is empty:\n" + error.getErrorLogString())
        return false
    }

    /**
     * Get a markdown [String] for stat output for various Fable app files paths.
     */
    @JvmStatic
    fun getFableFilesStatMarkdownString(@NonNull context: Context): String? {
        val fablePackageContext = FableUtils.getFablePackageContext(context)
        if (fablePackageContext == null) return null

        // Also ensures that termux files directory is created if it does not already exist
        val filesDir = fablePackageContext.filesDir.absolutePath

        // Build script
        val statScript = StringBuilder()
        statScript
            .append("echo 'ls info:'\n")
            .append("/system/bin/ls -lhdZ")
            .append(" '/data/data'")
            .append(" '/data/user/0'")
            .append(" '" + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "'")
            .append(" '/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "'")
            .append(" '" + TermuxConstants.TERMUX_FILES_DIR_PATH + "'")
            .append(" '" + filesDir + "'")
            .append(" '/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files'")
            .append(" '/data/user/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files'")
            .append(" '" + TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_HOME_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login'")
            .append(" 2>&1")
            .append("\necho; echo 'mount info:'\n")
            .append("/system/bin/grep -E '( /data )|( /data/data )|( /data/user/[0-9]+ )' /proc/self/mountinfo 2>&1 | /system/bin/grep -v '/data_mirror' 2>&1")

        // Run script
        val executionCommand = ExecutionCommand(-1, "/system/bin/sh", null,
            statScript.toString() + "\n", "/", ExecutionCommand.Runner.APP_SHELL.getName(), true)
        executionCommand.commandLabel = TermuxConstants.TERMUX_APP_NAME + " Files Stat Command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val appShell = AppShell.execute(context, executionCommand, null, FableShellEnvironment(), null, true)
        if (appShell == null || !executionCommand.isSuccessful()) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        // Build script output
        val statOutput = StringBuilder()
        statOutput.append("$ ").append(statScript.toString())
        statOutput.append("\n\n").append(executionCommand.resultData.stdout.toString())

        val stderrSet = !executionCommand.resultData.stderr.toString().isEmpty()
        if (executionCommand.resultData.exitCode != 0 || stderrSet) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            if (stderrSet)
                statOutput.append("\n").append(executionCommand.resultData.stderr.toString())
            statOutput.append("\n").append("exit code: ").append(executionCommand.resultData.exitCode.toString())
        }

        // Build markdown output
        val markdownString = StringBuilder()
        markdownString.append("## ").append(TermuxConstants.TERMUX_APP_NAME).append(" Files Info\n\n")
        AndroidUtils.appendPropertyToMarkdown(markdownString, "TERMUX_REQUIRED_FILES_DIR_PATH (\$PREFIX)", TermuxConstants.TERMUX_FILES_DIR_PATH)
        AndroidUtils.appendPropertyToMarkdown(markdownString, "ANDROID_ASSIGNED_FILES_DIR_PATH", filesDir)
        markdownString.append("\n\n").append(MarkdownUtils.getMarkdownCodeForString(statOutput.toString(), true))
        markdownString.append("\n##\n")

        return markdownString.toString()
    }
}
