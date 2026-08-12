package com.gph.fable.shared.android

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.logger.Logger

object ProcessUtils {

    const val LOG_TAG = "ProcessUtils"

    /**
     * Get the app process name for a pid with a call to [ActivityManager.getRunningAppProcesses].
     */
    @Nullable
    @JvmStatic
    fun getAppProcessNameForPid(@NonNull context: Context, pid: Int): String? {
        if (pid < 0) return null

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        if (activityManager == null) return null
        return try {
            val runningApps = activityManager.runningAppProcesses
            if (runningApps == null) {
                return null
            }
            for (procInfo in runningApps) {
                if (procInfo.pid == pid) {
                    return procInfo.processName
                }
            }
            null
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get app process name for pid " + pid, e)
            null
        }
    }
}
