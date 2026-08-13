package com.gph.fable.shared.shell.command

import android.content.Intent
import android.net.Uri
import com.gph.fable.core.TerminalSession
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.data.IntentUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.shell.command.result.ResultConfig
import com.gph.fable.shared.shell.command.result.ResultData

class ExecutionCommand {
    enum class ExecutionState(private val stateName: String, private val stateValue: Int) {
        PRE_EXECUTION("Pre-Execution", 0),
        EXECUTING("Executing", 1),
        EXECUTED("Executed", 2),
        SUCCESS("Success", 3),
        FAILED("Failed", 4);

        fun getName(): String = stateName
        fun getValue(): Int = stateValue
    }

    enum class Runner(private val runnerName: String) {
        TERMINAL_SESSION("terminal-session"),
        APP_SHELL("app-shell");

        fun getName(): String = runnerName
        fun equalsRunner(runner: String?): Boolean = runner == runnerName

        companion object {
            @JvmStatic
            fun runnerOf(name: String?): Runner? = values().firstOrNull { it.runnerName == name }

            @JvmStatic
            fun runnerOf(name: String?, def: Runner): Runner = runnerOf(name) ?: def
        }
    }

    enum class ShellCreateMode(private val createMode: String) {
        ALWAYS("always"),
        NO_SHELL_WITH_NAME("no-shell-with-name");

        fun getMode(): String = createMode
        fun equalsMode(mode: String?): Boolean = mode == createMode

        companion object {
            @JvmStatic
            fun modeOf(mode: String?): ShellCreateMode? = values().firstOrNull { it.createMode == mode }
        }
    }

    @JvmField var id: Int? = null
    @JvmField var mPid: Int = -1
    private var currentState: ExecutionState = ExecutionState.PRE_EXECUTION
    private var previousState: ExecutionState = ExecutionState.PRE_EXECUTION

    @JvmField var executable: String? = null
    @JvmField var executableUri: Uri? = null
    @JvmField var arguments: Array<String>? = null
    @JvmField var stdin: String? = null
    @JvmField var workingDirectory: String? = null
    @JvmField var runner: String? = null
    @JvmField var isFailsafe: Boolean = false
    @JvmField var backgroundCustomLogLevel: Int? = null
    @JvmField var sessionAction: String? = null
    @JvmField var shellName: String? = null
    @JvmField var shellCreateMode: String? = null
    @JvmField var setShellCommandShellEnvironment: Boolean = false
    @JvmField var commandLabel: String? = null
    @JvmField var commandDescription: String? = null
    @JvmField var commandHelp: String? = null
    @JvmField var pluginAPIHelp: String? = null
    @JvmField var commandIntent: Intent? = null
    @JvmField var isPluginExecutionCommand: Boolean = false
    @JvmField val resultConfig = ResultConfig()
    @JvmField val resultData = ResultData()
    @JvmField var processingResultsAlreadyCalled: Boolean = false

    constructor()
    constructor(id: Int?) {
        this.id = id
    }

    constructor(
        id: Int?,
        executable: String?,
        arguments: Array<String>?,
        stdin: String?,
        workingDirectory: String?,
        runner: String?,
        isFailsafe: Boolean
    ) {
        this.id = id
        this.executable = executable
        this.arguments = arguments
        this.stdin = stdin
        this.workingDirectory = workingDirectory
        this.runner = runner
        this.isFailsafe = isFailsafe
    }

    @Synchronized
    fun isPluginExecutionCommandWithPendingResult(): Boolean =
        isPluginExecutionCommand && resultConfig.isCommandWithPendingResult()

    @Synchronized
    fun setState(newState: ExecutionState): Boolean {
        if (newState.getValue() < currentState.getValue() || currentState == ExecutionState.SUCCESS) {
            Logger.logError(
                LOG_TAG,
                "Invalid ${getCommandIdAndLabelLogString()} state transition from \"${currentState.getName()}\" to \"${newState.getName()}\""
            )
            return false
        }
        if (currentState != ExecutionState.FAILED) previousState = currentState
        currentState = newState
        return true
    }

    @Synchronized fun hasExecuted(): Boolean = currentState.getValue() >= ExecutionState.EXECUTED.getValue()
    @Synchronized fun isExecuting(): Boolean = currentState == ExecutionState.EXECUTING
    @Synchronized fun isSuccessful(): Boolean = currentState == ExecutionState.SUCCESS

    @Synchronized
    fun setStateFailed(error: Error): Boolean =
        setStateFailed(error.getType(), error.getCode(), error.getMessage(), null)

    @Synchronized
    fun setStateFailed(error: Error, throwable: Throwable?): Boolean =
        setStateFailed(error.getType(), error.getCode(), error.getMessage(), listOfNotNull(throwable))

    @Synchronized
    fun setStateFailed(error: Error, throwablesList: List<Throwable>?): Boolean =
        setStateFailed(error.getType(), error.getCode(), error.getMessage(), throwablesList)

    @Synchronized
    fun setStateFailed(code: Int, message: String?): Boolean =
        setStateFailed(null, code, message, null)

    @Synchronized
    fun setStateFailed(code: Int, message: String?, throwable: Throwable?): Boolean =
        setStateFailed(null, code, message, listOfNotNull(throwable))

    @Synchronized
    fun setStateFailed(code: Int, message: String?, throwablesList: List<Throwable>?): Boolean =
        setStateFailed(null, code, message, throwablesList)

    @Synchronized
    fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: List<Throwable>?): Boolean {
        if (!resultData.setStateFailed(type, code, message, throwablesList)) {
            Logger.logWarn(
                LOG_TAG,
                "setStateFailed for ${getCommandIdAndLabelLogString()} resultData encountered an error."
            )
        }
        return setState(ExecutionState.FAILED)
    }

    @Synchronized
    fun shouldNotProcessResults(): Boolean {
        if (processingResultsAlreadyCalled) return true
        processingResultsAlreadyCalled = true
        return false
    }

    @Synchronized
    fun isStateFailed(): Boolean {
        if (currentState != ExecutionState.FAILED) return false
        if (!resultData.isStateFailed()) {
            Logger.logWarn(
                LOG_TAG,
                "The ${getCommandIdAndLabelLogString()} has an invalid errCode value set in errors list while having ExecutionState.FAILED state.\n${resultData.errorsList}"
            )
            return false
        }
        return true
    }

    override fun toString(): String =
        if (!hasExecuted()) getExecutionInputLogString(this, true, true)
        else getExecutionOutputLogString(this, true, true, true)

    companion object {
        private const val LOG_TAG = "ExecutionCommand"

        @JvmStatic
        fun getExecutionInputLogString(executionCommand: ExecutionCommand?, ignoreNull: Boolean, logStdin: Boolean): String {
            if (executionCommand == null) return "null"
            return buildString {
                append(executionCommand.getCommandIdAndLabelLogString()).append(":")
                if (executionCommand.mPid != -1) append("\n").append(executionCommand.getPidLogString())
                if (executionCommand.previousState != ExecutionState.PRE_EXECUTION) append("\n").append(executionCommand.getPreviousStateLogString())
                append("\n").append(executionCommand.getCurrentStateLogString())
                append("\n").append(executionCommand.getExecutableLogString())
                append("\n").append(executionCommand.getArgumentsLogString())
                append("\n").append(executionCommand.getWorkingDirectoryLogString())
                append("\n").append(executionCommand.getRunnerLogString())
                append("\n").append(executionCommand.getIsFailsafeLogString())
                if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
                    if (logStdin && (!ignoreNull || !DataUtils.isNullOrEmpty(executionCommand.stdin)))
                        append("\n").append(executionCommand.getStdinLogString())
                    if (!ignoreNull || executionCommand.backgroundCustomLogLevel != null)
                        append("\n").append(executionCommand.getBackgroundCustomLogLevelLogString())
                }
                if (!ignoreNull || executionCommand.sessionAction != null)
                    append("\n").append(executionCommand.getSessionActionLogString())
                if (!ignoreNull || executionCommand.shellName != null)
                    append("\n").append(executionCommand.getShellNameLogString())
                if (!ignoreNull || executionCommand.shellCreateMode != null)
                    append("\n").append(executionCommand.getShellCreateModeLogString())
                append("\n").append(executionCommand.getSetRunnerShellEnvironmentLogString())
                if (!ignoreNull || executionCommand.commandIntent != null)
                    append("\n").append(executionCommand.getCommandIntentLogString())
                append("\n").append(executionCommand.getIsPluginExecutionCommandLogString())
                if (executionCommand.isPluginExecutionCommand)
                    append("\n").append(ResultConfig.getResultConfigLogString(executionCommand.resultConfig, ignoreNull))
            }
        }

        @JvmStatic
        fun getExecutionOutputLogString(
            executionCommand: ExecutionCommand?,
            ignoreNull: Boolean,
            logResultData: Boolean,
            logStdoutAndStderr: Boolean
        ): String {
            if (executionCommand == null) return "null"
            return buildString {
                append(executionCommand.getCommandIdAndLabelLogString()).append(":")
                append("\n").append(executionCommand.getPreviousStateLogString())
                append("\n").append(executionCommand.getCurrentStateLogString())
                if (logResultData)
                    append("\n").append(ResultData.getResultDataLogString(executionCommand.resultData, logStdoutAndStderr))
            }
        }

        @JvmStatic
        fun getDetailedLogString(executionCommand: ExecutionCommand?): String {
            if (executionCommand == null) return "null"
            return buildString {
                append(getExecutionInputLogString(executionCommand, false, true))
                append(getExecutionOutputLogString(executionCommand, false, true, true))
                append("\n").append(executionCommand.getCommandDescriptionLogString())
                append("\n").append(executionCommand.getCommandHelpLogString())
                append("\n").append(executionCommand.getPluginAPIHelpLogString())
            }
        }

        @JvmStatic
        fun getExecutionCommandMarkdownString(executionCommand: ExecutionCommand?): String {
            if (executionCommand == null) return "null"
            if (executionCommand.commandLabel == null) executionCommand.commandLabel = "Execution Command"
            return buildString {
                append("## ").append(executionCommand.commandLabel).append("\n")
                if (executionCommand.mPid != -1)
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Pid", executionCommand.mPid, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Previous State", executionCommand.previousState.getName(), "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Current State", executionCommand.currentState.getName(), "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Executable", executionCommand.executable, "-"))
                append("\n").append(getArgumentsMarkdownString("Arguments", executionCommand.arguments))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Working Directory", executionCommand.workingDirectory, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Runner", executionCommand.runner, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("isFailsafe", executionCommand.isFailsafe, "-"))
                if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
                    if (!DataUtils.isNullOrEmpty(executionCommand.stdin))
                        append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Stdin", executionCommand.stdin, "-"))
                    if (executionCommand.backgroundCustomLogLevel != null)
                        append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Background Custom Log Level", executionCommand.backgroundCustomLogLevel, "-"))
                }
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Session Action", executionCommand.sessionAction, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Shell Name", executionCommand.shellName, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Shell Create Mode", executionCommand.shellCreateMode, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Set Shell Command Shell Environment", executionCommand.setShellCommandShellEnvironment, "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("isPluginExecutionCommand", executionCommand.isPluginExecutionCommand, "-"))
                append("\n\n").append(ResultConfig.getResultConfigMarkdownString(executionCommand.resultConfig))
                append("\n\n").append(ResultData.getResultDataMarkdownString(executionCommand.resultData))
                if (executionCommand.commandDescription != null || executionCommand.commandHelp != null) {
                    if (executionCommand.commandDescription != null)
                        append("\n\n### Command Description\n\n").append(executionCommand.commandDescription).append("\n")
                    if (executionCommand.commandHelp != null)
                        append("\n\n### Command Help\n\n").append(executionCommand.commandHelp).append("\n")
                    append("\n##\n")
                }
                if (executionCommand.pluginAPIHelp != null)
                    append("\n\n### Plugin API Help\n\n").append(executionCommand.pluginAPIHelp).append("\n##\n")
            }
        }

        @JvmStatic
        fun getArgumentsLogString(label: String, argumentsArray: Array<String>?): String {
            val result = StringBuilder("$label:")
            if (!argumentsArray.isNullOrEmpty()) {
                result.append("\n```\n")
                argumentsArray.forEachIndexed { index, argument ->
                    result.append(
                        Logger.getSingleLineLogStringEntry(
                            "Arg ${index + 1}",
                            DataUtils.getTruncatedCommandOutput(
                                argument,
                                Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5,
                                true,
                                false,
                                true
                            ),
                            "-"
                        )
                    ).append("\n")
                }
                result.append("```")
            } else {
                result.append(" -")
            }
            return result.toString()
        }

        @JvmStatic
        fun getArgumentsMarkdownString(label: String, argumentsArray: Array<String>?): String {
            val result = StringBuilder("**$label:**")
            if (!argumentsArray.isNullOrEmpty()) {
                result.append("\n")
                argumentsArray.forEachIndexed { index, argument ->
                    result.append(MarkdownUtils.getMultiLineMarkdownStringEntry("Arg ${index + 1}", argument, "-")).append("\n")
                }
            } else {
                result.append(" -  ")
            }
            return result.toString()
        }
    }

    fun getIdLogString(): String = if (id != null) "($id) " else ""
    fun getPidLogString(): String = "Pid: `$mPid`"
    fun getCurrentStateLogString(): String = "Current State: `${currentState.getName()}`"
    fun getPreviousStateLogString(): String = "Previous State: `${previousState.getName()}`"
    fun getCommandLabelLogString(): String = if (!commandLabel.isNullOrEmpty()) commandLabel!! else "Execution Command"
    fun getCommandIdAndLabelLogString(): String = getIdLogString() + getCommandLabelLogString()
    fun getExecutableLogString(): String = "Executable: `$executable`"
    fun getArgumentsLogString(): String = getArgumentsLogString("Arguments", arguments)
    fun getWorkingDirectoryLogString(): String = "Working Directory: `$workingDirectory`"
    fun getRunnerLogString(): String = Logger.getSingleLineLogStringEntry("Runner", runner, "-")
    fun getIsFailsafeLogString(): String = "isFailsafe: `$isFailsafe`"
    fun getStdinLogString(): String =
        if (DataUtils.isNullOrEmpty(stdin)) "Stdin: -" else Logger.getMultiLineLogStringEntry("Stdin", stdin, "-")
    fun getBackgroundCustomLogLevelLogString(): String = "Background Custom Log Level: `$backgroundCustomLogLevel`"
    fun getSessionActionLogString(): String = Logger.getSingleLineLogStringEntry("Session Action", sessionAction, "-")
    fun getShellNameLogString(): String = Logger.getSingleLineLogStringEntry("Shell Name", shellName, "-")
    fun getShellCreateModeLogString(): String = Logger.getSingleLineLogStringEntry("Shell Create Mode", shellCreateMode, "-")
    fun getSetRunnerShellEnvironmentLogString(): String = "Set Shell Command Shell Environment: `$setShellCommandShellEnvironment`"
    fun getCommandDescriptionLogString(): String = Logger.getSingleLineLogStringEntry("Command Description", commandDescription, "-")
    fun getCommandHelpLogString(): String = Logger.getSingleLineLogStringEntry("Command Help", commandHelp, "-")
    fun getPluginAPIHelpLogString(): String = Logger.getSingleLineLogStringEntry("Plugin API Help", pluginAPIHelp, "-")
    fun getCommandIntentLogString(): String =
        if (commandIntent == null) "Command Intent: -" else Logger.getMultiLineLogStringEntry("Command Intent", IntentUtils.getIntentString(commandIntent), "-")
    fun getIsPluginExecutionCommandLogString(): String = "isPluginExecutionCommand: `$isPluginExecutionCommand`"
}
