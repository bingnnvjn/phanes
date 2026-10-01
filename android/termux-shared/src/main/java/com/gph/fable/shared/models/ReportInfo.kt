package com.gph.fable.shared.models

import androidx.annotation.Keep
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.android.AndroidUtils
import java.io.Serializable

/**
 * An object that stored info for [com.gph.fable.shared.activities.ReportActivity].
 */
class ReportInfo(userAction: String, sender: String, reportTitle: String) : Serializable {

    /** The user action that was being processed for which the report was generated. */
    @JvmField
    val userAction: String
    /** The internal app component that sent the report. */
    @JvmField
    val sender: String
    /** The report title. */
    @JvmField
    val reportTitle: String
    /** The timestamp for the report. */
    @JvmField
    val reportTimestamp: String

    /** The markdown report text prefix. Will not be part of copy and share operations, etc. */
    @JvmField
    var reportStringPrefix: String? = null
    /** The markdown report text. */
    @JvmField
    var reportString: String? = null
    /** The markdown report text suffix. Will not be part of copy and share operations, etc. */
    @JvmField
    var reportStringSuffix: String? = null

    /** If set to `true`, then report header info will be added to the report when markdown is generated. */
    @JvmField
    var addReportInfoHeaderToMarkdown: Boolean = false

    /** The label for the report file to save if user selects menu_item_save_report_to_file. */
    @JvmField
    var reportSaveFileLabel: String? = null
    /** The path for the report file to save if user selects menu_item_save_report_to_file. */
    @JvmField
    var reportSaveFilePath: String? = null

    init {
        this.userAction = userAction
        this.sender = sender
        this.reportTitle = reportTitle
        this.reportTimestamp = AndroidUtils.getCurrentMilliSecondUTCTimeStamp()
    }

    fun setReportStringPrefix(reportStringPrefix: String?) {
        this.reportStringPrefix = reportStringPrefix
    }

    fun setReportString(reportString: String?) {
        this.reportString = reportString
    }

    fun setReportStringSuffix(reportStringSuffix: String?) {
        this.reportStringSuffix = reportStringSuffix
    }

    fun setAddReportInfoHeaderToMarkdown(addReportInfoHeaderToMarkdown: Boolean) {
        this.addReportInfoHeaderToMarkdown = addReportInfoHeaderToMarkdown
    }

    fun setReportSaveFileLabelAndPath(reportSaveFileLabel: String?, reportSaveFilePath: String?) {
        setReportSaveFileLabel(reportSaveFileLabel)
        setReportSaveFilePath(reportSaveFilePath)
    }

    fun setReportSaveFileLabel(reportSaveFileLabel: String?) {
        this.reportSaveFileLabel = reportSaveFileLabel
    }

    fun setReportSaveFilePath(reportSaveFilePath: String?) {
        this.reportSaveFilePath = reportSaveFilePath
    }

    /**
     * Get a markdown [String] for [ReportInfo].
     *
     * @param reportInfo The [ReportInfo] to convert.
     * @return Returns the markdown [String].
     */
    companion object {
        /**
         * Explicitly define `serialVersionUID` to prevent exceptions on deserialization.
         *
         * Like when calling `Bundle.getSerializable()` on Android.
         * `android.os.BadParcelableException: Parcelable encountered IOException reading a Serializable object` (name = <class_name>)
         * `java.io.InvalidClassException: <class_name>; local class incompatible`
         *
         * The `@Keep` annotation is necessary to prevent the field from being removed by proguard when
         * app is compiled, even if its kept during library compilation.
         */
        @Keep
        private const val serialVersionUID = 1L

        /**
         * Get a markdown [String] for [ReportInfo].
         *
         * @param reportInfo The [ReportInfo] to convert.
         * @return Returns the markdown [String].
         */
        @JvmStatic
        fun getReportInfoMarkdownString(reportInfo: ReportInfo?): String {
            if (reportInfo == null) return "null"

            val markdownString = StringBuilder()

            if (reportInfo.addReportInfoHeaderToMarkdown) {
                markdownString.append("## Report Info\n\n")
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("User Action", reportInfo.userAction, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Sender", reportInfo.sender, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Report Timestamp", reportInfo.reportTimestamp, "-"))
                markdownString.append("\n##\n\n")
            }

            markdownString.append(reportInfo.reportString)

            return markdownString.toString()
        }
    }
}
