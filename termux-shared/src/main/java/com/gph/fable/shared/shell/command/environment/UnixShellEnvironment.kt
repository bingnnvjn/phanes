package com.gph.fable.shared.shell.command.environment

import android.content.Context
import com.gph.fable.shared.shell.ShellUtils
import com.gph.fable.shared.shell.command.ExecutionCommand

abstract class UnixShellEnvironment : IShellEnvironment {
    companion object {
        const val ENV_COLORTERM = "COLORTERM"
        const val ENV_HOME = "HOME"
        const val ENV_LANG = "LANG"
        const val ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH"
        const val ENV_PATH = "PATH"
        const val ENV_PWD = "PWD"
        const val ENV_TERM = "TERM"
        const val ENV_TMPDIR = "TMPDIR"
        @JvmField val LOGIN_SHELL_BINARIES = arrayOf("login", "bash", "zsh", "fish", "sh")
    }

    abstract fun getEnvironment(currentPackageContext: Context, isFailSafe: Boolean): HashMap<String, String>
    abstract override fun getDefaultWorkingDirectoryPath(): String
    abstract override fun getDefaultBinPath(): String
    override fun setupShellCommandArguments(fileToExecute: String, arguments: Array<String>?): Array<String> =
        ShellUtils.setupShellCommandArguments(fileToExecute, arguments)
    abstract override fun setupShellCommandEnvironment(
        currentPackageContext: Context,
        executionCommand: ExecutionCommand
    ): HashMap<String, String>
}
