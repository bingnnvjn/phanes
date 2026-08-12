package com.gph.fable.shared.android

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import com.gph.fable.shared.R
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.interact.MessageDialogUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.reflection.ReflectionUtils
import java.lang.reflect.Field
import java.security.MessageDigest

object PackageUtils {

    private const val LOG_TAG = "PackageUtils"

    /**
     * Get the [Context] for the package name with [Context.CONTEXT_RESTRICTED] flags.
     */
    @Nullable
    @JvmStatic
    fun getContextForPackage(@NonNull context: Context, packageName: String?): Context? {
        return getContextForPackage(context, packageName, Context.CONTEXT_RESTRICTED)
    }

    /**
     * Get the [Context] for the package name.
     */
    @Nullable
    @JvmStatic
    fun getContextForPackage(@NonNull context: Context, packageName: String?, flags: Int): Context? {
        return try {
            context.createPackageContext(packageName, flags)
        } catch (e: Exception) {
            Logger.logVerbose(LOG_TAG, "Failed to get \"" + packageName + "\" package context with flags " + flags + ": " + e.message)
            null
        }
    }

    /**
     * Get the [Context] for a package name.
     */
    @Nullable
    @JvmStatic
    fun getContextForPackageOrExitApp(@NonNull context: Context, packageName: String?,
                                      exitAppOnError: Boolean, @Nullable helpUrl: String?): Context? {
        val packageContext = getContextForPackage(context, packageName)

        if (packageContext == null && exitAppOnError) {
            var errorMessage = context.getString(R.string.error_get_package_context_failed_message,
                packageName)
            if (!DataUtils.isNullOrEmpty(helpUrl))
                errorMessage += "\n" + context.getString(R.string.error_get_package_context_failed_help_url_message, helpUrl)
            Logger.logError(LOG_TAG, errorMessage)
            MessageDialogUtils.exitAppWithErrorMessage(context,
                context.getString(R.string.error_get_package_context_failed_title),
                errorMessage)
        }

        return packageContext
    }

    /**
     * Get the [PackageInfo] for the package associated with the `context`.
     */
    @JvmStatic
    fun getPackageInfoForPackage(@NonNull context: Context): PackageInfo? {
        return getPackageInfoForPackage(context, context.packageName)
    }

    /**
     * Get the [PackageInfo] for the package associated with the `context`.
     */
    @Nullable
    @JvmStatic
    fun getPackageInfoForPackage(@NonNull context: Context, flags: Int): PackageInfo? {
        return getPackageInfoForPackage(context, context.packageName, flags)
    }

    /**
     * Get the [PackageInfo] for the package associated with the `packageName`.
     */
    @JvmStatic
    fun getPackageInfoForPackage(@NonNull context: Context, @NonNull packageName: String): PackageInfo? {
        return getPackageInfoForPackage(context, packageName, 0)
    }

    /**
     * Get the [PackageInfo] for the package associated with the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getPackageInfoForPackage(@NonNull context: Context, @NonNull packageName: String, flags: Int): PackageInfo? {
        return try {
            context.packageManager.getPackageInfo(packageName, flags)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the [ApplicationInfo] for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getApplicationInfoForPackage(@NonNull context: Context, @NonNull packageName: String): ApplicationInfo? {
        return getApplicationInfoForPackage(context, packageName, 0)
    }

    /**
     * Get the [ApplicationInfo] for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getApplicationInfoForPackage(@NonNull context: Context, @NonNull packageName: String, flags: Int): ApplicationInfo? {
        return try {
            context.packageManager.getApplicationInfo(packageName, flags)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the `privateFlags` [Field] of the [ApplicationInfo] class.
     */
    @Nullable
    @JvmStatic
    fun getApplicationInfoPrivateFlagsForPackage(@NonNull applicationInfo: ApplicationInfo): Int? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        return try {
            ReflectionUtils.invokeField(ApplicationInfo::class.java, "privateFlags", applicationInfo).value as Int?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get privateFlags field value for ApplicationInfo class", e)
            null
        }
    }

    /**
     * Get the `seInfo` [Field] of the [ApplicationInfo] class.
     */
    @Nullable
    @JvmStatic
    fun getApplicationInfoSeInfoForPackage(@NonNull applicationInfo: ApplicationInfo): String? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        return try {
            ReflectionUtils.invokeField(ApplicationInfo::class.java, if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "seinfo" else "seInfo", applicationInfo).value as String?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get seInfo field value for ApplicationInfo class", e)
            null
        }
    }

    /**
     * Get the `seInfoUser` [Field] of the [ApplicationInfo] class.
     */
    @Nullable
    @JvmStatic
    fun getApplicationInfoSeInfoUserForPackage(@NonNull applicationInfo: ApplicationInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        return try {
            ReflectionUtils.invokeField(ApplicationInfo::class.java, "seInfoUser", applicationInfo).value as String?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get seInfoUser field value for ApplicationInfo class", e)
            null
        }
    }

    /**
     * Get the `privateFlags` [Field] of the [ApplicationInfo] class.
     */
    @Nullable
    @JvmStatic
    fun getApplicationInfoStaticIntFieldValue(@NonNull fieldName: String): Int? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        return try {
            ReflectionUtils.invokeField(ApplicationInfo::class.java, fieldName, null).value as Int?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + fieldName + "\" field value for ApplicationInfo class", e)
            null
        }
    }

    /**
     * Check if the app associated with the `applicationInfo` has a specific flag set.
     */
    @Nullable
    @JvmStatic
    fun isApplicationInfoPrivateFlagSetForPackage(@NonNull flagToCheckName: String, @NonNull applicationInfo: ApplicationInfo): Boolean? {
        val privateFlags = getApplicationInfoPrivateFlagsForPackage(applicationInfo)
        if (privateFlags == null) return null

        val flagToCheck = getApplicationInfoStaticIntFieldValue(flagToCheckName)
        if (flagToCheck == null) return null

        return (0 != (privateFlags and flagToCheck))
    }

    /**
     * Get the app name for the package associated with the `context`.
     */
    @JvmStatic
    fun getAppNameForPackage(@NonNull context: Context): String {
        return getAppNameForPackage(context, context.applicationInfo)
    }

    /**
     * Get the app name for the package associated with the `applicationInfo`.
     */
    @JvmStatic
    fun getAppNameForPackage(@NonNull context: Context, @NonNull applicationInfo: ApplicationInfo): String {
        return applicationInfo.loadLabel(context.packageManager).toString()
    }

    /**
     * Get the package name for the package associated with the `context`.
     */
    @JvmStatic
    fun getPackageNameForPackage(@NonNull context: Context): String {
        return getPackageNameForPackage(context.applicationInfo)
    }

    /**
     * Get the package name for the package associated with the `applicationInfo`.
     */
    @JvmStatic
    fun getPackageNameForPackage(@NonNull applicationInfo: ApplicationInfo): String {
        return applicationInfo.packageName
    }

    /**
     * Get the uid for the package associated with the `context`.
     */
    @JvmStatic
    fun getUidForPackage(@NonNull context: Context): Int {
        return getUidForPackage(context.applicationInfo)
    }

    /**
     * Get the uid for the package associated with the `applicationInfo`.
     */
    @JvmStatic
    fun getUidForPackage(@NonNull applicationInfo: ApplicationInfo): Int {
        return applicationInfo.uid
    }

    /**
     * Get the `targetSdkVersion` for the package associated with the `context`.
     */
    @JvmStatic
    fun getTargetSDKForPackage(@NonNull context: Context): Int {
        return getTargetSDKForPackage(context.applicationInfo)
    }

    /**
     * Get the `targetSdkVersion` for the package associated with the `applicationInfo`.
     */
    @JvmStatic
    fun getTargetSDKForPackage(@NonNull applicationInfo: ApplicationInfo): Int {
        return applicationInfo.targetSdkVersion
    }

    /**
     * Get the base apk path for the package associated with the `context`.
     */
    @JvmStatic
    fun getBaseAPKPathForPackage(@NonNull context: Context): String {
        return getBaseAPKPathForPackage(context.applicationInfo)
    }

    /**
     * Get the base apk path for the package associated with the `applicationInfo`.
     */
    @JvmStatic
    fun getBaseAPKPathForPackage(@NonNull applicationInfo: ApplicationInfo): String {
        return applicationInfo.publicSourceDir
    }

    /**
     * Check if the app associated with the `context` has [ApplicationInfo.FLAG_DEBUGGABLE] set.
     */
    @JvmStatic
    fun isAppForPackageADebuggableBuild(@NonNull context: Context): Boolean {
        return isAppForPackageADebuggableBuild(context.applicationInfo)
    }

    /**
     * Check if the app associated with the `applicationInfo` has [ApplicationInfo.FLAG_DEBUGGABLE] set.
     */
    @JvmStatic
    fun isAppForPackageADebuggableBuild(@NonNull applicationInfo: ApplicationInfo): Boolean {
        return (0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE))
    }

    /**
     * Check if the app associated with the `context` has [ApplicationInfo.FLAG_EXTERNAL_STORAGE] set.
     */
    @JvmStatic
    fun isAppInstalledOnExternalStorage(@NonNull context: Context): Boolean {
        return isAppInstalledOnExternalStorage(context.applicationInfo)
    }

    /**
     * Check if the app associated with the `applicationInfo` has [ApplicationInfo.FLAG_EXTERNAL_STORAGE] set.
     */
    @JvmStatic
    fun isAppInstalledOnExternalStorage(@NonNull applicationInfo: ApplicationInfo): Boolean {
        return (0 != (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE))
    }

    /**
     * Check if the app associated with the `context` has
     * ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE (requestLegacyExternalStorage)
     * set to `true` in app manifest.
     */
    @Nullable
    @JvmStatic
    fun hasRequestedLegacyExternalStorage(@NonNull context: Context): Boolean? {
        return hasRequestedLegacyExternalStorage(context.applicationInfo)
    }

    /**
     * Check if the app associated with the `applicationInfo` has
     * ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE (requestLegacyExternalStorage)
     * set to `true` in app manifest.
     */
    @Nullable
    @JvmStatic
    fun hasRequestedLegacyExternalStorage(@NonNull applicationInfo: ApplicationInfo): Boolean? {
        return isApplicationInfoPrivateFlagSetForPackage("PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE", applicationInfo)
    }

    /**
     * Get the `versionCode` for the package associated with the `context`.
     */
    @Nullable
    @JvmStatic
    fun getVersionCodeForPackage(@NonNull context: Context): Int? {
        return getVersionCodeForPackage(context, context.packageName)
    }

    /**
     * Get the `versionCode` for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getVersionCodeForPackage(@NonNull context: Context, @NonNull packageName: String): Int? {
        return getVersionCodeForPackage(getPackageInfoForPackage(context, packageName))
    }

    /**
     * Get the `versionCode` for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getVersionCodeForPackage(@Nullable packageInfo: PackageInfo?): Int? {
        return packageInfo?.versionCode
    }

    /**
     * Get the `versionName` for the package associated with the `context`.
     */
    @Nullable
    @JvmStatic
    fun getVersionNameForPackage(@NonNull context: Context): String? {
        return getVersionNameForPackage(context, context.packageName)
    }

    /**
     * Get the `versionName` for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getVersionNameForPackage(@NonNull context: Context, @NonNull packageName: String): String? {
        return getVersionNameForPackage(getPackageInfoForPackage(context, packageName))
    }

    /**
     * Get the `versionName` for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getVersionNameForPackage(@Nullable packageInfo: PackageInfo?): String? {
        return packageInfo?.versionName
    }

    /**
     * Get the `SHA-256 digest` of signing certificate for the package associated with the `context`.
     */
    @Nullable
    @JvmStatic
    fun getSigningCertificateSHA256DigestForPackage(@NonNull context: Context): String? {
        return getSigningCertificateSHA256DigestForPackage(context, context.packageName)
    }

    /**
     * Get the `SHA-256 digest` of signing certificate for the `packageName`.
     */
    @Nullable
    @JvmStatic
    fun getSigningCertificateSHA256DigestForPackage(@NonNull context: Context, @NonNull packageName: String): String? {
        return try {
            val packageInfo = getPackageInfoForPackage(context, packageName, PackageManager.GET_SIGNATURES)
            if (packageInfo == null) return null
            DataUtils.bytesToHex(MessageDigest.getInstance("SHA-256").digest(packageInfo.signatures!![0].toByteArray()))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the serial number for the user for the package associated with the `context`.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Nullable
    @JvmStatic
    fun getUserIdForPackage(@NonNull context: Context): Long? {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager?
        if (userManager == null) return null
        return userManager.getSerialNumberForUser(UserHandle.getUserHandleForUid(getUidForPackage(context)))
    }

    /**
     * Check if the current user is the primary user.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @JvmStatic
    fun isCurrentUserThePrimaryUser(@NonNull context: Context): Boolean {
        val userId = getUserIdForPackage(context)
        return userId != null && userId == 0L
    }

    /**
     * Get the profile owner package name for the current user.
     */
    @Nullable
    @JvmStatic
    fun getProfileOwnerPackageNameForUser(@NonNull context: Context): String? {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager?
        if (devicePolicyManager == null) return null
        val activeAdmins = devicePolicyManager.activeAdmins
        if (activeAdmins != null) {
            for (admin in activeAdmins) {
                val packageName = admin.packageName
                if (devicePolicyManager.isProfileOwnerApp(packageName))
                    return packageName
            }
        }
        return null
    }

    /**
     * Get the process id of the main app process of a package.
     */
    @Nullable
    @JvmStatic
    fun getPackagePID(context: Context?, packageName: String): String? {
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        if (activityManager != null) {
            val processInfos = activityManager.runningAppProcesses
            if (processInfos != null) {
                for (processInfo in processInfos) {
                    if (processInfo.processName == packageName)
                        return processInfo.pid.toString()
                }
            }
        }
        return null
    }

    /**
     * Check if app is installed and enabled. This can be used by external apps that don't
     * share `sharedUserId` with the app.
     */
    @JvmStatic
    fun isAppInstalled(@NonNull context: Context, appName: String, packageName: String): String? {
        val errmsg: String?

        val applicationInfo = getApplicationInfoForPackage(context, packageName)
        val isAppEnabled = (applicationInfo != null && applicationInfo.enabled)

        // If app is not installed or is disabled
        if (!isAppEnabled)
            errmsg = context.getString(R.string.error_app_not_installed_or_disabled_warning, appName, packageName)
        else
            errmsg = null

        return errmsg
    }

    /** Wrapper for [setComponentState] with `alwaysShowToast` `true`. */
    @JvmStatic
    fun setComponentState(@NonNull context: Context, @NonNull packageName: String,
                          @NonNull className: String, newState: Boolean, toastString: String?,
                          showErrorMessage: Boolean): String? {
        return setComponentState(context, packageName, className, newState, toastString, showErrorMessage, true)
    }

    /**
     * Enable or disable a [ComponentName] with a call to
     * [PackageManager.setComponentEnabledSetting].
     */
    @Nullable
    @JvmStatic
    fun setComponentState(@NonNull context: Context, @NonNull packageName: String,
                          @NonNull className: String, newState: Boolean, toastString: String?,
                          alwaysShowToast: Boolean, showErrorMessage: Boolean): String? {
        return try {
            val packageManager = context.packageManager
            if (packageManager != null) {
                var toastString = toastString
                if (toastString != null && alwaysShowToast) {
                    Logger.showToast(context, toastString, true)
                    toastString = null
                }

                val currentlyDisabled = PackageUtils.isComponentDisabled(context, packageName, className, false)
                if (currentlyDisabled == null)
                    throw UnsupportedOperationException("Failed to find if component currently disabled")

                val setState: Boolean? = when {
                    newState && currentlyDisabled -> true
                    !newState && !currentlyDisabled -> false
                    else -> null
                }

                if (setState == null) return null

                if (toastString != null) Logger.showToast(context, toastString, true)
                val componentName = ComponentName(packageName, className)
                packageManager.setComponentEnabledSetting(componentName,
                    if (setState) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
            }
            null
        } catch (e: Exception) {
            val errmsg = context.getString(
                if (newState) R.string.error_enable_component_failed else R.string.error_disable_component_failed,
                packageName, className) + ": " + e.message
            if (showErrorMessage)
                Logger.showToast(context, errmsg, true)
            errmsg
        }
    }

    /**
     * Check if state of a [ComponentName] is [PackageManager.COMPONENT_ENABLED_STATE_DISABLED]
     * with a call to [PackageManager.getComponentEnabledSetting].
     */
    @JvmStatic
    fun isComponentDisabled(@NonNull context: Context, @NonNull packageName: String,
                            @NonNull className: String, logErrorMessage: Boolean): Boolean? {
        return try {
            val packageManager = context.packageManager
            if (packageManager != null) {
                val componentName = ComponentName(packageName, className)
                // Will throw IllegalArgumentException: Unknown component: ComponentInfo{} if app
                // for context is not installed or component does not exist.
                packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                null
            }
        } catch (e: Exception) {
            if (logErrorMessage)
                Logger.logStackTraceWithMessage(LOG_TAG, context.getString(R.string.error_get_component_state_failed, packageName, className), e)
            null
        }
    }

    /**
     * Check if an [android.app.Activity] [ComponentName] can be called by calling
     * [PackageManager.queryIntentActivities].
     */
    @JvmStatic
    fun doesActivityComponentExist(@NonNull context: Context, @NonNull packageName: String,
                                   @NonNull className: String, flags: Int): Boolean {
        return try {
            val packageManager = context.packageManager
            if (packageManager != null) {
                val intent = Intent()
                intent.setClassName(packageName, className)
                packageManager.queryIntentActivities(intent, flags).size > 0
            } else {
                false
            }
        } catch (e: Exception) {
            // ignore
            false
        }
    }
}
