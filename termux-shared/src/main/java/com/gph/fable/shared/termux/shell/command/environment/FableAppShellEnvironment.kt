package com.gph.fable.shared.termux.shell.command.environment

import android.content.Context
import android.os.Build
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.android.SELinuxUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.shell.command.environment.ShellEnvironmentUtils
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.shell.am.FableAmSocketServer

object FableAppShellEnvironment {
    @JvmField var fableAppEnvironment: HashMap<String, String>? = null
    const val TERMUX_ENV__S_ROOT = "TERMUX_"
    const val TERMUX_ENV__S_TERMUX = "${TERMUX_ENV__S_ROOT}_"
    const val ENV_TERMUX_VERSION = "${TermuxConstants.TERMUX_ENV_PREFIX_ROOT}_VERSION"
    const val TERMUX_APP_ENV_PREFIX = "${TermuxConstants.TERMUX_ENV_PREFIX_ROOT}_APP__"
    const val ENV_TERMUX_APP__APP_VERSION_NAME = "${TERMUX_APP_ENV_PREFIX}APP_VERSION_NAME"
    const val ENV_TERMUX_APP__APP_VERSION_CODE = "${TERMUX_APP_ENV_PREFIX}APP_VERSION_CODE"
    const val ENV_TERMUX_APP__PACKAGE_NAME = "${TERMUX_APP_ENV_PREFIX}PACKAGE_NAME"
    const val ENV_TERMUX_APP__PID = "${TERMUX_APP_ENV_PREFIX}PID"
    const val ENV_TERMUX__UID = "${TERMUX_ENV__S_TERMUX}UID"
    const val ENV_TERMUX_APP__TARGET_SDK = "${TERMUX_APP_ENV_PREFIX}TARGET_SDK"
    const val ENV_TERMUX_APP__IS_DEBUGGABLE_BUILD = "${TERMUX_APP_ENV_PREFIX}IS_DEBUGGABLE_BUILD"
    const val ENV_TERMUX_APP__APK_RELEASE = "${TERMUX_APP_ENV_PREFIX}APK_RELEASE"
    const val ENV_TERMUX_APP__APK_FILE = "${TERMUX_APP_ENV_PREFIX}APK_FILE"
    const val ENV_TERMUX_APP__IS_INSTALLED_ON_EXTERNAL_STORAGE = "${TERMUX_APP_ENV_PREFIX}IS_INSTALLED_ON_EXTERNAL_STORAGE"
    const val ENV_TERMUX_APP__SE_FILE_CONTEXT = "${TERMUX_APP_ENV_PREFIX}SE_FILE_CONTEXT"
    const val ENV_TERMUX_APP__SE_INFO = "${TERMUX_APP_ENV_PREFIX}SE_INFO"
    const val ENV_TERMUX__SE_PROCESS_CONTEXT = "${TERMUX_ENV__S_TERMUX}SE_PROCESS_CONTEXT"
    const val ENV_TERMUX__USER_ID = "${TERMUX_ENV__S_TERMUX}USER_ID"
    const val ENV_TERMUX__PROFILE_OWNER = "${TERMUX_ENV__S_TERMUX}PROFILE_OWNER"
    const val ENV_TERMUX_APP__PACKAGE_MANAGER = "${TERMUX_APP_ENV_PREFIX}PACKAGE_MANAGER"
    const val ENV_TERMUX_APP__PACKAGE_VARIANT = "${TERMUX_APP_ENV_PREFIX}PACKAGE_VARIANT"
    const val ENV_TERMUX_APP__DATA_DIR = "${TERMUX_APP_ENV_PREFIX}DATA_DIR"
    const val ENV_TERMUX_APP__LEGACY_DATA_DIR = "${TERMUX_APP_ENV_PREFIX}LEGACY_DATA_DIR"
    const val ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED = "${TERMUX_APP_ENV_PREFIX}AM_SOCKET_SERVER_ENABLED"

    @JvmStatic fun getEnvironment(context: Context): HashMap<String, String>? {
        setFableAppEnvironment(context)
        return fableAppEnvironment
    }

    @JvmStatic @Synchronized
    fun setFableAppEnvironment(context: Context) {
        val isFableApp = TermuxConstants.TERMUX_PACKAGE_NAME == context.packageName
        if (fableAppEnvironment != null && isFableApp) return
        fableAppEnvironment = null
        val packageInfo = PackageUtils.getPackageInfoForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME) ?: return
        val appInfo = PackageUtils.getApplicationInfoForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME)
        if (appInfo == null || !appInfo.enabled) return
        val env = hashMapOf<String, String>()
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_VERSION, PackageUtils.getVersionNameForPackage(packageInfo))
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__APP_VERSION_NAME, PackageUtils.getVersionNameForPackage(packageInfo))
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__APP_VERSION_CODE, PackageUtils.getVersionCodeForPackage(packageInfo).toString())
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__PACKAGE_NAME, TermuxConstants.TERMUX_PACKAGE_NAME)
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__PID, FableUtils.getFableAppPID(context))
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX__UID, PackageUtils.getUidForPackage(appInfo).toString())
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__TARGET_SDK, PackageUtils.getTargetSDKForPackage(appInfo).toString())
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__IS_DEBUGGABLE_BUILD, PackageUtils.isAppForPackageADebuggableBuild(appInfo))
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__APK_FILE, PackageUtils.getBaseAPKPathForPackage(appInfo))
        ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__IS_INSTALLED_ON_EXTERNAL_STORAGE, PackageUtils.isAppInstalledOnExternalStorage(appInfo))
        putFableAPKSignature(context, env)
        if (FableUtils.getFablePackageContext(context) != null) {
            ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__DATA_DIR, appInfo.dataDir)
            ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__LEGACY_DATA_DIR, "/data/data/${appInfo.packageName}")
            ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX__SE_PROCESS_CONTEXT, SELinuxUtils.getContext())
            ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__SE_FILE_CONTEXT, SELinuxUtils.getFileContext(appInfo.dataDir))
            val seInfoUser = PackageUtils.getApplicationInfoSeInfoUserForPackage(appInfo)
            ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX_APP__SE_INFO,
                PackageUtils.getApplicationInfoSeInfoForPackage(appInfo) + if (DataUtils.isNullOrEmpty(seInfoUser)) "" else seInfoUser)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX__USER_ID, PackageUtils.getUserIdForPackage(context).toString())
            ShellEnvironmentUtils.putToEnvIfSet(env, ENV_TERMUX__PROFILE_OWNER, PackageUtils.getProfileOwnerPackageNameForUser(context))
        }
        fableAppEnvironment = env
    }

    @JvmStatic
    fun putFableAPKSignature(context: Context, environment: HashMap<String, String>) {
        PackageUtils.getSigningCertificateSHA256DigestForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME)?.let {
            ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_TERMUX_APP__APK_RELEASE,
                FableUtils.getAPKRelease(it).replace(Regex("[^a-zA-Z]"), "_").uppercase())
        }
    }

    @JvmStatic @Synchronized
    fun updateFableAppAMSocketServerEnabled(context: Context) {
        fableAppEnvironment?.let {
            it.remove(ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED)
            ShellEnvironmentUtils.putToEnvIfSet(
                it,
                ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED,
                FableAmSocketServer.getFableAppAMSocketServerEnabled(context)
            )
        }
    }
}
