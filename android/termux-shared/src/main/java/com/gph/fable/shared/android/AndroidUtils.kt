package com.gph.fable.shared.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.NonNull
import com.google.common.base.Joiner
import com.gph.fable.shared.R
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.regex.Matcher
import java.util.regex.Pattern

object AndroidUtils {

    /**
     * Get a markdown [String] for the app info for the package associated with the `context`.
     */
    @JvmStatic
    fun getAppInfoMarkdownString(@NonNull context: Context): String {
        val markdownString = StringBuilder()

        val appInfo = getAppInfoMarkdownString(context, context.packageName)
        if (appInfo == null)
            return markdownString.toString()
        else
            markdownString.append(appInfo)

        val filesDir = context.filesDir.absolutePath
        if (!filesDir.equals("/data/user/0/" + context.packageName + "/files") &&
            !filesDir.equals("/data/data/" + context.packageName + "/files"))
            AndroidUtils.appendPropertyToMarkdown(markdownString, "FILES_DIR", filesDir)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userId = PackageUtils.getUserIdForPackage(context)
            if (userId == null || userId != 0L)
                AndroidUtils.appendPropertyToMarkdown(markdownString, "USER_ID", userId)
        }

        AndroidUtils.appendPropertyToMarkdownIfSet(markdownString, "PROFILE_OWNER", PackageUtils.getProfileOwnerPackageNameForUser(context))

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for the app info for the `packageName`.
     */
    @JvmStatic
    fun getAppInfoMarkdownString(@NonNull context: Context, @NonNull packageName: String): String? {
        val packageInfo = PackageUtils.getPackageInfoForPackage(context, packageName)
        if (packageInfo == null) return null
        val applicationInfo = PackageUtils.getApplicationInfoForPackage(context, packageName)
        if (applicationInfo == null) return null

        val markdownString = StringBuilder()

        AndroidUtils.appendPropertyToMarkdown(markdownString, "APP_NAME", PackageUtils.getAppNameForPackage(context, applicationInfo))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "PACKAGE_NAME", PackageUtils.getPackageNameForPackage(applicationInfo))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "VERSION_NAME", PackageUtils.getVersionNameForPackage(packageInfo))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "VERSION_CODE", PackageUtils.getVersionCodeForPackage(packageInfo))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "UID", PackageUtils.getUidForPackage(applicationInfo))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "TARGET_SDK", PackageUtils.getTargetSDKForPackage(applicationInfo))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "IS_DEBUGGABLE_BUILD", PackageUtils.isAppForPackageADebuggableBuild(applicationInfo))

        if (PackageUtils.isAppInstalledOnExternalStorage(applicationInfo)) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "APK_PATH", PackageUtils.getBaseAPKPathForPackage(applicationInfo))
            AndroidUtils.appendPropertyToMarkdown(markdownString, "IS_INSTALLED_ON_EXTERNAL_STORAGE", true)
        }

        AndroidUtils.appendPropertyToMarkdown(markdownString, "SE_PROCESS_CONTEXT", SELinuxUtils.getContext())
        AndroidUtils.appendPropertyToMarkdown(markdownString, "SE_FILE_CONTEXT", SELinuxUtils.getFileContext(context.filesDir.absolutePath))

        val seInfoUser = PackageUtils.getApplicationInfoSeInfoUserForPackage(applicationInfo)
        AndroidUtils.appendPropertyToMarkdown(markdownString, "SE_INFO", PackageUtils.getApplicationInfoSeInfoForPackage(applicationInfo) +
            (if (DataUtils.isNullOrEmpty(seInfoUser)) "" else seInfoUser))

        return markdownString.toString()
    }

    @JvmStatic
    fun getDeviceInfoMarkdownString(@NonNull context: Context): String {
        return getDeviceInfoMarkdownString(context, false)
    }

    /**
     * Get a markdown [String] for the device info.
     */
    @JvmStatic
    fun getDeviceInfoMarkdownString(@NonNull context: Context, addPhantomProcessesInfo: Boolean): String {
        // Some properties cannot be read with [System.getProperty] but can be read
        // directly by running getprop command
        val systemProperties = getSystemProperties()

        val markdownString = StringBuilder()

        markdownString.append("## Device Info")

        markdownString.append("\n\n### Software\n")
        appendPropertyToMarkdown(markdownString, "OS_VERSION", getSystemPropertyWithAndroidAPI("os.version"))
        appendPropertyToMarkdown(markdownString, "SDK_INT", Build.VERSION.SDK_INT)
        // If its a release version
        if ("REL" == Build.VERSION.CODENAME)
            appendPropertyToMarkdown(markdownString, "RELEASE", Build.VERSION.RELEASE)
        else
            appendPropertyToMarkdown(markdownString, "CODENAME", Build.VERSION.CODENAME)
        appendPropertyToMarkdown(markdownString, "ID", Build.ID)
        appendPropertyToMarkdown(markdownString, "DISPLAY", Build.DISPLAY)
        appendPropertyToMarkdown(markdownString, "INCREMENTAL", Build.VERSION.INCREMENTAL)
        appendPropertyToMarkdownIfSet(markdownString, "SECURITY_PATCH", systemProperties.getProperty("ro.build.version.security_patch"))
        appendPropertyToMarkdownIfSet(markdownString, "IS_DEBUGGABLE", systemProperties.getProperty("ro.debuggable"))
        appendPropertyToMarkdownIfSet(markdownString, "IS_EMULATOR", systemProperties.getProperty("ro.boot.qemu"))
        appendPropertyToMarkdownIfSet(markdownString, "IS_TREBLE_ENABLED", systemProperties.getProperty("ro.treble.enabled"))
        appendPropertyToMarkdown(markdownString, "TYPE", Build.TYPE)
        appendPropertyToMarkdown(markdownString, "TAGS", Build.TAGS)

        // If on Android >= 12
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            val maxPhantomProcesses = PhantomProcessUtils.getActivityManagerMaxPhantomProcesses(context)
            if (maxPhantomProcesses != null)
                appendPropertyToMarkdown(markdownString, "MAX_PHANTOM_PROCESSES", maxPhantomProcesses)
            else
                appendLiteralPropertyToMarkdown(markdownString, "MAX_PHANTOM_PROCESSES", "- (*" + context.getString(R.string.msg_requires_dump_and_package_usage_stats_permissions) + "*)")

            appendPropertyToMarkdown(markdownString, "MONITOR_PHANTOM_PROCS", PhantomProcessUtils.getFeatureFlagMonitorPhantomProcsValueString(context).getName())
            appendPropertyToMarkdown(markdownString, "DEVICE_CONFIG_SYNC_DISABLED", PhantomProcessUtils.getSettingsGlobalDeviceConfigSyncDisabled(context))
        }

        markdownString.append("\n\n### Hardware\n")
        appendPropertyToMarkdown(markdownString, "MANUFACTURER", Build.MANUFACTURER)
        appendPropertyToMarkdown(markdownString, "BRAND", Build.BRAND)
        appendPropertyToMarkdown(markdownString, "MODEL", Build.MODEL)
        appendPropertyToMarkdown(markdownString, "PRODUCT", Build.PRODUCT)
        appendPropertyToMarkdown(markdownString, "BOARD", Build.BOARD)
        appendPropertyToMarkdown(markdownString, "HARDWARE", Build.HARDWARE)
        appendPropertyToMarkdown(markdownString, "DEVICE", Build.DEVICE)

        appendPropertyToMarkdown(markdownString, "SUPPORTED_ABIS", Joiner.on(", ").skipNulls().join(Build.SUPPORTED_ABIS))
        appendPropertyToMarkdown(markdownString, "SUPPORTED_32_BIT_ABIS", Joiner.on(", ").skipNulls().join(Build.SUPPORTED_32_BIT_ABIS))
        appendPropertyToMarkdown(markdownString, "SUPPORTED_64_BIT_ABIS", Joiner.on(", ").skipNulls().join(Build.SUPPORTED_64_BIT_ABIS))

        // If on Android >= 15
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                appendPropertyToMarkdownIfSet(markdownString, "PAGE_SIZE", Os.sysconf(OsConstants._SC_PAGESIZE))
            } catch (t: Throwable) {
                // Ignore
            }
        }

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    @JvmStatic
    fun getSystemProperties(): Properties {
        val systemProperties = Properties()

        // getprop commands returns values in the format `[key]: [value]`
        val propertiesPattern = Pattern.compile("^\\[([^]]+)]: \\[(.+)]$")

        try {
            val process = ProcessBuilder()
                .command("/system/bin/getprop")
                .redirectErrorStream(true)
                .start()

            val inputStream: InputStream = process.inputStream
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var key: String?
            var value: String?

            while (bufferedReader.readLine().also { line = it } != null) {
                val matcher: Matcher = propertiesPattern.matcher(line)
                if (matcher.matches()) {
                    key = matcher.group(1)
                    value = matcher.group(2)
                    if (key != null && value != null && !key.isEmpty() && !value.isEmpty())
                        systemProperties.put(key, value)
                }
            }

            bufferedReader.close()
            process.destroy()
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage("Failed to get run \"/system/bin/getprop\" to get system properties.", e)
        }

        return systemProperties
    }

    @JvmStatic
    fun getSystemPropertyWithAndroidAPI(@NonNull property: String): String? {
        return try {
            System.getProperty(property)
        } catch (e: Exception) {
            Logger.logVerbose("Failed to get system property \"" + property + "\":" + e.message)
            null
        }
    }

    @JvmStatic
    fun appendPropertyToMarkdownIfSet(markdownString: StringBuilder, label: String, value: Any?) {
        if (value == null) return
        if ((value is String && value.isEmpty()) || "REL" == value) return
        markdownString.append("\n").append(getPropertyMarkdown(label, value))
    }

    @JvmStatic
    fun appendPropertyToMarkdown(markdownString: StringBuilder, label: String, value: Any?) {
        markdownString.append("\n").append(getPropertyMarkdown(label, value))
    }

    @JvmStatic
    fun getPropertyMarkdown(label: String, value: Any?): String {
        return MarkdownUtils.getSingleLineMarkdownStringEntry(label, value, "-")
    }

    @JvmStatic
    fun appendLiteralPropertyToMarkdown(markdownString: StringBuilder, label: String, value: Any?) {
        markdownString.append("\n").append(getLiteralPropertyMarkdown(label, value))
    }

    @JvmStatic
    fun getLiteralPropertyMarkdown(label: String, value: Any?): String {
        return MarkdownUtils.getLiteralSingleLineMarkdownStringEntry(label, value, "-")
    }

    @JvmStatic
    fun getCurrentTimeStamp(): String {
        @SuppressLint("SimpleDateFormat")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    @JvmStatic
    fun getCurrentMilliSecondUTCTimeStamp(): String {
        @SuppressLint("SimpleDateFormat")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z")
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    @JvmStatic
    fun getCurrentMilliSecondLocalTimeStamp(): String {
        @SuppressLint("SimpleDateFormat")
        val df = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS")
        df.timeZone = TimeZone.getDefault()
        return df.format(Date())
    }
}
