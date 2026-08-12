package com.gph.fable.shared.models

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.Keep
import androidx.annotation.NonNull
import com.gph.fable.shared.activities.TextIOActivity
import com.gph.fable.shared.data.DataUtils
import java.io.Serializable
import kotlin.jvm.JvmName

/**
 * An object that stored info for [TextIOActivity].
 * Max text limit is 95KB to prevent TransactionTooLargeException as per
 * [DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES]. Larger size can be supported for in-app
 * transactions by storing [TextIOInfo] as a serialized object in a file like
 * [com.gph.fable.shared.activities.ReportActivity] does.
 */
class TextIOInfo(@NonNull action: String, @NonNull sender: String) : Serializable {

    /** The action for which [TextIOActivity] will be started. */
    private val mAction: String
    /** The internal app component that is will start the [TextIOActivity]. */
    private val mSender: String

    /** The activity title. */
    private var mTitle: String? = null

    /** If back button should be shown in [android.app.ActionBar]. */
    private var showBackButtonInActionBarBacking: Boolean = false

    /** If label is enabled. */
    private var labelEnabled: Boolean = false
    /**
     * The label of text input set in [android.widget.TextView] that can be updated by user.
     * Max allowed length is [LABEL_SIZE_LIMIT_IN_BYTES].
     */
    private var label: String? = null
    /** The text size of label. Defaults to 14sp. */
    private var labelSize: Int = 14
    /** The text color of label. Defaults to [Color.BLACK]. */
    private var labelColor: Int = Color.BLACK
    /** The [Typeface] family of label. Defaults to "sans-serif". */
    private var labelTypeFaceFamily: String? = "sans-serif"
    /** The [Typeface] style of label. Defaults to [Typeface.BOLD]. */
    private var labelTypeFaceStyle: Int = Typeface.BOLD

    /**
     * The text of text input set in [android.widget.EditText] that can be updated by user.
     * Max allowed length is [TEXT_SIZE_LIMIT_IN_BYTES].
     */
    private var text: String? = null
    /** The text size for text. Defaults to 12sp. */
    private var textSize: Int = 12
    /** The text size for text. Defaults to [TEXT_SIZE_LIMIT_IN_BYTES]. */
    private var textLengthLimit: Int = TEXT_SIZE_LIMIT_IN_BYTES
    /** The text color of text. Defaults to [Color.BLACK]. */
    private var textColor: Int = Color.BLACK
    /** The [Typeface] family for text. Defaults to "sans-serif". */
    private var textTypeFaceFamily: String? = "sans-serif"
    /** The [Typeface] style for text. Defaults to [Typeface.NORMAL]. */
    private var textTypeFaceStyle: Int = Typeface.NORMAL
    /** If horizontal scrolling should be enabled for text. */
    private var horizontallyScrollable: Boolean = false
    /** If character usage should be enabled for text. */
    private var showTextCharacterUsage: Boolean = false
    /** If editing text should be disabled so that text acts like its in a [android.widget.TextView]. */
    private var editingTextDisabled: Boolean = false

    init {
        mAction = action
        mSender = sender
    }

    fun getAction(): String {
        return mAction
    }

    fun getSender(): String {
        return mSender
    }

    fun getTitle(): String? {
        return mTitle
    }

    fun setTitle(title: String?) {
        mTitle = title
    }

    @get:JvmName("shouldShowBackButtonInActionBar")
    @set:JvmName("setShowBackButtonInActionBar")
    var showBackButtonInActionBar: Boolean
        get() = showBackButtonInActionBarBacking
        set(value) {
            showBackButtonInActionBarBacking = value
        }

    fun isLabelEnabled(): Boolean {
        return labelEnabled
    }

    fun setLabelEnabled(labelEnabled: Boolean) {
        this.labelEnabled = labelEnabled
    }

    fun getLabel(): String? {
        return label
    }

    fun setLabel(label: String?) {
        this.label = DataUtils.getTruncatedCommandOutput(label, LABEL_SIZE_LIMIT_IN_BYTES, true, false, false)
    }

    fun getLabelSize(): Int {
        return labelSize
    }

    fun setLabelSize(labelSize: Int) {
        if (labelSize > 0)
            this.labelSize = labelSize
    }

    fun getLabelColor(): Int {
        return labelColor
    }

    fun setLabelColor(labelColor: Int) {
        this.labelColor = labelColor
    }

    fun getLabelTypeFaceFamily(): String? {
        return labelTypeFaceFamily
    }

    fun setLabelTypeFaceFamily(labelTypeFaceFamily: String?) {
        this.labelTypeFaceFamily = labelTypeFaceFamily
    }

    fun getLabelTypeFaceStyle(): Int {
        return labelTypeFaceStyle
    }

    fun setLabelTypeFaceStyle(labelTypeFaceStyle: Int) {
        this.labelTypeFaceStyle = labelTypeFaceStyle
    }

    fun getText(): String? {
        return text
    }

    fun setText(text: String?) {
        this.text = DataUtils.getTruncatedCommandOutput(text, TEXT_SIZE_LIMIT_IN_BYTES, true, false, false)
    }

    fun getTextSize(): Int {
        return textSize
    }

    fun setTextSize(textSize: Int) {
        if (textSize > 0)
            this.textSize = textSize
    }

    fun getTextLengthLimit(): Int {
        return textLengthLimit
    }

    fun setTextLengthLimit(textLengthLimit: Int) {
        if (textLengthLimit < TEXT_SIZE_LIMIT_IN_BYTES)
            this.textLengthLimit = textLengthLimit
    }

    fun getTextColor(): Int {
        return textColor
    }

    fun setTextColor(textColor: Int) {
        this.textColor = textColor
    }

    fun getTextTypeFaceFamily(): String? {
        return textTypeFaceFamily
    }

    fun setTextTypeFaceFamily(textTypeFaceFamily: String?) {
        this.textTypeFaceFamily = textTypeFaceFamily
    }

    fun getTextTypeFaceStyle(): Int {
        return textTypeFaceStyle
    }

    fun setTextTypeFaceStyle(textTypeFaceStyle: Int) {
        this.textTypeFaceStyle = textTypeFaceStyle
    }

    fun isHorizontallyScrollable(): Boolean {
        return horizontallyScrollable
    }

    @JvmName("setTextHorizontallyScrolling")
    fun setTextHorizontallyScrolling(textHorizontallyScrolling: Boolean) {
        this.horizontallyScrollable = textHorizontallyScrolling
    }

    fun shouldShowTextCharacterUsage(): Boolean {
        return showTextCharacterUsage
    }

    fun setShowTextCharacterUsage(showTextCharacterUsage: Boolean) {
        this.showTextCharacterUsage = showTextCharacterUsage
    }

    fun isEditingTextDisabled(): Boolean {
        return editingTextDisabled
    }

    fun setEditingTextDisabled(editingTextDisabled: Boolean) {
        this.editingTextDisabled = editingTextDisabled
    }

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

        const val GENERAL_DATA_SIZE_LIMIT_IN_BYTES = 1000
        const val LABEL_SIZE_LIMIT_IN_BYTES = 4000
        const val TEXT_SIZE_LIMIT_IN_BYTES = 100000 - GENERAL_DATA_SIZE_LIMIT_IN_BYTES - LABEL_SIZE_LIMIT_IN_BYTES // < 100KB
    }
}
