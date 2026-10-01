package com.gph.fable.shared.shell.command.runner.app

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.google.common.base.Joiner
import com.gph.fable.shared.R
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Errno
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.shell.ShellUtils
import com.gph.fable.shared.shell.StreamGobbler
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.ExecutionCommand.ExecutionState
import com.gph.fable.shared.shell.command.environment.IShellEnvironment
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class AppShell private constructor(
    @JvmField val process: Process,
    @JvmField val executionCommand: ExecutionCommand,
    @JvmField val appShellClient: AppShellClient?
) {
    private fun executeInner(context: Context) {
        executionCommand.mPid = ShellUtils.getPid(process)
        executionCommand.resultData.exitCode = null
        val stdin = DataOutputStream(process.outputStream)
        val stdout = StreamGobbler("${executionCommand.mPid}-stdout", process.inputStream, executionCommand.resultData.stdout, executionCommand.backgroundCustomLogLevel)
        val stderr = StreamGobbler("${executionCommand.mPid}-stderr", process.errorStream, executionCommand.resultData.stderr, executionCommand.backgroundCustomLogLevel)
        stdout.start(); stderr.start()
        if (!DataUtils.isNullOrEmpty(executionCommand.stdin)) {
            try {
                stdin.write((executionCommand.stdin + "\n").toByteArray(StandardCharsets.UTF_8))
                stdin.flush(); stdin.close()
            } catch (e: IOException) {
                if (e.message?.contains("EPIPE") != true && e.message?.contains("Stream closed") != true) {
                    executionCommand.setStateFailed(
                        Errno.ERRNO_FAILED.code,
                        context.getString(R.string.error_exception_received_while_executing_app_shell_command, executionCommand.getCommandIdAndLabelLogString(), e.message),
                        e
                    )
                    executionCommand.resultData.exitCode = 1
                    processAppShellResult(this, null); kill(); return
                }
            }
        }
        val exitCode = process.waitFor()
        try { stdin.close() } catch (_: IOException) {}
        stdout.join(); stderr.join(); process.destroy()
        if (executionCommand.isStateFailed()) return
        executionCommand.resultData.exitCode = exitCode
        if (!executionCommand.setState(ExecutionState.EXECUTED)) return
        processAppShellResult(this, null)
    }

    fun killIfExecuting(context: Context, processResult: Boolean) {
        if (executionCommand.hasExecuted()) return
        if (executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                executionCommand.resultData.exitCode = 137
                processAppShellResult(this, null)
            }
        }
        if (executionCommand.isExecuting()) kill()
    }

    fun kill() {
        val pid = ShellUtils.getPid(process)
        try { Os.kill(pid, OsConstants.SIGKILL) } catch (e: ErrnoException) {
            Logger.logWarn(LOG_TAG, "Failed to send SIGKILL to \"${executionCommand.getCommandIdAndLabelLogString()}\" AppShell with pid $pid: ${e.message}")
        }
    }

    fun getProcess(): Process = process
    fun getExecutionCommand(): ExecutionCommand = executionCommand

    companion object {
        const val LOG_TAG = "AppShell"
        @JvmStatic
        fun execute(
            currentPackageContext: Context,
            executionCommand: ExecutionCommand,
            appShellClient: AppShellClient?,
            shellEnvironmentClient: IShellEnvironment,
            additionalEnvironment: HashMap<String, String>?,
            isSynchronous: Boolean
        ): AppShell? {
            if (executionCommand.executable.isNullOrEmpty()) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(R.string.error_executable_unset, executionCommand.getCommandIdAndLabelLogString())
                )
                processAppShellResult(null, executionCommand)
                return null
            }
            if (executionCommand.workingDirectory.isNullOrEmpty())
                executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath()
            if (executionCommand.workingDirectory!!.isEmpty()) executionCommand.workingDirectory = "/"
            val executableBasename = ShellUtils.getExecutableBasename(executionCommand.executable)
            if (executionCommand.shellName == null) executionCommand.shellName = executableBasename
            if (executionCommand.commandLabel == null) executionCommand.commandLabel = executableBasename
            val commandArray = shellEnvironmentClient.setupShellCommandArguments(executionCommand.executable!!, executionCommand.arguments)
            val environment = shellEnvironmentClient.setupShellCommandEnvironment(currentPackageContext, executionCommand)
            if (additionalEnvironment != null) environment.putAll(additionalEnvironment)
            val environmentArray = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment).sorted().toTypedArray()
            if (!executionCommand.setState(ExecutionState.EXECUTING)) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(R.string.error_failed_to_execute_app_shell_command, executionCommand.getCommandIdAndLabelLogString())
                )
                processAppShellResult(null, executionCommand)
                return null
            }
            Logger.logDebugExtended(LOG_TAG, ExecutionCommand.getExecutionInputLogString(executionCommand, true,
                Logger.shouldEnableLoggingForCustomLogLevel(executionCommand.backgroundCustomLogLevel)))
            Logger.logVerboseExtended(LOG_TAG, "\"${executionCommand.getCommandIdAndLabelLogString()}\" AppShell Environment:\n${Joiner.on("\n").join(environmentArray)}")
            val process = try {
                Runtime.getRuntime().exec(commandArray, environmentArray, File(executionCommand.workingDirectory))
            } catch (e: IOException) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(R.string.error_failed_to_execute_app_shell_command, executionCommand.getCommandIdAndLabelLogString()),
                    e
                )
                processAppShellResult(null, executionCommand)
                return null
            }
            val appShell = AppShell(process, executionCommand, appShellClient)
            val runner = Runnable {
                try { appShell.executeInner(currentPackageContext) } catch (_: IllegalThreadStateException) { } catch (_: InterruptedException) { }
            }
            if (isSynchronous) runner.run() else Thread(runner).start()
            return appShell
        }

        @JvmStatic
        private fun processAppShellResult(appShell: AppShell?, executionCommand: ExecutionCommand?) {
            val command = appShell?.executionCommand ?: executionCommand ?: return
            if (command.shouldNotProcessResults()) return
            if (appShell?.appShellClient != null) appShell.appShellClient.onAppShellExited(appShell)
            else if (!command.isStateFailed()) command.setState(ExecutionState.SUCCESS)
        }
    }

    interface AppShellClient {
        fun onAppShellExited(appShell: AppShell)
    }
}
