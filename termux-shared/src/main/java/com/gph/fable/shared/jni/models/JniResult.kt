package com.gph.fable.shared.jni.models

import androidx.annotation.Keep
import com.gph.fable.shared.logger.Logger

@Keep
class JniResult @JvmOverloads constructor(
    @JvmField var retval: Int,
    @JvmField var errno: Int,
    @JvmField var errmsg: String?,
    @JvmField var intData: Int = 0
) {
    constructor(message: String, throwable: Throwable) :
        this(-1, 0, Logger.getMessageAndStackTraceString(message, throwable))

    fun getErrorString(): String {
        val result = StringBuilder()
        result.append(Logger.getSingleLineLogStringEntry("Retval", retval, "-"))
        if (errno != 0) result.append("\n").append(Logger.getSingleLineLogStringEntry("Errno", errno, "-"))
        if (!errmsg.isNullOrEmpty()) result.append("\n").append(Logger.getMultiLineLogStringEntry("Errmsg", errmsg, "-"))
        return result.toString()
    }

    companion object {
        @JvmStatic
        fun getErrorString(result: JniResult?): String = result?.getErrorString() ?: "null"
    }
}
