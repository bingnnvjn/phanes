package com.gph.fable.shared.errors

import android.content.Context
import androidx.annotation.NonNull
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import java.io.Serializable
import java.util.ArrayList
import java.util.Collections

class Error : Serializable {

    /** The optional error label. */
    private var label: String? = null
    /** The error type. */
    private var type: String? = null
    /** The error code. */
    private var code: Int = 0
    /** The error message. */
    private var message: String? = null
    /** The error exceptions. */
    private var throwablesList: List<Throwable>? = ArrayList()

    constructor() {
        InitError(null, null, null, null)
    }

    constructor(type: String?, code: Int?, message: String?, throwablesList: List<Throwable>?) {
        InitError(type, code, message, throwablesList)
    }

    constructor(type: String?, code: Int?, message: String?, throwable: Throwable?) {
        InitError(type, code, message, Collections.singletonList(throwable))
    }

    constructor(type: String?, code: Int?, message: String?) {
        InitError(type, code, message, null)
    }

    constructor(code: Int?, message: String?, throwablesList: List<Throwable>?) {
        InitError(null, code, message, throwablesList)
    }

    constructor(code: Int?, message: String?, throwable: Throwable?) {
        InitError(null, code, message, Collections.singletonList(throwable))
    }

    constructor(code: Int?, message: String?) {
        InitError(null, code, message, null)
    }

    constructor(message: String?, throwable: Throwable?) {
        InitError(null, null, message, Collections.singletonList(throwable))
    }

    constructor(message: String?, throwablesList: List<Throwable>?) {
        InitError(null, null, message, throwablesList)
    }

    constructor(message: String?) {
        InitError(null, null, message, null)
    }

    private fun InitError(type: String?, code: Int?, message: String?, throwablesList: List<Throwable>?) {
        if (type != null && !type.isEmpty())
            this.type = type
        else
            this.type = Errno.TYPE

        if (code != null && code > Errno.ERRNO_SUCCESS.code)
            this.code = code
        else
            this.code = Errno.ERRNO_SUCCESS.code

        this.message = message

        if (throwablesList != null)
            this.throwablesList = throwablesList
    }

    fun setLabel(label: String?): Error {
        this.label = label
        return this
    }

    fun getLabel(): String? {
        return label
    }

    fun getType(): String? {
        return type
    }

    fun getCode(): Int {
        return code
    }

    fun getMessage(): String? {
        return message
    }

    fun prependMessage(message: String?) {
        if (message != null && isStateFailed())
            this.message = message + this.message
    }

    fun appendMessage(message: String?) {
        if (message != null && isStateFailed())
            this.message = this.message + message
    }

    fun getThrowablesList(): List<Throwable> {
        return Collections.unmodifiableList(throwablesList!!)
    }

    @Synchronized
    fun setStateFailed(@NonNull error: Error): Boolean {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), null)
    }

    @Synchronized
    fun setStateFailed(@NonNull error: Error, throwable: Throwable?): Boolean {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), Collections.singletonList(throwable))
    }

    @Synchronized
    fun setStateFailed(@NonNull error: Error, throwablesList: List<Throwable>?): Boolean {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), throwablesList)
    }

    @Synchronized
    fun setStateFailed(code: Int, message: String?): Boolean {
        return setStateFailed(this.type, code, message, null)
    }

    @Synchronized
    fun setStateFailed(code: Int, message: String?, throwable: Throwable?): Boolean {
        return setStateFailed(this.type, code, message, Collections.singletonList(throwable))
    }

    @Synchronized
    fun setStateFailed(code: Int, message: String?, throwablesList: List<Throwable>?): Boolean {
        return setStateFailed(this.type, code, message, throwablesList)
    }

    @Synchronized
    fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: List<Throwable>?): Boolean {
        this.message = message
        this.throwablesList = throwablesList

        if (type != null && !type.isEmpty())
            this.type = type

        return if (code > Errno.ERRNO_SUCCESS.code) {
            this.code = code
            true
        } else {
            Logger.logWarn(LOG_TAG, "Ignoring invalid error code value \"" + code + "\". Force setting it to RESULT_CODE_FAILED \"" + Errno.ERRNO_FAILED.code + "\"")
            this.code = Errno.ERRNO_FAILED.code
            false
        }
    }

    fun isStateFailed(): Boolean {
        return code > Errno.ERRNO_SUCCESS.code
    }

    @NonNull
    override fun toString(): String {
        return getErrorLogString(this)
    }

    /**
     * Log the [Error] and show a toast for the minimal [String] for the [Error].
     *
     * @param context The [Context] for operations.
     * @param logTag The log tag to use for logging.
     * @param error The [Error] to convert.
     */
    fun logErrorAndShowToast(context: Context?, logTag: String) {
        Logger.logErrorExtended(logTag, getErrorLogString())
        Logger.showToast(context, getMinimalErrorLogString(), true)
    }

    /**
     * Get a log friendly [String] for [Error] error parameters.
     *
     * @param error The [Error] to convert.
     * @return Returns the log friendly [String].
     */
    fun getErrorLogString(): String {
        val logString = StringBuilder()

        logString.append(getCodeString())
        logString.append("\n").append(getTypeAndMessageLogString())
        val list = throwablesList
        if (list != null && list.size > 0)
            logString.append("\n").append(geStackTracesLogString())

        return logString.toString()
    }

    /**
     * Get a minimal log friendly [String] for [Error] error parameters.
     *
     * @param error The [Error] to convert.
     * @return Returns the log friendly [String].
     */
    fun getMinimalErrorLogString(): String {
        val logString = StringBuilder()

        logString.append(getCodeString())
        logString.append(getTypeAndMessageLogString())

        return logString.toString()
    }

    /**
     * Get a minimal [String] for [Error] error parameters.
     *
     * @param error The [Error] to convert.
     * @return Returns the [String].
     */
    fun getMinimalErrorString(): String {
        val logString = StringBuilder()

        logString.append("(").append(getCode()).append(") ")
        logString.append(getType()).append(": ").append(getMessage())

        return logString.toString()
    }

    /**
     * Get a markdown [String] for [Error].
     *
     * @param error The [Error] to convert.
     * @return Returns the markdown [String].
     */
    fun getErrorMarkdownString(): String {
        val markdownString = StringBuilder()

        markdownString.append(MarkdownUtils.getSingleLineMarkdownStringEntry("Error Code", getCode(), "-"))
        markdownString.append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry(
            (if (Errno.TYPE == getType()) "Error Message" else "Error Message (" + getType() + ")"), message, "-"))
        val list = throwablesList
        if (list != null && list.size > 0)
            markdownString.append("\n\n").append(geStackTracesMarkdownString())

        return markdownString.toString()
    }

    fun getCodeString(): String {
        return Logger.getSingleLineLogStringEntry("Error Code", code, "-")
    }

    fun getTypeAndMessageLogString(): String {
        return Logger.getMultiLineLogStringEntry(if (Errno.TYPE == type) "Error Message" else "Error Message (" + type + ")", message, "-")
    }

    fun geStackTracesLogString(): String {
        val list = throwablesList
        return Logger.getStackTracesString("StackTraces:", Logger.getStackTracesStringArray(list))
    }

    fun geStackTracesMarkdownString(): String {
        val list = throwablesList
        return Logger.getStackTracesMarkdownString("StackTraces", Logger.getStackTracesStringArray(list))
    }

    companion object {
        private const val LOG_TAG = "Error"

        /**
         * Log the [Error] and show a toast for the minimal [String] for the [Error].
         *
         * @param context The [Context] for operations.
         * @param logTag The log tag to use for logging.
         * @param error The [Error] to convert.
         */
        @JvmStatic
        fun logErrorAndShowToast(context: Context?, logTag: String, error: Error?) {
            if (error == null) return
            error.logErrorAndShowToast(context, logTag)
        }

        /**
         * Get a log friendly [String] for [Error] error parameters.
         *
         * @param error The [Error] to convert.
         * @return Returns the log friendly [String].
         */
        @JvmStatic
        fun getErrorLogString(error: Error?): String {
            if (error == null) return "null"
            return error.getErrorLogString()
        }

        /**
         * Get a minimal log friendly [String] for [Error] error parameters.
         *
         * @param error The [Error] to convert.
         * @return Returns the log friendly [String].
         */
        @JvmStatic
        fun getMinimalErrorLogString(error: Error?): String {
            if (error == null) return "null"
            return error.getMinimalErrorLogString()
        }

        /**
         * Get a minimal [String] for [Error] error parameters.
         *
         * @param error The [Error] to convert.
         * @return Returns the [String].
         */
        @JvmStatic
        fun getMinimalErrorString(error: Error?): String {
            if (error == null) return "null"
            return error.getMinimalErrorString()
        }

        /**
         * Get a markdown [String] for [Error].
         *
         * @param error The [Error] to convert.
         * @return Returns the markdown [String].
         */
        @JvmStatic
        fun getErrorMarkdownString(error: Error?): String {
            if (error == null) return "null"
            return error.getErrorMarkdownString()
        }
    }
}
