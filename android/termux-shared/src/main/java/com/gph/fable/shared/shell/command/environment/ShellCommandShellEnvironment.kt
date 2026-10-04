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

    open fun getEnvironment(currentPackageContext: Context, executionCommand: ExecutionCommand): HashMap<String, String> =
        getEnvironmentForPackageName(currentPackageContext.packageName, executionCommand)

    /**
     * JVM 可测入口：行为与 [getEnvironment] 相同，只把 `packageName` 单独传入，
     * 使环境组装逻辑不依赖 `android.content.Context`。公开是刻意的——本模块的
     * 单测是 Java（AGP 内置 Kotlin 下 `internal` 对 Java 测试不可见）。
     */
    open fun getEnvironmentForPackageName(packageName: String, executionCommand: ExecutionCommand): HashMap<String, String> {
        val environment = HashMap<String, String>()
        val runner = ExecutionCommand.Runner.runnerOf(executionCommand.runner) ?: return environment
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__RUNNER_NAME, runner.getName())
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__PACKAGE_NAME, packageName)
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__SHELL_ID, executionCommand.id.toString())
        ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__SHELL_NAME, executionCommand.shellName)
        return environment
    }
}
