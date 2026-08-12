package com.gph.fable.shared.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.common.base.Joiner
import com.gph.fable.shared.R
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.errors.FunctionErrno
import com.gph.fable.shared.activity.ActivityUtils
import com.gph.fable.shared.termux.TermuxConstants
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

object PermissionUtils {

    const val REQUEST_GRANT_STORAGE_PERMISSION = 1000

    const val REQUEST_DISABLE_BATTERY_OPTIMIZATIONS = 2000
    const val REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION = 2001

    /** 工单 05：通知运行时权限（API 33+，Android 13 引入 POST_NOTIFICATIONS）。 */
    const val REQUEST_NOTIFICATION_PERMISSION = 1001

    private const val LOG_TAG = "PermissionUtils"

    /** 是否已向用户弹过一次通知权限请求（尊重用户选择，不反复打扰）。 */
    private const val PREF_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked_before"

    /**
     * 通知权限请求策略（纯逻辑，供单测）。
     *
     * 规则：已授权 → 不再请求；未授权但已问过 → 不再自动弹窗（用户可去系统设置开启）；
     * 未授权且未问过 → 请求一次。
     *
     * @return 是否应该请求通知权限。
     */
    @JvmStatic
    fun shouldRequestNotificationPermission(permissionGranted: Boolean, askedBefore: Boolean): Boolean {
        return !permissionGranted && !askedBefore
    }

    /**
     * 检查通知权限是否已授予。API 33 以下不存在该运行时权限，视为已授予。
     */
    @JvmStatic
    fun isNotificationPermissionGranted(@NonNull context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 请求通知权限（仅 API 33+ 且满足 [shouldRequestNotificationPermission] 时弹窗）。
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @JvmStatic
    fun requestNotificationPermission(@NonNull context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permissionGranted = isNotificationPermissionGranted(context)
        if (permissionGranted) return
        if (!shouldRequestNotificationPermission(permissionGranted, isNotificationPermissionAskedBefore(context))) return

        if (requestPermission(context, Manifest.permission.POST_NOTIFICATIONS, REQUEST_NOTIFICATION_PERMISSION)) {
            markNotificationPermissionAsked(context)
        }
    }

    /** 是否已向用户弹过一次通知权限请求（记录在共享偏好中，跨启动保持）。 */
    @JvmStatic
    fun isNotificationPermissionAskedBefore(@NonNull context: Context): Boolean {
        val preferences = getNotificationPermissionPreferences(context)
        return preferences.getBoolean(PREF_NOTIFICATION_PERMISSION_ASKED, false)
    }

    /** 记录"已弹过通知权限请求"。 */
    @JvmStatic
    fun markNotificationPermissionAsked(@NonNull context: Context) {
        val preferences = getNotificationPermissionPreferences(context)
        preferences.edit().putBoolean(PREF_NOTIFICATION_PERMISSION_ASKED, true).apply()
    }

    private fun getNotificationPermissionPreferences(@NonNull context: Context): SharedPreferences {
        return SharedPreferenceUtils.getPrivateSharedPreferences(context,
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    }

    /**
     * Check if app has been granted the required permission.
     */
    @JvmStatic
    fun checkPermission(@NonNull context: Context, @NonNull permission: String): Boolean {
        return checkPermissions(context, arrayOf(permission))
    }

    /**
     * Check if app has been granted the required permissions.
     */
    @JvmStatic
    fun checkPermissions(@NonNull context: Context, @NonNull permissions: Array<String>): Boolean {
        // checkSelfPermission may return true for permissions not even requested
        val permissionsNotRequested = getPermissionsNotRequested(context, permissions)
        if (permissionsNotRequested.size > 0) {
            Logger.logError(LOG_TAG,
                context.getString(R.string.error_attempted_to_check_for_permissions_not_requested,
                    Joiner.on(", ").join(permissionsNotRequested)))
            return false
        }

        for (permission in permissions) {
            val result = ContextCompat.checkSelfPermission(context, permission)
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    /**
     * Request user to grant required permissions to the app.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @JvmStatic
    fun requestPermission(@NonNull context: Context, @NonNull permission: String, requestCode: Int): Boolean {
        return requestPermissions(context, arrayOf(permission), requestCode)
    }

    /**
     * Request user to grant required permissions to the app.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @JvmStatic
    fun requestPermissions(@NonNull context: Context, @NonNull permissions: Array<String>, requestCode: Int): Boolean {
        val permissionsNotRequested = getPermissionsNotRequested(context, permissions)
        if (permissionsNotRequested.size > 0) {
            Logger.logErrorAndShowToast(context, LOG_TAG,
                context.getString(R.string.error_attempted_to_ask_for_permissions_not_requested,
                    Joiner.on(", ").join(permissionsNotRequested)))
            return false
        }

        for (permission in permissions) {
            val result = ContextCompat.checkSelfPermission(context, permission)
            // If at least one permission not granted
            if (result != PackageManager.PERMISSION_GRANTED) {
                Logger.logInfo(LOG_TAG, "Requesting Permissions: " + Arrays.toString(permissions))

                try {
                    if (context is AppCompatActivity)
                        context.requestPermissions(permissions, requestCode)
                    else if (context is Activity)
                        context.requestPermissions(permissions, requestCode)
                    else {
                        Error.logErrorAndShowToast(context, LOG_TAG,
                            FunctionErrno.ERRNO_PARAMETER_NOT_INSTANCE_OF.getError("context", "requestPermissions", "Activity or AppCompatActivity"))
                        return false
                    }
                } catch (e: Exception) {
                    val errmsg = context.getString(R.string.error_failed_to_request_permissions, requestCode, Arrays.toString(permissions))
                    Logger.logStackTraceWithMessage(LOG_TAG, errmsg, e)
                    Logger.showToast(context, errmsg + "\n" + e.message, true)
                    return false
                }

                break
            }
        }

        return true
    }

    /**
     * Check if app has requested the required permission in the manifest.
     */
    @JvmStatic
    fun isPermissionRequested(@NonNull context: Context, @NonNull permission: String): Boolean {
        return getPermissionsNotRequested(context, arrayOf(permission)).size == 0
    }

    /**
     * Check if app has requested the required permissions or not in the manifest.
     */
    @NonNull
    @JvmStatic
    fun getPermissionsNotRequested(@NonNull context: Context, @NonNull permissions: Array<String>): List<String> {
        val permissionsNotRequested = ArrayList<String>()
        Collections.addAll(permissionsNotRequested, *permissions)

        val packageInfo = PackageUtils.getPackageInfoForPackage(context, PackageManager.GET_PERMISSIONS)
        if (packageInfo == null) {
            return permissionsNotRequested
        }

        val requestedPermissions = packageInfo.requestedPermissions
        // If no permissions are requested, then nothing to check
        if (requestedPermissions == null || requestedPermissions.size == 0)
            return permissionsNotRequested

        val requestedPermissionsList = Arrays.asList(*requestedPermissions)
        for (permission in permissions) {
            if (requestedPermissionsList.contains(permission)) {
                permissionsNotRequested.remove(permission)
            }
        }

        return permissionsNotRequested
    }

    /**
     * If path is under primary external storage directory and storage permission is missing,
     * then legacy or manage external storage permission will be requested.
     */
    @SuppressLint("SdCardPath")
    @JvmStatic
    fun checkAndRequestLegacyOrManageExternalStoragePermissionIfPathOnPrimaryExternalStorage(
        @NonNull context: Context, filePath: String?, requestCode: Int,
        prioritizeManageExternalStoragePermission: Boolean, showErrorMessage: Boolean
    ): Boolean {
        // If path is under primary external storage directory, then check for missing permissions.
        if (!FileUtils.isPathInDirPaths(filePath,
                Arrays.asList(Environment.getExternalStorageDirectory().absolutePath, "/sdcard"), true))
            return true

        return checkAndRequestLegacyOrManageExternalStoragePermission(context, requestCode, prioritizeManageExternalStoragePermission, showErrorMessage)
    }

    /**
     * Check if legacy or manage external storage permissions has been granted.
     */
    @JvmStatic
    fun checkAndRequestLegacyOrManageExternalStoragePermission(@NonNull context: Context,
                                                               requestCode: Int,
                                                               prioritizeManageExternalStoragePermission: Boolean,
                                                               showErrorMessage: Boolean): Boolean {
        Logger.logVerbose(LOG_TAG, "Checking storage permission")

        val errmsg: String
        var requestLegacyStoragePermission: Boolean? = null

        if (prioritizeManageExternalStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requestLegacyStoragePermission = false

        if (requestLegacyStoragePermission == null)
            requestLegacyStoragePermission = isLegacyExternalStoragePossible(context)

        val checkIfHasRequestedLegacyExternalStorage = checkIfHasRequestedLegacyExternalStorage(context)

        Logger.logVerbose(LOG_TAG, "prioritizeManageExternalStoragePermission=" + prioritizeManageExternalStoragePermission +
            ", requestLegacyStoragePermission=" + requestLegacyStoragePermission +
            ", checkIfHasRequestedLegacyExternalStorage=" + checkIfHasRequestedLegacyExternalStorage)

        if (requestLegacyStoragePermission == true && checkIfHasRequestedLegacyExternalStorage) {
            // Check if requestLegacyExternalStorage is set to true in app manifest
            if (!hasRequestedLegacyExternalStorage(context, showErrorMessage))
                return false
        }

        if (checkStoragePermission(context, requestLegacyStoragePermission == true)) {
            return true
        }

        errmsg = context.getString(R.string.msg_storage_permission_not_granted)
        Logger.logError(LOG_TAG, errmsg)
        if (showErrorMessage)
            Logger.showToast(context, errmsg, false)

        if (requestCode < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return false

        if (requestLegacyStoragePermission == true || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestLegacyStorageExternalPermission(context, requestCode)
        } else {
            requestManageStorageExternalPermission(context, requestCode)
        }

        return false
    }

    /**
     * Check if app has been granted storage permission.
     */
    @JvmStatic
    fun checkStoragePermission(@NonNull context: Context, checkLegacyStoragePermission: Boolean): Boolean {
        return if (checkLegacyStoragePermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            checkPermissions(context,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else {
            Environment.isExternalStorageManager()
        }
    }

    /**
     * Request user to grant [Manifest.permission.READ_EXTERNAL_STORAGE] and
     * [Manifest.permission.WRITE_EXTERNAL_STORAGE] permissions to the app.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @JvmStatic
    fun requestLegacyStorageExternalPermission(@NonNull context: Context, requestCode: Int): Boolean {
        Logger.logInfo(LOG_TAG, "Requesting legacy external storage permission")
        return requestPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, requestCode)
    }

    /** Wrapper for [requestManageStorageExternalPermission]. */
    @RequiresApi(api = Build.VERSION_CODES.R)
    @JvmStatic
    fun requestManageStorageExternalPermission(@NonNull context: Context): Error? {
        return requestManageStorageExternalPermission(context, -1)
    }

    /**
     * Request user to grant [Manifest.permission.MANAGE_EXTERNAL_STORAGE] permission to the app.
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    @JvmStatic
    fun requestManageStorageExternalPermission(@NonNull context: Context, requestCode: Int): Error? {
        Logger.logInfo(LOG_TAG, "Requesting manage external storage permission")

        var intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.addCategory("android.intent.category.DEFAULT")
        intent.setData(Uri.parse("package:" + context.packageName))

        // Flag must not be passed for activity contexts, otherwise onActivityResult() will not be called with permission grant result.
        // Flag must be passed for non-activity contexts like services, otherwise "Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag" exception will be raised.
        if (context !is Activity)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val error: Error?
        if (requestCode >= 0)
            error = ActivityUtils.startActivityForResult(context, requestCode, intent, true, false)
        else
            error = ActivityUtils.startActivity(context, intent, true, false)

        // Use fallback if matching Activity did not exist for ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION.
        if (error != null) {
            intent = Intent()
            intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            return if (requestCode >= 0)
                ActivityUtils.startActivityForResult(context, requestCode, intent)
            else
                ActivityUtils.startActivity(context, intent)
        }

        return null
    }

    /**
     * If app is targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) or
     * higher, then [android.R.attr.requestLegacyExternalStorage] attribute is ignored.
     */
    @JvmStatic
    fun isLegacyExternalStoragePossible(@NonNull context: Context): Boolean {
        return !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            PackageUtils.getTargetSDKForPackage(context) >= Build.VERSION_CODES.R)
    }

    /**
     * Return whether it should be checked if app has set
     * [android.R.attr.requestLegacyExternalStorage] attribute to `true`.
     */
    @JvmStatic
    fun checkIfHasRequestedLegacyExternalStorage(@NonNull context: Context): Boolean {
        val targetSdkVersion = PackageUtils.getTargetSDKForPackage(context)

        return when {
            targetSdkVersion >= Build.VERSION_CODES.R -> Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
            targetSdkVersion == Build.VERSION_CODES.Q -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            else -> false
        }
    }

    /**
     * Call to [Environment.isExternalStorageLegacy] will not return the actual value defined
     * in app manifest for [android.R.attr.requestLegacyExternalStorage] attribute.
     */
    @JvmStatic
    fun hasRequestedLegacyExternalStorage(@NonNull context: Context, showErrorMessage: Boolean): Boolean {
        val errmsg: String
        val hasRequestedLegacyExternalStorage = PackageUtils.hasRequestedLegacyExternalStorage(context)
        if (hasRequestedLegacyExternalStorage != null && !hasRequestedLegacyExternalStorage) {
            errmsg = context.getString(R.string.error_has_not_requested_legacy_external_storage,
                context.packageName, PackageUtils.getTargetSDKForPackage(context), Build.VERSION.SDK_INT)
            Logger.logError(LOG_TAG, errmsg)
            if (showErrorMessage)
                Logger.showToast(context, errmsg, true)
            return false
        }

        return true
    }

    /**
     * Check if [Manifest.permission.SYSTEM_ALERT_WINDOW] permission has been granted.
     */
    @JvmStatic
    fun checkDisplayOverOtherAppsPermission(@NonNull context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(context)
        else
            true
    }

    /** Wrapper for [requestDisplayOverOtherAppsPermission]. */
    @JvmStatic
    fun requestDisplayOverOtherAppsPermission(@NonNull context: Context): Error? {
        return requestDisplayOverOtherAppsPermission(context, -1)
    }

    /**
     * Request user to grant [Manifest.permission.SYSTEM_ALERT_WINDOW] permission to the app.
     */
    @JvmStatic
    fun requestDisplayOverOtherAppsPermission(@NonNull context: Context, requestCode: Int): Error? {
        Logger.logInfo(LOG_TAG, "Requesting display over apps permission")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return null

        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.setData(Uri.parse("package:" + context.packageName))

        // Flag must not be passed for activity contexts, otherwise onActivityResult() will not be called with permission grant result.
        // Flag must be passed for non-activity contexts like services, otherwise "Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag" exception will be raised.
        if (context !is Activity)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (requestCode >= 0)
            ActivityUtils.startActivityForResult(context, requestCode, intent)
        else
            ActivityUtils.startActivity(context, intent)
    }

    /**
     * Check if running on sdk 29 (android 10) or higher and [Manifest.permission.SYSTEM_ALERT_WINDOW]
     * permission has been granted or not.
     */
    @JvmStatic
    fun validateDisplayOverOtherAppsPermissionForPostAndroid10(@NonNull context: Context, logResults: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        return if (!checkDisplayOverOtherAppsPermission(context)) {
            if (logResults)
                Logger.logWarn(LOG_TAG, context.packageName + " does not have Display over other apps (SYSTEM_ALERT_WINDOW) permission")
            false
        } else {
            if (logResults)
                Logger.logDebug(LOG_TAG, context.packageName + " already has Display over other apps (SYSTEM_ALERT_WINDOW) permission")
            true
        }
    }

    /**
     * Check if [Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] permission has been granted.
     */
    @JvmStatic
    fun checkIfBatteryOptimizationsDisabled(@NonNull context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else
            true
    }

    /** Wrapper for [requestDisableBatteryOptimizations]. */
    @JvmStatic
    fun requestDisableBatteryOptimizations(@NonNull context: Context): Error? {
        return requestDisableBatteryOptimizations(context, -1)
    }

    /**
     * Request user to grant [Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     * permission to the app.
     */
    @SuppressLint("BatteryLife")
    @JvmStatic
    fun requestDisableBatteryOptimizations(@NonNull context: Context, requestCode: Int): Error? {
        Logger.logInfo(LOG_TAG, "Requesting to disable battery optimizations")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return null

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.setData(Uri.parse("package:" + context.packageName))

        // Flag must not be passed for activity contexts, otherwise onActivityResult() will not be called with permission grant result.
        // Flag must be passed for non-activity contexts like services, otherwise "Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag" exception will be raised.
        if (context !is Activity)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (requestCode >= 0)
            ActivityUtils.startActivityForResult(context, requestCode, intent)
        else
            ActivityUtils.startActivity(context, intent)
    }
}
