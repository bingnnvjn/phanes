package com.gph.fable.shared.termux

import android.content.Context
import com.gph.fable.shared.logger.Logger

object FableBootstrap {
    private const val LOG_TAG = "FableBootstrap"
    const val BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT = "TERMUX_PACKAGE_VARIANT"

    @JvmField
    var TERMUX_APP_PACKAGE_MANAGER: PackageManager? = null

    @JvmField
    var TERMUX_APP_PACKAGE_VARIANT: PackageVariant? = null

    @JvmStatic
    fun setFablePackageManagerAndVariant(packageVariantName: String?) {
        val variant = PackageVariant.variantOf(packageVariantName)
            ?: throw RuntimeException("Unsupported TERMUX_APP_PACKAGE_VARIANT \"$packageVariantName\"")
        TERMUX_APP_PACKAGE_VARIANT = variant
        Logger.logVerbose(LOG_TAG, "Set TERMUX_APP_PACKAGE_VARIANT to \"$variant\"")

        val index = packageVariantName!!.indexOf('-')
        val packageManagerName = if (index == -1) null else packageVariantName.substring(0, index)
        val manager = PackageManager.managerOf(packageManagerName)
            ?: throw RuntimeException("Unsupported TERMUX_APP_PACKAGE_MANAGER \"$packageManagerName\" with variant \"$packageVariantName\"")
        TERMUX_APP_PACKAGE_MANAGER = manager
        Logger.logVerbose(LOG_TAG, "Set TERMUX_APP_PACKAGE_MANAGER to \"$manager\"")
    }

    @JvmStatic
    fun setFablePackageManagerAndVariantFromFableApp(currentPackageContext: Context) {
        val packageVariantName = getFableAppBuildConfigPackageVariantFromFableApp(currentPackageContext)
        if (packageVariantName != null) setFablePackageManagerAndVariant(packageVariantName)
        else Logger.logError(LOG_TAG, "Failed to set TERMUX_APP_PACKAGE_VARIANT and TERMUX_APP_PACKAGE_MANAGER from the termux app")
    }

    @JvmStatic
    fun getFableAppBuildConfigPackageVariantFromFableApp(currentPackageContext: Context): String? =
        try {
            FableUtils.getFableAppAPKBuildConfigClassField(
                currentPackageContext,
                BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT
            ) as? String
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(
                LOG_TAG,
                "Failed to get \"$BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT\" value from \"${TermuxConstants.TERMUX_APP.BUILD_CONFIG_CLASS_NAME}\" class",
                e
            )
            null
        }

    @JvmStatic fun isAppPackageManagerAPT() = PackageManager.APT == TERMUX_APP_PACKAGE_MANAGER
    @JvmStatic fun isAppPackageVariantAPTAndroid7() = PackageVariant.APT_ANDROID_7 == TERMUX_APP_PACKAGE_VARIANT
    @JvmStatic fun isAppPackageVariantAPTAndroid5() = PackageVariant.APT_ANDROID_5 == TERMUX_APP_PACKAGE_VARIANT

    enum class PackageManager(val managerName: String) {
        APT("apt");

        fun getName() = managerName
        fun equalsManager(manager: String?) = manager == managerName

        companion object {
            @JvmStatic
            fun managerOf(name: String?): PackageManager? =
                values().firstOrNull { it.managerName == name && !name.isNullOrEmpty() }
        }
    }

    enum class PackageVariant(val variantName: String) {
        APT_ANDROID_7("apt-android-7"),
        APT_ANDROID_5("apt-android-5");

        fun getName() = variantName
        fun equalsVariant(variant: String?) = variant == variantName

        companion object {
            @JvmStatic
            fun variantOf(name: String?): PackageVariant? =
                values().firstOrNull { it.variantName == name && !name.isNullOrEmpty() }
        }
    }
}
