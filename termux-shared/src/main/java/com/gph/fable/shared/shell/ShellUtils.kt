package com.gph.fable.shared.shell

import com.gph.fable.core.TerminalSession
import com.gph.fable.core.adapter.CoreAdapter
import com.gph.fable.shared.file.FileUtils
import java.lang.reflect.Field

object ShellUtils {
    @JvmStatic
    fun getPid(process: Process): Int {
        return try {
            val field: Field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            try {
                field.getInt(process)
            } finally {
                field.isAccessible = false
            }
        } catch (_: Throwable) {
            -1
        }
    }

    @JvmStatic
    fun setupShellCommandArguments(executable: String, arguments: Array<String>?): Array<String> {
        return arrayOf(executable) + (arguments ?: emptyArray())
    }

    @JvmStatic
    fun getExecutableBasename(executable: String?): String? = FileUtils.getFileBasename(executable)

    @JvmStatic
    fun getTerminalSessionTranscriptText(
        terminalSession: TerminalSession?,
        linesJoined: Boolean,
        trim: Boolean
    ): String? {
        if (terminalSession == null) return null
        val adapter: CoreAdapter = terminalSession.coreAdapter ?: return null
        return adapter.getTranscriptText(linesJoined, trim)
    }
}
