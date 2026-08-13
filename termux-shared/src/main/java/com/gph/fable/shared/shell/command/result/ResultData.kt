package com.gph.fable.shared.shell.command.result

import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Errno
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import java.io.Serializable

class ResultData : Serializable {
    @JvmField val stdout = StringBuilder()
    @JvmField val stderr = StringBuilder()
    @JvmField var exitCode: Int? = null
    @JvmField var errorsList: List<Error> = ArrayList()

    fun clearStdout() { stdout.setLength(0) }
    fun prependStdout(message: String) = stdout.insert(0, message)
    fun prependStdoutLn(message: String) = stdout.insert(0, "$message\n")
    fun appendStdout(message: String) = stdout.append(message)
    fun appendStdoutLn(message: String) = stdout.append(message).append("\n")
    fun clearStderr() { stderr.setLength(0) }
    fun prependStderr(message: String) = stderr.insert(0, message)
    fun prependStderrLn(message: String) = stderr.insert(0, "$message\n")
    fun appendStderr(message: String) = stderr.append(message)
    fun appendStderrLn(message: String) = stderr.append(message).append("\n")

    @Synchronized fun setStateFailed(error: Error) =
        setStateFailed(error.getType(), error.getCode(), error.getMessage(), null)
    @Synchronized fun setStateFailed(error: Error, throwable: Throwable?) =
        setStateFailed(error.getType(), error.getCode(), error.getMessage(), listOfNotNull(throwable))
    @Synchronized fun setStateFailed(error: Error, throwablesList: List<Throwable>?) =
        setStateFailed(error.getType(), error.getCode(), error.getMessage(), throwablesList)
    @Synchronized fun setStateFailed(code: Int, message: String?) = setStateFailed(null, code, message, null)
    @Synchronized fun setStateFailed(code: Int, message: String?, throwable: Throwable?) =
        setStateFailed(null, code, message, listOfNotNull(throwable))
    @Synchronized fun setStateFailed(code: Int, message: String?, throwablesList: List<Throwable>?) =
        setStateFailed(null, code, message, throwablesList)
    @Synchronized fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: List<Throwable>?): Boolean {
        val errors = errorsList.toMutableList()
        val error = Error()
        errors.add(error)
        errorsList = errors
        return error.setStateFailed(type, code, message, throwablesList)
    }

    fun isStateFailed() = errorsList.any { it.isStateFailed() }
    fun getErrCode() = errorsList.lastOrNull()?.getCode() ?: Errno.ERRNO_SUCCESS.code
    override fun toString() = getResultDataLogString(this, true)

    fun getStdoutLogString() =
        if (stdout.isEmpty()) Logger.getSingleLineLogStringEntry("Stdout", null, "-")
        else Logger.getMultiLineLogStringEntry("Stdout", DataUtils.getTruncatedCommandOutput(stdout.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5, false, false, true), "-")
    fun getStderrLogString() =
        if (stderr.isEmpty()) Logger.getSingleLineLogStringEntry("Stderr", null, "-")
        else Logger.getMultiLineLogStringEntry("Stderr", DataUtils.getTruncatedCommandOutput(stderr.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5, false, false, true), "-")
    fun getExitCodeLogString() = Logger.getSingleLineLogStringEntry("Exit Code", exitCode, "-")

    companion object {
        @JvmStatic fun getResultDataLogString(resultData: ResultData?, logStdoutAndStderr: Boolean): String {
            if (resultData == null) return "null"
            return buildString {
                if (logStdoutAndStderr) {
                    append("\n").append(resultData.getStdoutLogString())
                    append("\n").append(resultData.getStderrLogString())
                }
                append("\n").append(resultData.getExitCodeLogString())
                append("\n\n").append(getErrorsListLogString(resultData))
            }
        }
        @JvmStatic fun getErrorsListLogString(resultData: ResultData?) =
            resultData?.errorsList?.filter { it.isStateFailed() }?.joinToString("\n") { Error.getErrorLogString(it) } ?: "null"
        @JvmStatic fun getResultDataMarkdownString(resultData: ResultData?): String {
            if (resultData == null) return "null"
            return buildString {
                append(if (resultData.stdout.isEmpty()) MarkdownUtils.getSingleLineMarkdownStringEntry("Stdout", null, "-") else MarkdownUtils.getMultiLineMarkdownStringEntry("Stdout", resultData.stdout.toString(), "-"))
                append("\n").append(if (resultData.stderr.isEmpty()) MarkdownUtils.getSingleLineMarkdownStringEntry("Stderr", null, "-") else MarkdownUtils.getMultiLineMarkdownStringEntry("Stderr", resultData.stderr.toString(), "-"))
                append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Exit Code", resultData.exitCode, "-"))
                append("\n\n").append(getErrorsListMarkdownString(resultData))
            }
        }
        @JvmStatic fun getErrorsListMarkdownString(resultData: ResultData?) =
            resultData?.errorsList?.filter { it.isStateFailed() }?.joinToString("\n") { Error.getErrorMarkdownString(it) } ?: "null"
        @JvmStatic fun getErrorsListMinimalString(resultData: ResultData?) =
            resultData?.errorsList?.filter { it.isStateFailed() }?.joinToString("\n") { Error.getMinimalErrorString(it) } ?: "null"
    }
}
