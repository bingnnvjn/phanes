package com.gph.fable.shared.termux.shell.command.runner.terminal

import android.content.Context
import com.google.common.base.Joiner
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.TerminalSessionClient
import com.gph.fable.core.session.FableSessionFactory
import com.gph.fable.shared.R
import com.gph.fable.shared.errors.Errno
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.shell.ShellUtils
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.ExecutionCommand.ExecutionState
import com.gph.fable.shared.shell.command.environment.IShellEnvironment
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils
import com.gph.fable.shared.shell.command.environment.UnixShellEnvironment
import java.io.File

class FableShellSession private constructor(
    @JvmField val terminalSession: TerminalSession,
    @JvmField val executionCommand: ExecutionCommand,
    @JvmField val fableShellSessionClient: FableShellSessionClient?,
    @JvmField val setStdoutOnExit: Boolean
) {
    fun getTerminalSession(): TerminalSession = terminalSession
    fun getExecutionCommand(): ExecutionCommand = executionCommand

    fun finish() {
        if (terminalSession.isRunning()) return
        val exitCode = terminalSession.getExitStatus()
        if (executionCommand.isStateFailed()) return
        executionCommand.resultData.exitCode = exitCode
        if (setStdoutOnExit) ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false)?.let { executionCommand.resultData.stdout.append(it) }
        if (!executionCommand.setState(ExecutionState.EXECUTED)) return
        processResult(this, null)
    }

    fun killIfExecuting(context: Context, processResult: Boolean) {
        if (executionCommand.hasExecuted()) return
        if (executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                executionCommand.resultData.exitCode = 137
                if (setStdoutOnExit) ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false)?.let { executionCommand.resultData.stdout.append(it) }
                processResult(this, null)
            }
        }
        terminalSession.finishIfRunning()
    }

    companion object {
        const val LOG_TAG = "FableShellSession"

        @JvmStatic
        fun execute(
            currentPackageContext: Context,
            executionCommand: ExecutionCommand,
            terminalSessionClient: TerminalSessionClient,
            fableShellSessionClient: FableShellSessionClient?,
            shellEnvironmentClient: IShellEnvironment,
            additionalEnvironment: HashMap<String, String>?,
            setStdoutOnExit: Boolean,
            sessionFactory: FableSessionFactory
        ): FableShellSession? {
            if (executionCommand.executable?.isEmpty() == true) executionCommand.executable = null
            if (executionCommand.workingDirectory.isNullOrEmpty()) executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath()
            if (executionCommand.workingDirectory!!.isEmpty()) executionCommand.workingDirectory = "/"
            var binPath = shellEnvironmentClient.getDefaultBinPath()
            if (binPath.isEmpty()) binPath = "/system/bin"
            var login = false
            if (executionCommand.executable == null) {
                if (!executionCommand.isFailsafe) {
                    for (binary in UnixShellEnvironment.LOGIN_SHELL_BINARIES) {
                        val file = File(binPath, binary)
                        if (file.canExecute()) { executionCommand.executable = file.absolutePath; break }
                    }
                }
                if (executionCommand.executable == null) executionCommand.executable = "/system/bin/sh" else login = true
            }
            val commandArgs = shellEnvironmentClient.setupShellCommandArguments(executionCommand.executable!!, executionCommand.arguments)
            executionCommand.executable = commandArgs[0]
            val processName = (if (login) "-" else "") + ShellUtils.getExecutableBasename(executionCommand.executable)
            executionCommand.arguments = arrayOf(processName) + commandArgs.drop(1)
            if (executionCommand.commandLabel == null) executionCommand.commandLabel = processName
            val environment = shellEnvironmentClient.setupShellCommandEnvironment(currentPackageContext, executionCommand)
            if (additionalEnvironment != null) environment.putAll(additionalEnvironment)
            val env = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment).sorted().toTypedArray()
            if (!executionCommand.setState(ExecutionState.EXECUTING)) {
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, currentPackageContext.getString(R.string.error_failed_to_execute_fable_shell_session_command, executionCommand.getCommandIdAndLabelLogString()))
                processResult(null, executionCommand)
                return null
            }
            val session = TerminalSession(executionCommand.executable, executionCommand.workingDirectory, executionCommand.arguments, env, terminalSessionClient, sessionFactory)
            if (executionCommand.shellName != null) session.mSessionName = executionCommand.shellName
            return FableShellSession(session, executionCommand, fableShellSessionClient, setStdoutOnExit)
        }

        private fun processResult(session: FableShellSession?, command: ExecutionCommand?) {
            val execution = session?.executionCommand ?: command ?: return
            if (execution.shouldNotProcessResults()) return
            if (session?.fableShellSessionClient != null) session.fableShellSessionClient.onFableShellSessionExited(session)
            else if (!execution.isStateFailed()) execution.setState(ExecutionState.SUCCESS)
        }
    }

    interface FableShellSessionClient {
        fun onFableShellSessionExited(fableShellSession: FableShellSession)
    }
}
