package com.gph.fable.shared.termux.shell

import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.file.filesystem.FileTypes
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.settings.properties.FableAppSharedProperties
import org.apache.commons.io.filefilter.TrueFileFilter
import java.io.File
import java.io.FileInputStream

object FableShellUtils {
    private const val LOG_TAG = "FableShellUtils"

    @JvmStatic
    fun setupShellCommandArguments(executable: String, arguments: Array<String>?): Array<String> {
        var interpreter: String? = null
        try {
            FileInputStream(File(executable)).use { input ->
                val buffer = ByteArray(256)
                val bytesRead = input.read(buffer)
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7f.toByte() && buffer[1] == 'E'.code.toByte() &&
                        buffer[2] == 'L'.code.toByte() && buffer[3] == 'F'.code.toByte()) {
                        // ELF: execute directly.
                    } else if (buffer[0] == '#'.code.toByte() && buffer[1] == '!'.code.toByte()) {
                        val shebang = buffer.copyOfRange(2, bytesRead)
                            .toString(Charsets.UTF_8)
                            .trim()
                            .substringBefore(' ')
                            .substringBefore('\n')
                        if (shebang.startsWith("/usr") || shebang.startsWith("/bin")) {
                            interpreter = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/${shebang.substringAfterLast('/')}"
                        }
                    } else {
                        interpreter = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/sh"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return buildList {
            interpreter?.let(::add)
            add(executable)
            arguments?.let(::addAll)
        }.toTypedArray()
    }

    @JvmStatic
    fun clearFableTMPDIR(onlyIfExists: Boolean) {
        if (onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false)) return
        var days = FableAppSharedProperties.getProperties()?.getDeleteTMPDIRFilesOlderThanXDaysOnExit() ?: 0
        if (days > 0) days = 0
        when {
            days < 0 -> Logger.logInfo(LOG_TAG, "Not clearing termux \$TMPDIR")
            days == 0 -> {
                val error: Error? = FileUtils.clearDirectory(
                    "\$TMPDIR",
                    FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null)
                )
                if (error != null) Logger.logErrorExtended(LOG_TAG, "Failed to clear termux \$TMPDIR\n$error")
            }
            else -> {
                val error: Error? = FileUtils.deleteFilesOlderThanXDays(
                    "\$TMPDIR",
                    FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null),
                    TrueFileFilter.INSTANCE,
                    days,
                    true,
                    FileTypes.FILE_TYPE_ANY_FLAGS
                )
                if (error != null) Logger.logErrorExtended(LOG_TAG, "Failed to delete files from termux \$TMPDIR older than $days days\n$error")
            }
        }
    }
}
