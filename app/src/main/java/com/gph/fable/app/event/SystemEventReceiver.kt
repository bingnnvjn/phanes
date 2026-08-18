package com.gph.fable.app.event

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.gph.fable.shared.data.IntentUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.file.FableFileUtils
import com.gph.fable.shared.termux.shell.FableShellManager
import com.gph.fable.shared.termux.shell.command.environment.FableShellEnvironment

open class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        when (val action = intent.action) {
            null -> return
            Intent.ACTION_BOOT_COMPLETED -> onActionBootCompleted(context, intent)
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED -> onActionPackageUpdated(context, intent)
            else -> Logger.logError(LOG_TAG, "Invalid action \"$action\" passed to $LOG_TAG")
        }
    }

    @Synchronized
    fun onActionBootCompleted(context: Context, intent: Intent) {
        FableShellManager.onActionBootCompleted(context, intent)
    }

    @Synchronized
    fun onActionPackageUpdated(context: Context, intent: Intent) {
        val data = intent.data
        if (data != null && FableUtils.isUriDataForFablePluginPackage(data)) {
            Logger.logDebug(
                LOG_TAG,
                intent.action!!.replace(Regex("^android.intent.action."), "") +
                    " event received for \"" +
                    data.toString().replace(Regex("^package:"), "") + "\""
            )
            if (FableFileUtils.isFableFilesDirectoryAccessible(context, false, false) == null) {
                FableShellEnvironment.writeEnvironmentToFile(context)
            }
        }
    }

    companion object {
        private var mInstance: SystemEventReceiver? = null
        private const val LOG_TAG = "SystemEventReceiver"

        @JvmStatic
        fun getInstance(): SystemEventReceiver = synchronized(SystemEventReceiver::class.java) {
            mInstance ?: SystemEventReceiver().also { mInstance = it }
        }

        @JvmStatic
        fun registerPackageUpdateEvents(context: Context) {
            synchronized(SystemEventReceiver::class.java) {
                val intentFilter = IntentFilter()
                intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
                intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
                intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
                intentFilter.addDataScheme("package")
                context.registerReceiver(getInstance(), intentFilter)
            }
        }

        @JvmStatic
        fun unregisterPackageUpdateEvents(context: Context) {
            synchronized(SystemEventReceiver::class.java) {
                context.unregisterReceiver(getInstance())
            }
        }
    }
}
