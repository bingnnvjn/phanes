package com.gph.fable.shared.file.filesystem

import android.system.Os
import androidx.annotation.NonNull
import com.gph.fable.shared.logger.Logger
import java.io.File

object FileTypes {

    /** Flags to represent regular, directory and symlink file types defined by [FileType] */
    @JvmField
    val FILE_TYPE_NORMAL_FLAGS: Int = FileType.REGULAR.getValue() or FileType.DIRECTORY.getValue() or FileType.SYMLINK.getValue()

    /** Flags to represent any file type defined by [FileType] */
    const val FILE_TYPE_ANY_FLAGS = Int.MAX_VALUE // 1111111111111111111111111111111 (31 1's)

    @JvmStatic
    fun convertFileTypeFlagsToNamesString(fileTypeFlags: Int): String {
        val fileTypeFlagsStringBuilder = StringBuilder()

        val fileTypes = arrayOf(FileType.REGULAR, FileType.DIRECTORY, FileType.SYMLINK, FileType.CHARACTER, FileType.FIFO, FileType.BLOCK, FileType.UNKNOWN)
        for (fileType in fileTypes) {
            if ((fileTypeFlags and fileType.getValue()) > 0)
                fileTypeFlagsStringBuilder.append(fileType.getName()).append(",")
        }

        var fileTypeFlagsString = fileTypeFlagsStringBuilder.toString()

        if (fileTypeFlagsString.endsWith(","))
            fileTypeFlagsString = fileTypeFlagsString.substring(0, fileTypeFlagsString.lastIndexOf(","))

        return fileTypeFlagsString
    }

    /**
     * Checks the type of file that exists at `filePath`.
     */
    @NonNull
    @JvmStatic
    fun getFileType(filePath: String?, followLinks: Boolean): FileType {
        if (filePath == null || filePath.isEmpty()) return FileType.NO_EXIST

        return try {
            val fileAttributes = FileAttributes.get(filePath, followLinks)
            getFileType(fileAttributes)
        } catch (e: Exception) {
            // If not a ENOENT (No such file or directory) exception
            if (e.message != null && !e.message!!.contains("ENOENT"))
                Logger.logError("Failed to get file type for file at path \"" + filePath + "\": " + e.message)
            FileType.NO_EXIST
        }
    }

    @JvmStatic
    fun getFileType(@NonNull fileAttributes: FileAttributes): FileType {
        return when {
            fileAttributes.isRegularFile() -> FileType.REGULAR
            fileAttributes.isDirectory() -> FileType.DIRECTORY
            fileAttributes.isSymbolicLink() -> FileType.SYMLINK
            fileAttributes.isSocket() -> FileType.SOCKET
            fileAttributes.isCharacter() -> FileType.CHARACTER
            fileAttributes.isFifo() -> FileType.FIFO
            fileAttributes.isBlock() -> FileType.BLOCK
            else -> FileType.UNKNOWN
        }
    }
}
