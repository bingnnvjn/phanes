package com.gph.fable.shared.shell.command.result

import android.app.PendingIntent
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils

class ResultConfig {
    @JvmField var resultPendingIntent: PendingIntent? = null
    @JvmField var resultBundleKey: String? = null
    @JvmField var resultStdoutKey: String? = null
    @JvmField var resultStderrKey: String? = null
    @JvmField var resultExitCodeKey: String? = null
    @JvmField var resultErrCodeKey: String? = null
    @JvmField var resultErrmsgKey: String? = null
    @JvmField var resultStdoutOriginalLengthKey: String? = null
    @JvmField var resultStderrOriginalLengthKey: String? = null
    @JvmField var resultDirectoryPath: String? = null
    @JvmField var resultDirectoryAllowedParentPath: String? = null
    @JvmField var resultSingleFile = false
    @JvmField var resultFileBasename: String? = null
    @JvmField var resultFileOutputFormat: String? = null
    @JvmField var resultFileErrorFormat: String? = null
    @JvmField var resultFilesSuffix: String? = null

    fun isCommandWithPendingResult() = resultPendingIntent != null || resultDirectoryPath != null
    override fun toString() = getResultConfigLogString(this, true)

    fun getResultPendingIntentVariablesLogString(ignoreNull: Boolean): String {
        val intent = resultPendingIntent ?: return "Result PendingIntent Creator: -"
        return buildString {
            append("Result PendingIntent Creator: `").append(intent.creatorPackage).append("`")
            if (!ignoreNull || resultBundleKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Bundle Key", resultBundleKey, "-"))
            if (!ignoreNull || resultStdoutKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Stdout Key", resultStdoutKey, "-"))
            if (!ignoreNull || resultStderrKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Stderr Key", resultStderrKey, "-"))
            if (!ignoreNull || resultExitCodeKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Exit Code Key", resultExitCodeKey, "-"))
            if (!ignoreNull || resultErrCodeKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Err Code Key", resultErrCodeKey, "-"))
            if (!ignoreNull || resultErrmsgKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Error Key", resultErrmsgKey, "-"))
            if (!ignoreNull || resultStdoutOriginalLengthKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Stdout Original Length Key", resultStdoutOriginalLengthKey, "-"))
            if (!ignoreNull || resultStderrOriginalLengthKey != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Stderr Original Length Key", resultStderrOriginalLengthKey, "-"))
        }
    }

    fun getResultDirectoryVariablesLogString(ignoreNull: Boolean): String {
        val path = resultDirectoryPath ?: return "Result Directory Path: -"
        return buildString {
            append(Logger.getSingleLineLogStringEntry("Result Directory Path", path, "-"))
            append("\n").append(Logger.getSingleLineLogStringEntry("Result Single File", resultSingleFile, "-"))
            if (!ignoreNull || resultFileBasename != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result File Basename", resultFileBasename, "-"))
            if (!ignoreNull || resultFileOutputFormat != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result File Output Format", resultFileOutputFormat, "-"))
            if (!ignoreNull || resultFileErrorFormat != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result File Error Format", resultFileErrorFormat, "-"))
            if (!ignoreNull || resultFilesSuffix != null) append("\n").append(Logger.getSingleLineLogStringEntry("Result Files Suffix", resultFilesSuffix, "-"))
        }
    }

    companion object {
        @JvmStatic
        fun getResultConfigLogString(config: ResultConfig?, ignoreNull: Boolean): String {
            if (config == null) return "null"
            return buildString {
                append("Result Pending: `").append(config.isCommandWithPendingResult()).append("`\n")
                if (config.resultPendingIntent != null) append(config.getResultPendingIntentVariablesLogString(ignoreNull))
                if (config.resultPendingIntent != null && config.resultDirectoryPath != null) append("\n")
                if (!config.resultDirectoryPath.isNullOrEmpty()) append(config.getResultDirectoryVariablesLogString(ignoreNull))
            }
        }

        @JvmStatic
        fun getResultConfigMarkdownString(config: ResultConfig?): String {
            if (config == null) return "null"
            return buildString {
                if (config.resultPendingIntent != null) append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result PendingIntent Creator", config.resultPendingIntent!!.creatorPackage, "-"))
                else append("**Result PendingIntent Creator:** -  ")
                if (config.resultDirectoryPath != null) {
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Directory Path", config.resultDirectoryPath, "-"))
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Single File", config.resultSingleFile, "-"))
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Basename", config.resultFileBasename, "-"))
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Output Format", config.resultFileOutputFormat, "-"))
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Error Format", config.resultFileErrorFormat, "-"))
                    append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Files Suffix", config.resultFilesSuffix, "-"))
                }
            }
        }
    }
}
