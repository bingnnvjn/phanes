package com.gph.fable.shared.shell.command.environment

import android.content.Context
import com.gph.fable.shared.shell.command.ExecutionCommand

open class ShellCommandShellEnvironment {
    companion object {
        const val SHELL_CMD_ENV_PREFIX = "SHELL_CMD__"
        const val ENV_SHELL_CMD__RUNNER_NAME = "${SHELL_CMD_ENV_PREFIX}RUNNER_NAME"
        const val ENV_SHELL_CMD__PACKAGE_NAME = "${SHELL_CMD_ENV_PREFIX}PACKAGE_NAME"
        const val ENV_SHELL_CMD__SHELL_ID = "${SHELL_CMD_ENV_PREFIX}SHELL_ID"
        const val ENV_SHELL_CMD__SHELL_NAME = "${SHELL_CMD_ENV_PREFIX}SHELL_NAME"
        const val ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_BOOT = "${SHELL_CMD_ENV_PREFIX}APP_SHELL_NUMBER_SINCE_BOOT"
        const val ENV_SHELL_CMD__APP_TERMINAL_SESSION_NUMBER_SINCE_BOOT = "${SHELL_CMD_ENV_PREFIX}APP_TERMINAL_SESSION_NUMBER_SINCE_BOOT"
        const val ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_APP_START = "${SHELL_CMD_ENV_PREFIX}APP_SHELL_NUMBER_SINCE_APP_START"
        const val ENV_SHELL_CMD__APP_TERMINAL_SESSION_NUMBER_SINCE_APP_START = "${SHELL_CMD_ENV_PREFIX}APP_TERMINAL_SESSION_NUMBER_SINCE_APP_START"
    }

    open fun getEnvironment(currentPackageContext: Context, executionCommand: ExecutionCommand): HashMap<String, String> {
        val environment = HashMap<String, String>()
        val runner = ExecutionCommand.Runner.runnerOf(executionCommand.runner) ?: return environment
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__RUNNER_NAME, runner.name)
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__PACKAGE_NAME, currentPackageContext.packageName)
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__SHELL_ID, executionCommand.id?.toString())
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__SHELL_NAME, executionCommand.shellName)
        return environment
    }
}
