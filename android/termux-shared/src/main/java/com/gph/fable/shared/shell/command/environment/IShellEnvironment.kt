package com.gph.fable.shared.shell.command.environment

import android.content.Context
import com.gph.fable.shared.shell.command.ExecutionCommand

interface IShellEnvironment {
    fun getDefaultWorkingDirectoryPath(): String
    fun getDefaultBinPath(): String
    fun setupShellCommandArguments(fileToExecute: String, arguments: Array<String>?): Array<String>
    fun setupShellCommandEnvironment(currentPackageContext: Context, executionCommand: ExecutionCommand): HashMap<String, String>
}
