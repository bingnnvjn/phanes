package com.gph.fable.shared.android

import android.Manifest
import android.content.Context
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.shell.command.environment.AndroidShellEnvironment
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.runner.app.AppShell

/**
 * Utils for phantom processes added in android 12.
 */
object PhantomProcessUtils {

    private const val LOG_TAG = "PhantomProcessUtils"

    /**
     * If feature flag set to false, then will disable trimming of phantom process and processes using
     * excessive CPU.
     */
    const val FEATURE_FLAG_SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS = "settings_enable_monitor_phantom_procs"

    /**
     * Maximum number of allowed phantom processes.
     */
    const val KEY_MAX_PHANTOM_PROCESSES = "max_phantom_processes"

    /**
     * Whether or not syncs (bulk set operations) for DeviceConfig are disabled currently.
     */
    const val SETTINGS_GLOBAL_DEVICE_CONFIG_SYNC_DISABLED = "device_config_sync_disabled"

    /**
     * Get [FEATURE_FLAG_SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS] feature flag value.
     */
    @NonNull
    @JvmStatic
    fun getFeatureFlagMonitorPhantomProcsValueString(@NonNull context: Context): FeatureFlagUtils.FeatureFlagValue {
        return FeatureFlagUtils.getFeatureFlagValueString(context, FEATURE_FLAG_SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS)
    }

    /**
     * Get currently enforced ActivityManagerConstants MAX_PHANTOM_PROCESSES value, defaults to 32.
     */
    @Nullable
    @JvmStatic
    fun getActivityManagerMaxPhantomProcesses(@NonNull context: Context): Int? {
        if (!PermissionUtils.checkPermissions(context, arrayOf(Manifest.permission.DUMP, Manifest.permission.PACKAGE_USAGE_STATS))) {
            return null
        }

        // Dumpsys logs the currently enforced MAX_PHANTOM_PROCESSES value and not the device config setting.
        val script = "/system/bin/dumpsys activity settings | /system/bin/grep -iE '^[\\t ]+" + KEY_MAX_PHANTOM_PROCESSES + "=[0-9]+$' | /system/bin/cut -d = -f2"
        val executionCommand = ExecutionCommand(-1, "/system/bin/sh", null,
            script + "\n", "/", ExecutionCommand.Runner.APP_SHELL.getName(), true)
        executionCommand.commandLabel = " ActivityManager " + KEY_MAX_PHANTOM_PROCESSES + " Command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val appShell = AppShell.execute(context, executionCommand, null, AndroidShellEnvironment(), null, true)
        val stderrSet = !executionCommand.resultData.stderr.toString().isEmpty()
        if (appShell == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode != 0 || stderrSet) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        return try {
            executionCommand.resultData.stdout.toString().trim().toInt()
        } catch (e: NumberFormatException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "The " + executionCommand.commandLabel + " did not return a valid integer", e)
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            null
        }
    }

    /**
     * Get [SETTINGS_GLOBAL_DEVICE_CONFIG_SYNC_DISABLED] settings value.
     */
    @Nullable
    @JvmStatic
    fun getSettingsGlobalDeviceConfigSyncDisabled(@NonNull context: Context): Int? {
        return SettingsProviderUtils.getSettingsValue(context, SettingsProviderUtils.SettingNamespace.GLOBAL,
            SettingsProviderUtils.SettingType.INT, SETTINGS_GLOBAL_DEVICE_CONFIG_SYNC_DISABLED, null) as Int?
    }
}
