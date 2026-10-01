package com.gph.fable.shared.errors

import android.app.Activity
import androidx.annotation.NonNull
import com.gph.fable.shared.logger.Logger
import java.util.Arrays
import java.util.Collections
import java.util.HashMap

/** The [Class] that defines error messages and codes. */
open class Errno(@NonNull val type: String, val code: Int, @NonNull val message: String) {

    init {
        map.put(type + ":" + code, this)
    }

    @NonNull
    override fun toString(): String {
        return "type=" + type + ", code=" + code + ", message=\"" + message + "\""
    }

    /**
     * Get the [Errno] of a specific type and code.
     *
     * @param type The unique type of the [Errno].
     * @param code The unique code of the [Errno].
     */
    fun getError(): Error {
        return Error(type, code, message)
    }

    fun getError(vararg args: Any?): Error {
        return try {
            Error(type, code, String.format(message, *args))
        } catch (e: Exception) {
            Logger.logWarn(
                LOG_TAG,
                "Exception raised while calling String.format() for error message of errno " + this + " with args" + Arrays.toString(args) + "\n" + e.message
            )
            // Return unformatted message as a backup
            Error(type, code, message + ": " + Arrays.toString(args))
        }
    }

    fun getError(throwable: Throwable?, vararg args: Any?): Error {
        return if (throwable == null)
            getError(*args)
        else
            getError(Collections.singletonList(throwable), *args)
    }

    fun getError(throwablesList: List<Throwable>?, vararg args: Any?): Error {
        return try {
            if (throwablesList == null)
                Error(type, code, String.format(message, *args))
            else
                Error(type, code, String.format(message, *args), throwablesList)
        } catch (e: Exception) {
            Logger.logWarn(
                LOG_TAG,
                "Exception raised while calling String.format() for error message of errno " + this + " with args" + Arrays.toString(args) + "\n" + e.message
            )
            // Return unformatted message as a backup
            Error(type, code, message + ": " + Arrays.toString(args), throwablesList)
        }
    }

    fun equalsErrorTypeAndCode(error: Error?): Boolean {
        if (error == null) return false
        return type == error.getType() && code == error.getCode()
    }

    companion object {
        private val map = HashMap<String, Errno>()

        const val TYPE = "Error"

        @JvmField
        val ERRNO_SUCCESS = Errno(TYPE, Activity.RESULT_OK, "Success")
        @JvmField
        val ERRNO_CANCELLED = Errno(TYPE, Activity.RESULT_CANCELED, "Cancelled")
        @JvmField
        val ERRNO_MINOR_FAILURES = Errno(TYPE, Activity.RESULT_FIRST_USER, "Minor failure")
        @JvmField
        val ERRNO_FAILED = Errno(TYPE, Activity.RESULT_FIRST_USER + 1, "Failed")

        private const val LOG_TAG = "Errno"

        /**
         * Get the [Errno] of a specific type and code.
         *
         * @param type The unique type of the [Errno].
         * @param code The unique code of the [Errno].
         */
        @JvmStatic
        fun valueOf(type: String?, code: Int?): Errno? {
            if (type == null || type.isEmpty() || code == null) return null
            return map[type + ":" + code]
        }
    }
}
