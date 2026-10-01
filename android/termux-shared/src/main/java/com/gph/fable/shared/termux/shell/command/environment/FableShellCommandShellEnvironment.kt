package com.gph.fable.shared.termux.shell.command.environment

import android.content.Context
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.environment.ShellCommandShellEnvironment
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.shell.FableShellManager

class FableShellCommandShellEnvironment : ShellCommandShellEnvironment() {
    override fun getEnvironment(currentPackageContext: Context, executionCommand: ExecutionCommand): HashMap<String, String> {
        val environment = super.getEnvironment(currentPackageContext, executionCommand)
        val preferences = FableAppSharedPreferences.build(currentPackageContext) ?: return environment
        when {
            ExecutionCommand.Runner.APP_SHELL.equalsRunner(executionCommand.runner) -> {
                ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_BOOT, preferences.getAndIncrementAppShellNumberSinceBoot().toString())
                ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_APP_START, FableShellManager.getAndIncrementAppShellNumberSinceAppStart().toString())
            }
            ExecutionCommand.Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner) -> {
                ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__APP_TERMINAL_SESSION_NUMBER_SINCE_BOOT, preferences.getAndIncrementTerminalSessionNumberSinceBoot().toString())
                ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__APP_TERMINAL_SESSION_NUMBER_SINCE_APP_START, FableShellManager.getAndIncrementTerminalSessionNumberSinceAppStart().toString())
            }
        }
        return environment
    }
}
