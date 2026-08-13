package com.gph.fable.shared.shell.command.result

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.gph.fable.shared.R
import com.gph.fable.shared.android.AndroidUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.errors.FunctionErrno
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.shell.command.ShellCommandConstants.RESULT_SENDER

object ResultSender {
    private const val LOG_TAG = "ResultSender"

    @JvmStatic
    fun sendCommandResultData(
        context: Context?,
        logTag: String?,
        label: String?,
        resultConfig: ResultConfig?,
        resultData: ResultData?,
        logStdoutAndStderr: Boolean
    ): Error? {
        if (context == null || resultConfig == null || resultData == null) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETERS.getError(
                "context, resultConfig or resultData", "sendCommandResultData"
            )
        }
        if (resultConfig.resultPendingIntent != null) {
            val error = sendCommandResultDataWithPendingIntent(
                context, logTag, label, resultConfig, resultData, logStdoutAndStderr
            )
            if (error != null || resultConfig.resultDirectoryPath == null) return error
        }
        return if (resultConfig.resultDirectoryPath != null) {
            sendCommandResultDataToDirectory(context, logTag, label, resultConfig, resultData, logStdoutAndStderr)
        } else {
            FunctionErrno.ERRNO_UNSET_PARAMETERS.getError(
                "resultConfig.resultPendingIntent or resultConfig.resultDirectoryPath", "sendCommandResultData"
            )
        }
    }

    @JvmStatic
    fun sendCommandResultDataWithPendingIntent(
        context: Context?,
        logTag: String?,
        label: String?,
        resultConfig: ResultConfig?,
        resultData: ResultData?,
        logStdoutAndStderr: Boolean
    ): Error? {
        if (context == null || resultConfig == null || resultData == null ||
            resultConfig.resultPendingIntent == null || resultConfig.resultBundleKey == null
        ) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                "context, resultConfig, resultData, resultConfig.resultPendingIntent or resultConfig.resultBundleKey",
                "sendCommandResultDataWithPendingIntent"
            )
        }

        val actualLogTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)
        Logger.logDebugExtended(
            actualLogTag,
            "Sending result for command \"$label\":\n${resultConfig}\n${ResultData.getResultDataLogString(resultData, logStdoutAndStderr)}"
        )

        var stdout = resultData.stdout.toString()
        var stderr = resultData.stderr.toString()
        val stdoutOriginalLength = stdout.length.toString()
        val stderrOriginalLength = stderr.length.toString()

        val stdoutLimit = if (stderr.isEmpty()) DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES
        else DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES / 2
        val stderrLimit = if (stdout.isEmpty()) DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES
        else DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES / 2
        DataUtils.getTruncatedCommandOutput(stdout, stdoutLimit, false, false, false)?.let {
            if (it.length < stdout.length) {
                Logger.logWarn(actualLogTag, "The result for command \"$label\" stdout length truncated from $stdoutOriginalLength to ${it.length}")
                stdout = it
            }
        }
        DataUtils.getTruncatedCommandOutput(stderr, stderrLimit, false, false, false)?.let {
            if (it.length < stderr.length) {
                Logger.logWarn(actualLogTag, "The result for command \"$label\" stderr length truncated from $stderrOriginalLength to ${it.length}")
                stderr = it
            }
        }

        var errmsg: String? = null
        if (resultData.isStateFailed()) {
            errmsg = ResultData.getErrorsListLogString(resultData).ifEmpty { null }
        }
        val errmsgOriginalLength = errmsg?.length?.toString()
        DataUtils.getTruncatedCommandOutput(
            errmsg, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES / 4, true, false, false
        )?.let {
            if (errmsg != null && it.length < errmsg.length) {
                Logger.logWarn(actualLogTag, "The result for command \"$label\" error length truncated from $errmsgOriginalLength to ${it.length}")
                errmsg = it
            }
        }

        val bundle = Bundle().apply {
            putString(resultConfig.resultStdoutKey, stdout)
            putString(resultConfig.resultStdoutOriginalLengthKey, stdoutOriginalLength)
            putString(resultConfig.resultStderrKey, stderr)
            putString(resultConfig.resultStderrOriginalLengthKey, stderrOriginalLength)
            resultData.exitCode?.let { putInt(resultConfig.resultExitCodeKey, it) }
            putInt(resultConfig.resultErrCodeKey, resultData.getErrCode())
            putString(resultConfig.resultErrmsgKey, errmsg)
        }
        val resultIntent = Intent().putExtra(resultConfig.resultBundleKey, bundle)
        try {
            resultConfig.resultPendingIntent!!.send(context, Activity.RESULT_OK, resultIntent)
        } catch (_: PendingIntent.CanceledException) {
            Logger.logDebug(
                actualLogTag,
                "The command \"$label\" creator ${resultConfig.resultPendingIntent!!.creatorPackage} does not want the results anymore"
            )
        }
        return null
    }

    @JvmStatic
    fun sendCommandResultDataToDirectory(
        context: Context?,
        logTag: String?,
        label: String?,
        resultConfig: ResultConfig?,
        resultData: ResultData?,
        logStdoutAndStderr: Boolean
    ): Error? {
        if (context == null || resultConfig == null || resultData == null ||
            DataUtils.isNullOrEmpty(resultConfig.resultDirectoryPath)
        ) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                "context, resultConfig, resultData or resultConfig.resultDirectoryPath",
                "sendCommandResultDataToDirectory"
            )
        }

        val actualLogTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)
        val stdout = resultData.stdout.toString()
        val stderr = resultData.stderr.toString()
        val exitCode = resultData.exitCode?.toString() ?: ""
        val errmsg = if (resultData.isStateFailed()) {
            ResultData.getErrorsListLogString(resultData)
        } else ""

        resultConfig.resultDirectoryPath =
            FileUtils.getCanonicalPath(resultConfig.resultDirectoryPath, null)
        Logger.logDebugExtended(
            actualLogTag,
            "Writing result for command \"$label\":\n${resultConfig}\n${ResultData.getResultDataLogString(resultData, logStdoutAndStderr)}"
        )

        var error = FileUtils.validateDirectoryFileExistenceAndPermissions(
            "result",
            resultConfig.resultDirectoryPath,
            resultConfig.resultDirectoryAllowedParentPath,
            true,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS,
            true,
            true,
            true,
            true
        )
        if (error != null) {
            error.appendMessage(
                "\n" + context.getString(R.string.msg_directory_absolute_path, "Result", resultConfig.resultDirectoryPath)
            )
            return error
        }

        if (resultConfig.resultSingleFile) {
            val basename = resultConfig.resultFileBasename
            if (DataUtils.isNullOrEmpty(basename) || basename!!.contains("/")) {
                return ResultSenderErrno.ERROR_RESULT_FILE_BASENAME_NULL_OR_INVALID.getError(basename)
            }

            val output = try {
                if (resultData.isStateFailed()) {
                    if (DataUtils.isNullOrEmpty(resultConfig.resultFileErrorFormat)) {
                        String.format(
                            RESULT_SENDER.FORMAT_FAILED_ERR__ERRMSG__STDOUT__STDERR__EXIT_CODE,
                            MarkdownUtils.getMarkdownCodeForString(resultData.getErrCode().toString(), false),
                            MarkdownUtils.getMarkdownCodeForString(errmsg, true),
                            MarkdownUtils.getMarkdownCodeForString(stdout, true),
                            MarkdownUtils.getMarkdownCodeForString(stderr, true),
                            MarkdownUtils.getMarkdownCodeForString(exitCode, false)
                        )
                    } else {
                        String.format(resultConfig.resultFileErrorFormat ?: "", resultData.getErrCode(), errmsg, stdout, stderr, exitCode)
                    }
                } else if (DataUtils.isNullOrEmpty(resultConfig.resultFileOutputFormat)) {
                    when {
                        stderr.isEmpty() && exitCode == "0" ->
                            String.format(RESULT_SENDER.FORMAT_SUCCESS_STDOUT, stdout)
                        stderr.isEmpty() ->
                            String.format(
                                RESULT_SENDER.FORMAT_SUCCESS_STDOUT__EXIT_CODE,
                                stdout,
                                MarkdownUtils.getMarkdownCodeForString(exitCode, false)
                            )
                        else ->
                            String.format(
                                RESULT_SENDER.FORMAT_SUCCESS_STDOUT__STDERR__EXIT_CODE,
                                MarkdownUtils.getMarkdownCodeForString(stdout, true),
                                MarkdownUtils.getMarkdownCodeForString(stderr, true),
                                MarkdownUtils.getMarkdownCodeForString(exitCode, false)
                            )
                    }
                } else {
                    String.format(resultConfig.resultFileOutputFormat ?: "", stdout, stderr, exitCode)
                }
            } catch (e: Exception) {
                return if (resultData.isStateFailed()) {
                    ResultSenderErrno.ERROR_FORMAT_RESULT_ERROR_FAILED_WITH_EXCEPTION.getError(e.message)
                } else {
                    ResultSenderErrno.ERROR_FORMAT_RESULT_OUTPUT_FAILED_WITH_EXCEPTION.getError(e.message)
                }
            }

            val tempName = "$basename-${AndroidUtils.getCurrentMilliSecondLocalTimeStamp()}"
            error = FileUtils.writeTextToFile(
                tempName, "${resultConfig.resultDirectoryPath}/$tempName", null, output, false
            )
            if (error != null) return error
            return FileUtils.moveRegularFile(
                "error or output temp file",
                "${resultConfig.resultDirectoryPath}/$tempName",
                "${resultConfig.resultDirectoryPath}/$basename",
                false
            )
        }

        val suffix = resultConfig.resultFilesSuffix ?: ""
        if (suffix.contains("/")) {
            return ResultSenderErrno.ERROR_RESULT_FILES_SUFFIX_INVALID.getError(suffix)
        }

        fun writeIfNonEmpty(prefix: String, value: String): Error? {
            if (value.isEmpty()) return null
            val filename = prefix + suffix
            return FileUtils.writeTextToFile(
                filename, "${resultConfig.resultDirectoryPath}/$filename", null, value, false
            )
        }

        error = writeIfNonEmpty(RESULT_SENDER.RESULT_FILE_STDOUT_PREFIX, stdout)
        if (error != null) return error
        error = writeIfNonEmpty(RESULT_SENDER.RESULT_FILE_STDERR_PREFIX, stderr)
        if (error != null) return error
        error = writeIfNonEmpty(RESULT_SENDER.RESULT_FILE_EXIT_CODE_PREFIX, exitCode)
        if (error != null) return error
        if (resultData.isStateFailed() && errmsg.isNotEmpty()) {
            error = writeIfNonEmpty(RESULT_SENDER.RESULT_FILE_ERRMSG_PREFIX, errmsg)
            if (error != null) return error
        }

        var tempName = RESULT_SENDER.RESULT_FILE_ERR_PREFIX + "-" + AndroidUtils.getCurrentMilliSecondLocalTimeStamp()
        if (suffix.isNotEmpty()) tempName += "-$suffix"
        error = FileUtils.writeTextToFile(
            tempName, "${resultConfig.resultDirectoryPath}/$tempName", null,
            resultData.getErrCode().toString(), false
        )
        if (error != null) return error
        val errFilename = RESULT_SENDER.RESULT_FILE_ERR_PREFIX + suffix
        return FileUtils.moveRegularFile(
            "${RESULT_SENDER.RESULT_FILE_ERR_PREFIX} temp file",
            "${resultConfig.resultDirectoryPath}/$tempName",
            "${resultConfig.resultDirectoryPath}/$errFilename",
            false
        )
    }
}
