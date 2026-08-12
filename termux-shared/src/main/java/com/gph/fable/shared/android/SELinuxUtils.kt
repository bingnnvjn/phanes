package com.gph.fable.shared.android

import android.annotation.SuppressLint
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.reflection.ReflectionUtils

object SELinuxUtils {

    const val ANDROID_OS_SELINUX_CLASS = "android.os.SELinux"

    private const val LOG_TAG = "SELinuxUtils"

    /**
     * Gets the security context of the current process.
     */
    @Nullable
    @JvmStatic
    fun getContext(): String? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        val methodName = "getContext"
        return try {
            @SuppressLint("PrivateApi")
            val clazz = Class.forName(ANDROID_OS_SELINUX_CLASS)
            val method = ReflectionUtils.getDeclaredMethod(clazz, methodName)
            if (method == null) {
                Logger.logError(LOG_TAG, "Failed to get " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class")
                return null
            }

            ReflectionUtils.invokeMethod(method, null).value as String?
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class", e)
            null
        }
    }

    /**
     * Get the security context of a given process id.
     */
    @Nullable
    @JvmStatic
    fun getPidContext(pid: Int): String? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        val methodName = "getPidContext"
        return try {
            @SuppressLint("PrivateApi")
            val clazz = Class.forName(ANDROID_OS_SELINUX_CLASS)
            val method = ReflectionUtils.getDeclaredMethod(clazz, methodName, Int::class.javaPrimitiveType!!)
            if (method == null) {
                Logger.logError(LOG_TAG, "Failed to get " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class")
                return null
            }

            ReflectionUtils.invokeMethod(method, null, pid).value as String?
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class", e)
            null
        }
    }

    /**
     * Get the security context of a file object.
     */
    @Nullable
    @JvmStatic
    fun getFileContext(@NonNull path: String): String? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        val methodName = "getFileContext"
        return try {
            @SuppressLint("PrivateApi")
            val clazz = Class.forName(ANDROID_OS_SELINUX_CLASS)
            val method = ReflectionUtils.getDeclaredMethod(clazz, methodName, String::class.java)
            if (method == null) {
                Logger.logError(LOG_TAG, "Failed to get " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class")
                return null
            }

            ReflectionUtils.invokeMethod(method, null, path).value as String?
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class", e)
            null
        }
    }
}
