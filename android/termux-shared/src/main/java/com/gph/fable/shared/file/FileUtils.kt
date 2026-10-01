package com.gph.fable.shared.file

import android.os.Build
import android.system.Os
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.google.common.io.RecursiveDeleteOption
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Errno
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.errors.FunctionErrno
import com.gph.fable.shared.file.filesystem.FileType
import com.gph.fable.shared.file.filesystem.FileTypes
import com.gph.fable.shared.logger.Logger
import org.apache.commons.io.filefilter.AgeFileFilter
import org.apache.commons.io.filefilter.IOFileFilter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStreamWriter
import java.io.Serializable
import java.nio.charset.Charset
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Arrays
import java.util.Calendar
import java.util.Collections
import java.util.Iterator
import java.util.regex.Pattern

object FileUtils {

    /** Required file permissions for the executable file for app usage. Executable file must have read and execute permissions */
    const val APP_EXECUTABLE_FILE_PERMISSIONS = "r-x" // Default: "r-x"
    /** Required file permissions for the working directory for app usage. Working directory must have read and write permissions.
     * Execute permissions should be attempted to be set, but ignored if they are missing */
    const val APP_WORKING_DIRECTORY_PERMISSIONS = "rwx" // Default: "rwx"

    private const val LOG_TAG = "FileUtils"

    /**
     * Get canonical path.
     */
    @JvmStatic
    fun getCanonicalPath(path: String?, prefixForNonAbsolutePath: String?): String {
        var path = path ?: ""

        val absolutePath: String

        // If path is already an absolute path
        if (path.startsWith("/")) {
            absolutePath = path
        } else {
            if (prefixForNonAbsolutePath != null)
                absolutePath = prefixForNonAbsolutePath + "/" + path
            else
                absolutePath = "/" + path
        }

        return try {
            File(absolutePath).canonicalPath
        } catch (e: Exception) {
            absolutePath
        }
    }

    /**
     * Removes one or more forward slashes "//" with single slash "/"
     * Removes "./"
     * Removes trailing forward slash "/"
     */
    @Nullable
    @JvmStatic
    fun normalizePath(path: String?): String? {
        if (path == null) return null

        var path = path
        path = path.replace(Regex("/+"), "/")
        path = path.replace(Regex("\\./"), "")

        if (path.endsWith("/")) {
            path = path.replace(Regex("/+$"), "")
        }

        return path
    }

    /**
     * Convert special characters `\/:*?"<>|` to underscore.
     */
    @JvmStatic
    fun sanitizeFileName(fileName: String?, sanitizeWhitespaces: Boolean, toLower: Boolean): String? {
        if (fileName == null) return null

        var fileName = fileName
        fileName = if (sanitizeWhitespaces)
            fileName.replace(Regex("[\\\\/:*?\"<>| \t\n]"), "_")
        else
            fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

        return if (toLower)
            fileName.lowercase()
        else
            fileName
    }

    /**
     * Determines whether path is in `dirPath`.
     */
    @JvmStatic
    fun isPathInDirPath(path: String?, dirPath: String?, ensureUnder: Boolean): Boolean {
        return isPathInDirPaths(path, Collections.singletonList(dirPath), ensureUnder)
    }

    /**
     * Determines whether path is in one of the `dirPaths`.
     */
    @JvmStatic
    fun isPathInDirPaths(path: String?, dirPaths: List<String>?, ensureUnder: Boolean): Boolean {
        if (path == null || path.isEmpty() || dirPaths == null || dirPaths.size < 1) return false

        var path = path
        try {
            path = File(path).canonicalPath
        } catch (e: Exception) {
            return false
        }

        var isPathInDirPaths: Boolean

        for (dirPath in dirPaths) {
            val normalizedDirPath = normalizePath(dirPath)

            isPathInDirPaths = if (ensureUnder)
                !path.equals(normalizedDirPath) && path.startsWith(normalizedDirPath + "/")
            else
                path.startsWith(normalizedDirPath + "/")

            if (isPathInDirPaths) return true
        }

        return false
    }

    /**
     * Validate that directory is empty or contains only files in `ignoredSubFilePaths`.
     */
    @JvmStatic
    fun validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(label: String?, filePath: String?,
                                                              ignoredSubFilePaths: List<String>?,
                                                              ignoreNonExistentFile: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "isDirectoryFileEmptyOrOnlyContainsSpecificFiles")

        return try {
            val file = File(filePath)
            var fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            // If file does not exist
            if (fileType == FileType.NO_EXIST) {
                // If checking is to be ignored if file does not exist
                if (ignoreNonExistentFile)
                    return null
                else {
                    label += "directory to check if is empty or only contains specific files"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }
            }

            val subFiles = file.listFiles()
            if (subFiles == null || subFiles.size == 0)
                return null

            // If sub files exists but no file should be ignored
            if (ignoredSubFilePaths == null || ignoredSubFilePaths.size == 0)
                return FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.getError(label, filePath)

            // If a sub file does not exist in ignored file path
            if (nonIgnoredSubFileExists(subFiles, ignoredSubFilePaths)) {
                return FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.getError(label, filePath)
            }

            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_VALIDATE_DIRECTORY_EMPTY_OR_ONLY_CONTAINS_SPECIFIC_FILES_FAILED_WITH_EXCEPTION.getError(e, label + "directory", filePath, e.message)
        }
    }

    /**
     * Check if `subFiles` contains contains a file not in `ignoredSubFilePaths`.
     */
    @JvmStatic
    fun nonIgnoredSubFileExists(subFiles: Array<File>?, @NonNull ignoredSubFilePaths: List<String>): Boolean {
        if (subFiles == null || subFiles.size == 0) return false

        for (subFile in subFiles) {
            val subFilePath = subFile.absolutePath
            // If sub file does not exist in ignored sub file paths
            if (!ignoredSubFilePaths.contains(subFilePath)) {
                var isParentPath = false
                for (ignoredSubFilePath in ignoredSubFilePaths) {
                    if (ignoredSubFilePath.startsWith(subFilePath + "/") && fileExists(ignoredSubFilePath, false)) {
                        isParentPath = true
                        break
                    }
                }
                // If sub file is not a parent of any existing ignored sub file paths
                if (!isParentPath) {
                    return true
                }
            }

            if (getFileType(subFilePath, false) == FileType.DIRECTORY) {
                // If non ignored sub file found, then early exit, otherwise continue looking
                if (nonIgnoredSubFileExists(subFile.listFiles(), ignoredSubFilePaths))
                    return true
            }
        }

        return false
    }

    /**
     * Checks whether a regular file exists at `filePath`.
     */
    @JvmStatic
    fun regularFileExists(filePath: String?, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) == FileType.REGULAR
    }

    /**
     * Checks whether a directory file exists at `filePath`.
     */
    @JvmStatic
    fun directoryFileExists(filePath: String?, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) == FileType.DIRECTORY
    }

    /**
     * Checks whether a symlink file exists at `filePath`.
     */
    @JvmStatic
    fun symlinkFileExists(filePath: String?): Boolean {
        return getFileType(filePath, false) == FileType.SYMLINK
    }

    /**
     * Checks whether a regular or directory file exists at `filePath`.
     */
    @JvmStatic
    fun regularOrDirectoryFileExists(filePath: String?, followLinks: Boolean): Boolean {
        val fileType = getFileType(filePath, followLinks)
        return fileType == FileType.REGULAR || fileType == FileType.DIRECTORY
    }

    /**
     * Checks whether any file exists at `filePath`.
     */
    @JvmStatic
    fun fileExists(filePath: String?, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) != FileType.NO_EXIST
    }

    /**
     * Get the type of file that exists at `filePath`.
     */
    @NonNull
    @JvmStatic
    fun getFileType(filePath: String?, followLinks: Boolean): FileType {
        return FileTypes.getFileType(filePath, followLinks)
    }

    /**
     * Validate the existence and permissions of regular file at path.
     */
    @JvmStatic
    fun validateRegularFileExistenceAndPermissions(label: String?, filePath: String?, parentDirPath: String?,
                                                   permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean,
                                                   ignoreErrorsIfPathIsUnderParentDirPath: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "regular file path", "validateRegularFileExistenceAndPermissions")

        return try {
            val fileType = getFileType(filePath, false)

            // If file exists but not a regular file
            if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
                return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
            }

            var isPathUnderParentDirPath = false
            if (parentDirPath != null) {
                // The path can only be under parent directory path
                isPathUnderParentDirPath = isPathInDirPath(filePath, parentDirPath, true)
            }

            // If setPermissions is enabled and path is a regular file
            if (setPermissions && permissionsToCheck != null && fileType == FileType.REGULAR) {
                // If there is not parentDirPath restriction or path is under parentDirPath
                if (parentDirPath == null || (isPathUnderParentDirPath && getFileType(parentDirPath, false) == FileType.DIRECTORY)) {
                    if (setMissingPermissionsOnly)
                        setMissingFilePermissions(label + "file", filePath, permissionsToCheck)
                    else
                        setFilePermissions(label + "file", filePath, permissionsToCheck)
                }
            }

            // If path is not a regular file
            // Regular files cannot be automatically created so we do not ignore if missing
            if (fileType != FileType.REGULAR) {
                label += "regular file"
                return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
            }

            // If there is not parentDirPath restriction or path is not under parentDirPath or
            // if permission errors must not be ignored for paths under parentDirPath
            if (parentDirPath == null || !isPathUnderParentDirPath || !ignoreErrorsIfPathIsUnderParentDirPath) {
                if (permissionsToCheck != null) {
                    // Check if permissions are missing
                    return checkMissingFilePermissions(label + "regular", filePath, permissionsToCheck, false)
                }
            }

            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_VALIDATE_FILE_EXISTENCE_AND_PERMISSIONS_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        }
    }

    /**
     * Validate the existence and permissions of directory file at path.
     */
    @JvmStatic
    fun validateDirectoryFileExistenceAndPermissions(label: String?, filePath: String?, parentDirPath: String?, createDirectoryIfMissing: Boolean,
                                                     permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean,
                                                     ignoreErrorsIfPathIsInParentDirPath: Boolean, ignoreIfNotExecutable: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "directory file path", "validateDirectoryExistenceAndPermissions")

        return try {
            val file = File(filePath)
            var fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            var isPathInParentDirPath = false
            if (parentDirPath != null) {
                // The path can be equal to parent directory path or under it
                isPathInParentDirPath = isPathInDirPath(filePath, parentDirPath, false)
            }

            if (createDirectoryIfMissing || setPermissions) {
                // If there is not parentDirPath restriction or path is in parentDirPath
                if (parentDirPath == null || (isPathInParentDirPath && getFileType(parentDirPath, false) == FileType.DIRECTORY)) {
                    // If createDirectoryIfMissing is enabled and no file exists at path, then create directory
                    if (createDirectoryIfMissing && fileType == FileType.NO_EXIST) {
                        Logger.logVerbose(LOG_TAG, "Creating " + label + "directory file at path \"" + filePath + "\"")
                        // Create directory and update fileType if successful, otherwise return with error
                        // It "might" be possible that mkdirs returns false even though directory was created
                        val result = file.mkdirs()
                        fileType = getFileType(filePath, false)
                        if (!result && fileType != FileType.DIRECTORY)
                            return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED.getError(label + "directory file", filePath)
                    }

                    // If setPermissions is enabled and path is a directory
                    if (setPermissions && permissionsToCheck != null && fileType == FileType.DIRECTORY) {
                        if (setMissingPermissionsOnly)
                            setMissingFilePermissions(label + "directory", filePath, permissionsToCheck)
                        else
                            setFilePermissions(label + "directory", filePath, permissionsToCheck)
                    }
                }
            }

            // If there is not parentDirPath restriction or path is not in parentDirPath or
            // if existence or permission errors must not be ignored for paths in parentDirPath
            if (parentDirPath == null || !isPathInParentDirPath || !ignoreErrorsIfPathIsInParentDirPath) {
                // If path is not a directory
                // Directories can be automatically created so we can ignore if missing with above check
                if (fileType != FileType.DIRECTORY) {
                    label += "directory"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }

                if (permissionsToCheck != null) {
                    // Check if permissions are missing
                    return checkMissingFilePermissions(label + "directory", filePath, permissionsToCheck, ignoreIfNotExecutable)
                }
            }

            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_VALIDATE_DIRECTORY_EXISTENCE_AND_PERMISSIONS_FAILED_WITH_EXCEPTION.getError(e, label + "directory file", filePath, e.message)
        }
    }

    /**
     * Create a regular file at path.
     */
    @JvmStatic
    fun createRegularFile(filePath: String?): Error? {
        return createRegularFile(null, filePath)
    }

    /**
     * Create a regular file at path.
     */
    @JvmStatic
    fun createRegularFile(label: String?, filePath: String?): Error? {
        return createRegularFile(label, filePath,
            null, false, false)
    }

    /**
     * Create a regular file at path.
     */
    @JvmStatic
    fun createRegularFile(label: String?, filePath: String?,
                          permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "createRegularFile")

        val error: Error?

        val file = File(filePath)
        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
        }

        // If regular file already exists
        if (fileType == FileType.REGULAR) {
            return null
        }

        // Create the file parent directory
        val parentError = createParentDirectoryFile(label + "regular file parent", filePath)
        if (parentError != null)
            return parentError

        return try {
            Logger.logVerbose(LOG_TAG, "Creating " + label + "regular file at path \"" + filePath + "\"")

            if (!file.createNewFile())
                return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED.getError(label + "regular file", filePath)

            validateRegularFileExistenceAndPermissions(label, filePath,
                null,
                permissionsToCheck, setPermissions, setMissingPermissionsOnly,
                false)
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_CREATING_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "regular file", filePath, e.message)
        }
    }

    /**
     * Create parent directory of file at path.
     */
    @JvmStatic
    fun createParentDirectoryFile(label: String?, filePath: String?): Error? {
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "createParentDirectoryFile")

        val file = File(filePath)
        val fileParentPath = file.parent

        return if (fileParentPath != null)
            createDirectoryFile(label, fileParentPath,
                null, false, false)
        else
            null
    }

    /**
     * Create a directory file at path.
     */
    @JvmStatic
    fun createDirectoryFile(filePath: String?): Error? {
        return createDirectoryFile(null, filePath)
    }

    /**
     * Create a directory file at path.
     */
    @JvmStatic
    fun createDirectoryFile(label: String?, filePath: String?): Error? {
        return createDirectoryFile(label, filePath,
            null, false, false)
    }

    /**
     * Create a directory file at path.
     */
    @JvmStatic
    fun createDirectoryFile(label: String?, filePath: String?,
                            permissionsToCheck: String?, setPermissions: Boolean, setMissingPermissionsOnly: Boolean): Error? {
        return validateDirectoryFileExistenceAndPermissions(label, filePath,
            null, true,
            permissionsToCheck, setPermissions, setMissingPermissionsOnly,
            false, false)
    }

    /**
     * Create a symlink file at path.
     */
    @JvmStatic
    fun createSymlinkFile(targetFilePath: String?, destFilePath: String?): Error? {
        return createSymlinkFile(null, targetFilePath, destFilePath,
            true, true, true)
    }

    /**
     * Create a symlink file at path.
     */
    @JvmStatic
    fun createSymlinkFile(label: String?, targetFilePath: String?, destFilePath: String?): Error? {
        return createSymlinkFile(label, targetFilePath, destFilePath,
            true, true, true)
    }

    /**
     * Create a symlink file at path.
     */
    @JvmStatic
    fun createSymlinkFile(label: String?, targetFilePath: String?, destFilePath: String?,
                          allowDangling: Boolean, overwrite: Boolean, overwriteOnlyIfDestIsASymlink: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (targetFilePath == null || targetFilePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "target file path", "createSymlinkFile")
        if (destFilePath == null || destFilePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "destination file path", "createSymlinkFile")

        val error: Error?

        return try {
            val destFile = File(destFilePath)

            var targetFileAbsolutePath = targetFilePath
            // If target path is relative instead of absolute
            if (!targetFilePath.startsWith("/")) {
                val destFileParentPath = destFile.parent
                if (destFileParentPath != null)
                    targetFileAbsolutePath = destFileParentPath + "/" + targetFilePath
            }

            val targetFileType = getFileType(targetFileAbsolutePath, false)
            val destFileType = getFileType(destFilePath, false)

            // If target file does not exist
            if (targetFileType == FileType.NO_EXIST) {
                // If dangling symlink should not be allowed, then return with error
                if (!allowDangling) {
                    label += "symlink target file"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, targetFileAbsolutePath).setLabel(label)
                }
            }

            // If destination exists
            if (destFileType != FileType.NO_EXIST) {
                // If destination must not be overwritten
                if (!overwrite) {
                    return null
                }

                // If overwriteOnlyIfDestIsASymlink is enabled but destination file is not a symlink
                if (overwriteOnlyIfDestIsASymlink && destFileType != FileType.SYMLINK)
                    return FileUtilsErrno.ERRNO_CANNOT_OVERWRITE_A_NON_SYMLINK_FILE_TYPE.getError(label + " file", destFilePath, targetFilePath, destFileType.getName())

                // Delete the destination file
                val deleteError = deleteFile(label + "symlink destination", destFilePath, true)
                if (deleteError != null)
                    return deleteError
            } else {
                // Create the destination file parent directory
                val parentError = createParentDirectoryFile(label + "symlink destination file parent", destFilePath)
                if (parentError != null)
                    return parentError
            }

            // create a symlink at destFilePath to targetFilePath
            Logger.logVerbose(LOG_TAG, "Creating " + label + "symlink file at path \"" + destFilePath + "\" to \"" + targetFilePath + "\"")
            Os.symlink(targetFilePath, destFilePath)
            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_CREATING_SYMLINK_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "symlink file", destFilePath, targetFilePath, e.message)
        }
    }

    /**
     * Copy a regular file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun copyRegularFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileType.REGULAR.getValue(),
            true, true)
    }

    /**
     * Move a regular file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun moveRegularFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileType.REGULAR.getValue(),
            true, true)
    }

    /**
     * Copy a directory file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun copyDirectoryFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileType.DIRECTORY.getValue(),
            true, true)
    }

    /**
     * Move a directory file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun moveDirectoryFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileType.DIRECTORY.getValue(),
            true, true)
    }

    /**
     * Copy a symlink file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun copySymlinkFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileType.SYMLINK.getValue(),
            true, true)
    }

    /**
     * Move a symlink file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun moveSymlinkFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileType.SYMLINK.getValue(),
            true, true)
    }

    /**
     * Copy a file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun copyFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileTypes.FILE_TYPE_NORMAL_FLAGS,
            true, true)
    }

    /**
     * Move a file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun moveFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileTypes.FILE_TYPE_NORMAL_FLAGS,
            true, true)
    }

    /**
     * Copy or move a file from `sourceFilePath` to `destFilePath`.
     */
    @JvmStatic
    fun copyOrMoveFile(label: String?, srcFilePath: String?, destFilePath: String?,
                       moveFile: Boolean, ignoreNonExistentSrcFile: Boolean, allowedFileTypeFlags: Int,
                       overwrite: Boolean, overwriteOnlyIfDestSameFileTypeAsSrc: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (srcFilePath == null || srcFilePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "source file path", "copyOrMoveFile")
        if (destFilePath == null || destFilePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "destination file path", "copyOrMoveFile")

        val mode = if (moveFile) "Moving" else "Copying"
        val modePast = if (moveFile) "moved" else "copied"

        val error: Error?

        return try {
            Logger.logVerbose(LOG_TAG, mode + " " + label + "source file from \"" + srcFilePath + "\" to destination \"" + destFilePath + "\"")

            val srcFile = File(srcFilePath)
            val destFile = File(destFilePath)

            val srcFileType = getFileType(srcFilePath, false)
            val destFileType = getFileType(destFilePath, false)

            val srcFileCanonicalPath = srcFile.canonicalPath
            val destFileCanonicalPath = destFile.canonicalPath

            // If source file does not exist
            if (srcFileType == FileType.NO_EXIST) {
                // If copy or move is to be ignored if source file is not found
                if (ignoreNonExistentSrcFile)
                    return null
                    // Else return with error
                else {
                    label += "source file"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, srcFilePath).setLabel(label)
                }
            }

            // If the file type of the source file does not exist in the allowedFileTypeFlags, then return with error
            if ((allowedFileTypeFlags and srcFileType.getValue()) <= 0)
                return FileUtilsErrno.ERRNO_FILE_NOT_AN_ALLOWED_FILE_TYPE.getError(label + "source file meant to be " + modePast, srcFilePath, FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags))

            // If source and destination file path are the same
            if (srcFileCanonicalPath == destFileCanonicalPath)
                return FileUtilsErrno.ERRNO_COPYING_OR_MOVING_FILE_TO_SAME_PATH.getError(mode + " " + label + "source file", srcFilePath, destFilePath)

            // If destination exists
            if (destFileType != FileType.NO_EXIST) {
                // If destination must not be overwritten
                if (!overwrite) {
                    return null
                }

                // If overwriteOnlyIfDestSameFileTypeAsSrc is enabled but destination file does not match source file type
                if (overwriteOnlyIfDestSameFileTypeAsSrc && destFileType != srcFileType)
                    return FileUtilsErrno.ERRNO_CANNOT_OVERWRITE_A_DIFFERENT_FILE_TYPE.getError(label + "source file", mode.lowercase(), srcFilePath, destFilePath, destFileType.getName(), srcFileType.getName())

                // Delete the destination file
                val deleteError = deleteFile(label + "destination", destFilePath, true)
                if (deleteError != null)
                    return deleteError
            }

            // Copy or move source file to dest
            var copyFile = !moveFile

            // If moveFile is true
            if (moveFile) {
                // We first try to rename source file to destination file to save a copy operation in case both source and destination are on the same filesystem
                Logger.logVerbose(LOG_TAG, "Attempting to rename source to destination.")

                // Uses File.getPath() to get the path of source and destination and not the canonical path
                if (!srcFile.renameTo(destFile)) {
                    // If destination directory is a subdirectory of the source directory
                    // Copying is still allowed by copyDirectory() by excluding destination directory files
                    if (srcFileType == FileType.DIRECTORY && destFileCanonicalPath.startsWith(srcFileCanonicalPath + File.separator))
                        return FileUtilsErrno.ERRNO_CANNOT_MOVE_DIRECTORY_TO_SUB_DIRECTORY_OF_ITSELF.getError(label + "source directory", srcFilePath, destFilePath)

                    // If rename failed, then we copy
                    Logger.logVerbose(LOG_TAG, "Renaming " + label + "source file to destination failed, attempting to copy.")
                    copyFile = true
                }
            }

            // If moveFile is false or renameTo failed while moving
            if (copyFile) {
                Logger.logVerbose(LOG_TAG, "Attempting to copy source to destination.")

                // Create the dest file parent directory
                val parentError = createParentDirectoryFile(label + "dest file parent", destFilePath)
                if (parentError != null)
                    return parentError

                if (srcFileType == FileType.DIRECTORY) {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.copyDirectory(srcFile, destFile, true)
                } else if (srcFileType == FileType.SYMLINK) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        java.nio.file.Files.copy(srcFile.toPath(), destFile.toPath(), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        // read the target for the source file and create a symlink at dest
                        // source file metadata will be lost
                        val symlinkError = createSymlinkFile(label + "dest", Os.readlink(srcFilePath), destFilePath)
                        if (symlinkError != null)
                            return symlinkError
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        java.nio.file.Files.copy(srcFile.toPath(), destFile.toPath(), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                        org.apache.commons.io.FileUtils.copyFile(srcFile, destFile, true)
                    }
                }
            }

            // If source file had to be moved
            if (moveFile) {
                // Delete the source file since copying would have succeeded
                val deleteError = deleteFile(label + "source", srcFilePath, true)
                if (deleteError != null)
                    return deleteError
            }

            Logger.logVerbose(LOG_TAG, mode + " successful.")
            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_COPYING_OR_MOVING_FILE_FAILED_WITH_EXCEPTION.getError(e, mode + " " + label + "file", srcFilePath, destFilePath, e.message)
        }
    }

    /**
     * Delete regular file at path.
     */
    @JvmStatic
    fun deleteRegularFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.REGULAR.getValue())
    }

    /**
     * Delete directory file at path.
     */
    @JvmStatic
    fun deleteDirectoryFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.DIRECTORY.getValue())
    }

    /**
     * Delete symlink file at path.
     */
    @JvmStatic
    fun deleteSymlinkFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.SYMLINK.getValue())
    }

    /**
     * Delete socket file at path.
     */
    @JvmStatic
    fun deleteSocketFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.SOCKET.getValue())
    }

    /**
     * Delete regular, directory or symlink file at path.
     */
    @JvmStatic
    fun deleteFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileTypes.FILE_TYPE_NORMAL_FLAGS)
    }

    /**
     * Delete file at path.
     */
    @JvmStatic
    fun deleteFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean, ignoreWrongFileType: Boolean, allowedFileTypeFlags: Int): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "deleteFile")

        return try {
            val file = File(filePath)
            var fileType = getFileType(filePath, false)

            Logger.logVerbose(LOG_TAG, "Processing delete of " + label + "file at path \"" + filePath + "\" of type \"" + fileType.getName() + "\"")

            // If file does not exist
            if (fileType == FileType.NO_EXIST) {
                // If delete is to be ignored if file does not exist
                if (ignoreNonExistentFile)
                    return null
                    // Else return with error
                else {
                    label += "file meant to be deleted"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }
            }

            // If the file type of the file does not exist in the allowedFileTypeFlags
            if ((allowedFileTypeFlags and fileType.getValue()) <= 0) {
                // If wrong file type is to be ignored
                if (ignoreWrongFileType) {
                    Logger.logVerbose(LOG_TAG, "Ignoring deletion of " + label + "file at path \"" + filePath + "\" of type \"" + fileType.getName() + "\" not matching allowed file types: " + FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags))
                    return null
                }

                // Else return with error
                return FileUtilsErrno.ERRNO_FILE_NOT_AN_ALLOWED_FILE_TYPE.getError(label + "file meant to be deleted", filePath, fileType.getName(), FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags))
            }

            Logger.logVerbose(LOG_TAG, "Deleting " + label + "file at path \"" + filePath + "\"")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //noinspection UnstableApiUsage
                com.google.common.io.MoreFiles.deleteRecursively(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE)
            } else {
                if (fileType == FileType.DIRECTORY) {
                    // deleteDirectory() instead of forceDelete() gets the files list first instead of walking directory tree, so seems safer
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.deleteDirectory(file)
                } else {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.forceDelete(file)
                }
            }

            // If file still exists after deleting it
            fileType = getFileType(filePath, false)
            if (fileType != FileType.NO_EXIST)
                return FileUtilsErrno.ERRNO_FILE_STILL_EXISTS_AFTER_DELETING.getError(label + "file meant to be deleted", filePath)

            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_DELETING_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        }
    }

    /**
     * Clear contents of directory at path without deleting the directory.
     */
    @JvmStatic
    fun clearDirectory(filePath: String?): Error? {
        return clearDirectory(null, filePath)
    }

    /**
     * Clear contents of directory at path without deleting the directory.
     */
    @JvmStatic
    fun clearDirectory(label: String?, filePath: String?): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "clearDirectory")

        return try {
            Logger.logVerbose(LOG_TAG, "Clearing " + label + "directory at path \"" + filePath + "\"")

            val file = File(filePath)
            val fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            // If directory exists, clear its contents
            if (fileType == FileType.DIRECTORY) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    //noinspection UnstableApiUsage
                    com.google.common.io.MoreFiles.deleteDirectoryContents(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE)
                } else {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.cleanDirectory(File(filePath))
                }
            }
            // Else create it
            else {
                val createError = createDirectoryFile(label, filePath)
                if (createError != null)
                    return createError
            }

            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_CLEARING_DIRECTORY_FAILED_WITH_EXCEPTION.getError(e, label + "directory", filePath, e.message)
        }
    }

    /**
     * Delete files under a directory older than x days.
     */
    @JvmStatic
    fun deleteFilesOlderThanXDays(label: String?, filePath: String?, dirFilter: IOFileFilter?, days: Int, ignoreNonExistentFile: Boolean, allowedFileTypeFlags: Int): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "deleteFilesOlderThanXDays")
        if (days < 0) return FunctionErrno.ERRNO_INVALID_PARAMETER.getError(label + "days", "deleteFilesOlderThanXDays", " It must be >= 0.")

        return try {
            Logger.logVerbose(LOG_TAG, "Deleting files under " + label + "directory at path \"" + filePath + "\" older than " + days + " days")

            val file = File(filePath)
            val fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError(label + "directory", filePath).setLabel(label + "directory")
            }

            // If file does not exist
            if (fileType == FileType.NO_EXIST) {
                // If delete is to be ignored if file does not exist
                if (ignoreNonExistentFile)
                    return null
                    // Else return with error
                else {
                    label += "directory under which files had to be deleted"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
                }
            }

            // If directory exists, delete its contents
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DATE, -(days))
            // AgeFileFilter seems to apply to symlink destination timestamp instead of symlink file itself
            val filesToDelete = org.apache.commons.io.FileUtils.iterateFiles(file, AgeFileFilter(calendar.time), dirFilter)
            while (filesToDelete!!.hasNext()) {
                val subFile = filesToDelete.next()
                val deleteError = deleteFile(label + " directory sub", subFile.absolutePath, true, true, allowedFileTypeFlags)
                if (deleteError != null)
                    return deleteError
            }

            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_DELETING_FILES_OLDER_THAN_X_DAYS_FAILED_WITH_EXCEPTION.getError(e, label + "directory", filePath, days, e.message)
        }
    }

    /**
     * Read a text [String] from file at path with a specific [Charset] into `dataStringBuilder`.
     */
    @JvmStatic
    fun readTextFromFile(label: String?, filePath: String?, charset: Charset?, @NonNull dataStringBuilder: StringBuilder, ignoreNonExistentFile: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "readStringFromFile")

        Logger.logVerbose(LOG_TAG, "Reading text from " + label + "file at path \"" + filePath + "\"")

        val error: Error?

        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
        }

        // If file does not exist
        if (fileType == FileType.NO_EXIST) {
            // If reading is to be ignored if file does not exist
            if (ignoreNonExistentFile)
                return null
                // Else return with error
            else {
                label += "file meant to be read"
                return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label)
            }
        }

        var charset = charset ?: Charset.defaultCharset()

        // Check if charset is supported
        val charsetError = isCharsetSupported(charset)
        if (charsetError != null)
            return charsetError

        var fileInputStream: FileInputStream? = null
        var bufferedReader: BufferedReader? = null
        return try {
            // Read text from file
            fileInputStream = FileInputStream(filePath)
            bufferedReader = BufferedReader(InputStreamReader(fileInputStream, charset))

            var receiveString: String?

            var firstLine = true
            while (bufferedReader.readLine().also { receiveString = it } != null) {
                if (!firstLine) dataStringBuilder.append("\n") else firstLine = false
                dataStringBuilder.append(receiveString)
            }

            Logger.logVerbose(LOG_TAG, Logger.getMultiLineLogStringEntry("String", DataUtils.getTruncatedCommandOutput(dataStringBuilder.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD, true, false, true), "-"))
            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_READING_TEXT_FROM_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        } finally {
            closeCloseable(fileInputStream)
            closeCloseable(bufferedReader)
        }
    }

    /** Class that represents result of reading a serializable object. */
    class ReadSerializableObjectResult {
        @JvmField
        val error: Error?
        @JvmField
        val serializableObject: Serializable?

        internal constructor(error: Error?, serializableObject: Serializable?) {
            this.error = error
            this.serializableObject = serializableObject
        }
    }

    /**
     * Read a [Serializable] object from file at path.
     */
    @NonNull
    @JvmStatic
    fun <T : Serializable> readSerializableObjectFromFile(label: String?, filePath: String?, readObjectType: Class<T>, ignoreNonExistentFile: Boolean): ReadSerializableObjectResult {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return ReadSerializableObjectResult(FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "readSerializableObjectFromFile"), null)

        Logger.logVerbose(LOG_TAG, "Reading serializable object from " + label + "file at path \"" + filePath + "\"")

        val serializableObject: T

        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return ReadSerializableObjectResult(FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file"), null)
        }

        // If file does not exist
        if (fileType == FileType.NO_EXIST) {
            // If reading is to be ignored if file does not exist
            if (ignoreNonExistentFile)
                return ReadSerializableObjectResult(null, null)
                // Else return with error
            else {
                label += "file meant to be read"
                return ReadSerializableObjectResult(FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(label, filePath).setLabel(label), null)
            }
        }

        var fileInputStream: FileInputStream? = null
        var objectInputStream: ObjectInputStream? = null
        return try {
            // Read serializable object from file
            fileInputStream = FileInputStream(filePath)
            objectInputStream = ObjectInputStream(fileInputStream)
            val serializableObject = readObjectType.cast(objectInputStream.readObject())

            ReadSerializableObjectResult(null, serializableObject)
        } catch (e: Exception) {
            ReadSerializableObjectResult(FileUtilsErrno.ERRNO_READING_SERIALIZABLE_OBJECT_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message), null)
        } finally {
            closeCloseable(fileInputStream)
            closeCloseable(objectInputStream)
        }
    }

    /**
     * Write text `dataString` with a specific [Charset] to file at path.
     */
    @JvmStatic
    fun writeTextToFile(label: String?, filePath: String?, charset: Charset?, dataString: String?, append: Boolean): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "writeStringToFile")

        Logger.logVerbose(LOG_TAG, Logger.getMultiLineLogStringEntry("Writing text to " + label + "file at path \"" + filePath + "\"", DataUtils.getTruncatedCommandOutput(dataString, Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD, true, false, true), "-"))

        val error: Error?

        val preWriteError = preWriteToFile(label, filePath)
        if (preWriteError != null)
            return preWriteError

        var charset = charset ?: Charset.defaultCharset()

        // Check if charset is supported
        val charsetError = isCharsetSupported(charset)
        if (charsetError != null)
            return charsetError

        var fileOutputStream: FileOutputStream? = null
        var bufferedWriter: BufferedWriter? = null
        return try {
            // Write text to file
            fileOutputStream = FileOutputStream(filePath, append)
            bufferedWriter = BufferedWriter(OutputStreamWriter(fileOutputStream, charset))

            bufferedWriter.write(dataString)
            bufferedWriter.flush()
            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_WRITING_TEXT_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        } finally {
            closeCloseable(fileOutputStream)
            closeCloseable(bufferedWriter)
        }
    }

    /**
     * Write the [Serializable] `serializableObject` to file at path.
     */
    @JvmStatic
    fun <T : Serializable> writeSerializableObjectToFile(label: String?, filePath: String?, serializableObject: T): Error? {
        var label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "writeSerializableObjectToFile")

        Logger.logVerbose(LOG_TAG, "Writing serializable object to " + label + "file at path \"" + filePath + "\"")

        val error: Error?

        val preWriteError = preWriteToFile(label, filePath)
        if (preWriteError != null)
            return preWriteError

        var fileOutputStream: FileOutputStream? = null
        var objectOutputStream: ObjectOutputStream? = null
        return try {
            // Write serializable object to file
            fileOutputStream = FileOutputStream(filePath)
            objectOutputStream = ObjectOutputStream(fileOutputStream)

            objectOutputStream.writeObject(serializableObject)
            objectOutputStream.flush()
            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_WRITING_SERIALIZABLE_OBJECT_TO_FILE_FAILED_WITH_EXCEPTION.getError(e, label + "file", filePath, e.message)
        } finally {
            closeCloseable(fileOutputStream)
            closeCloseable(objectOutputStream)
        }
    }

    private fun preWriteToFile(label: String, filePath: String): Error? {
        val error: Error?

        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError(label + "file", filePath).setLabel(label + "file")
        }

        // Create the file parent directory
        val parentError = createParentDirectoryFile(label + "file parent", filePath)
        if (parentError != null)
            return parentError

        return null
    }

    /**
     * Check if a specific [Charset] is supported.
     */
    @JvmStatic
    fun isCharsetSupported(charset: Charset?): Error? {
        if (charset == null) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("charset", "isCharsetSupported")

        return try {
            if (!Charset.isSupported(charset.name())) {
                return FileUtilsErrno.ERRNO_UNSUPPORTED_CHARSET.getError(charset.name())
            }
            null
        } catch (e: Exception) {
            FileUtilsErrno.ERRNO_CHECKING_IF_CHARSET_SUPPORTED_FAILED.getError(e, charset.name(), e.message)
        }
    }

    /**
     * Close a [Closeable] object if not `null` and ignore any exceptions raised.
     */
    @JvmStatic
    fun closeCloseable(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    /**
     * Set permissions for file at path.
     */
    @JvmStatic
    fun setFilePermissions(filePath: String?, permissionsToSet: String?) {
        setFilePermissions(null, filePath, permissionsToSet)
    }

    /**
     * Set permissions for file at path.
     */
    @JvmStatic
    fun setFilePermissions(label: String?, filePath: String?, permissionsToSet: String?) {
        val label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return

        if (!isValidPermissionString(permissionsToSet)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToSet passed to setFilePermissions: \"" + permissionsToSet + "\"")
            return
        }

        val file = File(filePath)

        if (permissionsToSet!!.contains("r")) {
            if (!file.canRead()) {
                Logger.logVerbose(LOG_TAG, "Setting read permissions for " + label + "file at path \"" + filePath + "\"")
                file.setReadable(true)
            }
        } else {
            if (file.canRead()) {
                Logger.logVerbose(LOG_TAG, "Removing read permissions for " + label + "file at path \"" + filePath + "\"")
                file.setReadable(false)
            }
        }

        if (permissionsToSet.contains("w")) {
            if (!file.canWrite()) {
                Logger.logVerbose(LOG_TAG, "Setting write permissions for " + label + "file at path \"" + filePath + "\"")
                file.setWritable(true)
            }
        } else {
            if (file.canWrite()) {
                Logger.logVerbose(LOG_TAG, "Removing write permissions for " + label + "file at path \"" + filePath + "\"")
                file.setWritable(false)
            }
        }

        if (permissionsToSet.contains("x")) {
            if (!file.canExecute()) {
                Logger.logVerbose(LOG_TAG, "Setting execute permissions for " + label + "file at path \"" + filePath + "\"")
                file.setExecutable(true)
            }
        } else {
            if (file.canExecute()) {
                Logger.logVerbose(LOG_TAG, "Removing execute permissions for " + label + "file at path \"" + filePath + "\"")
                file.setExecutable(false)
            }
        }
    }

    /**
     * Set missing permissions for file at path.
     */
    @JvmStatic
    fun setMissingFilePermissions(filePath: String?, permissionsToSet: String?) {
        setMissingFilePermissions(null, filePath, permissionsToSet)
    }

    /**
     * Set missing permissions for file at path.
     */
    @JvmStatic
    fun setMissingFilePermissions(label: String?, filePath: String?, permissionsToSet: String?) {
        val label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return

        if (!isValidPermissionString(permissionsToSet)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToSet passed to setMissingFilePermissions: \"" + permissionsToSet + "\"")
            return
        }

        val file = File(filePath)

        if (permissionsToSet!!.contains("r") && !file.canRead()) {
            Logger.logVerbose(LOG_TAG, "Setting missing read permissions for " + label + "file at path \"" + filePath + "\"")
            file.setReadable(true)
        }

        if (permissionsToSet.contains("w") && !file.canWrite()) {
            Logger.logVerbose(LOG_TAG, "Setting missing write permissions for " + label + "file at path \"" + filePath + "\"")
            file.setWritable(true)
        }

        if (permissionsToSet.contains("x") && !file.canExecute()) {
            Logger.logVerbose(LOG_TAG, "Setting missing execute permissions for " + label + "file at path \"" + filePath + "\"")
            file.setExecutable(true)
        }
    }

    /**
     * Checking missing permissions for file at path.
     */
    @JvmStatic
    fun checkMissingFilePermissions(filePath: String?, permissionsToCheck: String?, ignoreIfNotExecutable: Boolean): Error? {
        return checkMissingFilePermissions(null, filePath, permissionsToCheck, ignoreIfNotExecutable)
    }

    /**
     * Checking missing permissions for file at path.
     */
    @JvmStatic
    fun checkMissingFilePermissions(label: String?, filePath: String?, permissionsToCheck: String?, ignoreIfNotExecutable: Boolean): Error? {
        val label = if (label == null || label.isEmpty()) "" else label + " "
        if (filePath == null || filePath.isEmpty()) return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(label + "file path", "checkMissingFilePermissions")

        if (!isValidPermissionString(permissionsToCheck)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToCheck passed to checkMissingFilePermissions: \"" + permissionsToCheck + "\"")
            return FileUtilsErrno.ERRNO_INVALID_FILE_PERMISSIONS_STRING_TO_CHECK.getError()
        }

        val file = File(filePath)

        // If file is not readable
        if (permissionsToCheck!!.contains("r") && !file.canRead()) {
            return FileUtilsErrno.ERRNO_FILE_NOT_READABLE.getError(label + "file", filePath).setLabel(label + "file")
        }

        // If file is not writable
        if (permissionsToCheck.contains("w") && !file.canWrite()) {
            return FileUtilsErrno.ERRNO_FILE_NOT_WRITABLE.getError(label + "file", filePath).setLabel(label + "file")
        }
        // If file is not executable
        // This canExecute() will give "avc: granted { execute }" warnings for target sdk 29
        else if (permissionsToCheck.contains("x") && !file.canExecute() && !ignoreIfNotExecutable) {
            return FileUtilsErrno.ERRNO_FILE_NOT_EXECUTABLE.getError(label + "file", filePath).setLabel(label + "file")
        }

        return null
    }

    /**
     * Checks whether string exactly matches the 3 character permission string that
     * contains the "r", "w", "x" or "-" in-order.
     */
    @JvmStatic
    fun isValidPermissionString(string: String?): Boolean {
        if (string == null || string.isEmpty()) return false
        return Pattern.compile("^([r-])[w-][x-]$", 0).matcher(string).matches()
    }

    /**
     * Get a [Error] that contains a shorter version of [Errno] message.
     */
    @JvmStatic
    fun getShortFileUtilsError(error: Error): Error {
        val type = error.getType()
        if (FileUtilsErrno.TYPE != type) return error

        val shortErrno = FileUtilsErrno.ERRNO_SHORT_MAPPING[Errno.valueOf(type, error.getCode())]
        if (shortErrno == null) return error

        val throwables = error.getThrowablesList()
        return if (throwables.isEmpty())
            shortErrno.getError(DataUtils.getDefaultIfNull(error.getLabel(), "file"))
        else
            shortErrno.getError(throwables, error.getLabel(), "file")
    }

    /**
     * Get file dirname for file at `filePath`.
     */
    @JvmStatic
    fun getFileDirname(filePath: String?): String? {
        if (DataUtils.isNullOrEmpty(filePath)) return null
        val lastSlash = filePath!!.lastIndexOf('/')
        return if (lastSlash == -1) null else filePath.substring(0, lastSlash)
    }

    /**
     * Get file basename for file at `filePath`.
     */
    @JvmStatic
    fun getFileBasename(filePath: String?): String? {
        if (DataUtils.isNullOrEmpty(filePath)) return null
        val lastSlash = filePath!!.lastIndexOf('/')
        return if (lastSlash == -1) filePath else filePath.substring(lastSlash + 1)
    }

    /**
     * Get file basename for file at `filePath` without extension.
     */
    @JvmStatic
    fun getFileBasenameWithoutExtension(filePath: String?): String? {
        val fileBasename = getFileBasename(filePath)
        if (DataUtils.isNullOrEmpty(fileBasename)) return null
        val lastDot = fileBasename!!.lastIndexOf('.')
        return if (lastDot == -1) fileBasename else fileBasename.substring(0, lastDot)
    }
}
