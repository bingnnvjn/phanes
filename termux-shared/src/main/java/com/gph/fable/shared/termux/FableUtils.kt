package com.gph.fable.shared.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.R
import com.gph.fable.shared.android.AndroidUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.reflection.ReflectionUtils
import com.gph.fable.shared.shell.command.ExecutionCommand
import com.gph.fable.shared.shell.command.runner.app.AppShell
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP
import com.gph.fable.shared.termux.file.FableFileUtils
import com.gph.fable.shared.termux.shell.command.environment.FableShellEnvironment
import com.gph.fable.shared.android.PackageUtils
import org.apache.commons.io.IOUtils
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.regex.Pattern

object FableUtils {

    /** The modes used by [getAppInfoMarkdownString]. */
    enum class AppInfoMode {
        /** Get info for Fable app only. */
        TERMUX_PACKAGE,
        /** Get info for Fable app and plugin app if context is of plugin app. */
        TERMUX_AND_PLUGIN_PACKAGE,
        /** Get info for Fable app and its plugins listed in [TermuxConstants.TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST]. */
        TERMUX_AND_PLUGIN_PACKAGES,
        /* Get info for all the Fable app plugins listed in [TermuxConstants.TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST]. */
        TERMUX_PLUGIN_PACKAGES,
        /* Get info for Fable app and the calling package that called a Fable API. */
        TERMUX_AND_CALLING_PACKAGE,
    }

    private const val LOG_TAG = "FableUtils"

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_PACKAGE_NAME] package with the
     * [Context.CONTEXT_RESTRICTED] flag.
     */
    @JvmStatic
    fun getFablePackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_PACKAGE_NAME] package with the
     * [Context.CONTEXT_INCLUDE_CODE] flag.
     */
    @JvmStatic
    fun getFablePackageContextWithCode(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME, Context.CONTEXT_INCLUDE_CODE)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_API_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableAPIPackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_API_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_BOOT_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableBootPackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableFloatPackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_STYLING_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableStylingPackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_STYLING_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_TASKER_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableTaskerPackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_TASKER_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableWidgetPackageContext(@NonNull context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME)
    }

    /** Wrapper for [PackageUtils.getContextForPackageOrExitApp]. */
    @JvmStatic
    fun getContextForPackageOrExitApp(@NonNull context: Context, packageName: String?, exitAppOnError: Boolean): Context? {
        return PackageUtils.getContextForPackageOrExitApp(context, packageName, exitAppOnError, TermuxConstants.TERMUX_GITHUB_REPO_URL)
    }

    /**
     * Check if Fable app is installed and enabled.
     */
    @JvmStatic
    fun isFableAppInstalled(@NonNull context: Context): String? {
        return PackageUtils.isAppInstalled(context, TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_PACKAGE_NAME)
    }

    /**
     * Check if Fable:API app is installed and enabled.
     */
    @JvmStatic
    fun isFableAPIAppInstalled(@NonNull context: Context): String? {
        return PackageUtils.isAppInstalled(context, TermuxConstants.TERMUX_API_APP_NAME, TermuxConstants.TERMUX_API_PACKAGE_NAME)
    }

    /**
     * Check if Fable app is installed and accessible.
     */
    @JvmStatic
    fun isFableAppAccessible(@NonNull currentPackageContext: Context): String? {
        var errmsg = isFableAppInstalled(currentPackageContext)
        if (errmsg == null) {
            val fablePackageContext = FableUtils.getFablePackageContext(currentPackageContext)
            // If failed to get Fable app package context
            if (fablePackageContext == null)
                errmsg = currentPackageContext.getString(R.string.error_fable_app_package_context_not_accessible)

            if (errmsg == null) {
                // If TermuxConstants.TERMUX_PREFIX_DIR_PATH is not a directory or does not have required permissions
                val error = FableFileUtils.isFablePrefixDirectoryAccessible(false, false)
                if (error != null)
                    errmsg = currentPackageContext.getString(R.string.error_fable_prefix_dir_path_not_accessible,
                        PackageUtils.getAppNameForPackage(currentPackageContext))
            }
        }

        return if (errmsg != null)
            errmsg + " " + currentPackageContext.getString(R.string.msg_fable_app_required_by_app,
                PackageUtils.getAppNameForPackage(currentPackageContext))
        else
            null
    }

    /**
     * Get a field value from the [TERMUX_APP.BUILD_CONFIG_CLASS_NAME] class of the Fable app
     * APK installed on the device.
     */
    @JvmStatic
    fun getFableAppAPKBuildConfigClassField(@NonNull currentPackageContext: Context, @NonNull fieldName: String): Any? {
        return getFableAppAPKClassField(currentPackageContext, TERMUX_APP.BUILD_CONFIG_CLASS_NAME, fieldName)
    }

    /**
     * Get a field value from a class of the Fable app APK installed on the device.
     */
    @JvmStatic
    fun getFableAppAPKClassField(@NonNull currentPackageContext: Context, @NonNull clazzName: String, @NonNull fieldName: String): Any? {
        return try {
            val fablePackageContext = FableUtils.getFablePackageContextWithCode(currentPackageContext)
            if (fablePackageContext == null)
                return null

            val clazz = fablePackageContext.classLoader.loadClass(clazzName)
            ReflectionUtils.invokeField(clazz, fieldName, null).value
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + fieldName + "\" value from \"" + clazzName + "\" class", e)
            null
        }
    }

    /** Returns `true` if [Uri] has `package:` scheme for [TermuxConstants.TERMUX_PACKAGE_NAME] or its sub plugin package. */
    @JvmStatic
    fun isUriDataForFableOrPluginPackage(@NonNull data: Uri): Boolean {
        return data.toString() == "package:" + TermuxConstants.TERMUX_PACKAGE_NAME ||
            data.toString().startsWith("package:" + TermuxConstants.TERMUX_PACKAGE_NAME + ".")
    }

    /** Returns `true` if [Uri] has `package:` scheme for [TermuxConstants.TERMUX_PACKAGE_NAME] sub plugin package. */
    @JvmStatic
    fun isUriDataForFablePluginPackage(@NonNull data: Uri): Boolean {
        return data.toString().startsWith("package:" + TermuxConstants.TERMUX_PACKAGE_NAME + ".")
    }

    /**
     * Send the [TermuxConstants.BROADCAST_TERMUX_OPENED] broadcast to notify apps that Fable
     * app has been opened.
     */
    @JvmStatic
    fun sendFableOpenedBroadcast(@NonNull context: Context) {
        val broadcast = Intent(TermuxConstants.BROADCAST_TERMUX_OPENED)
        val matches = context.packageManager.queryBroadcastReceivers(broadcast, 0)

        // send broadcast to registered receivers
        // this technique is needed to work around broadcast changes that Oreo introduced
        for (info in matches) {
            val explicitBroadcast = Intent(broadcast)
            val cname = ComponentName(info.activityInfo.applicationInfo.packageName,
                info.activityInfo.name)
            explicitBroadcast.component = cname
            context.sendBroadcast(explicitBroadcast)
        }
    }

    /**
     * Wrapper for [getAppInfoMarkdownString].
     */
    @JvmStatic
    fun getAppInfoMarkdownString(currentPackageContext: Context, appInfoMode: AppInfoMode): String? {
        return getAppInfoMarkdownString(currentPackageContext, appInfoMode, null)
    }

    /**
     * Get a markdown [String] for the apps info of Fable app, its installed plugin apps or
     * external apps that called a Fable API depending on [AppInfoMode] passed.
     */
    @JvmStatic
    fun getAppInfoMarkdownString(currentPackageContext: Context, appInfoMode: AppInfoMode?, @Nullable callingPackageName: String?): String? {
        if (appInfoMode == null) return null

        val appInfo = StringBuilder()
        return when (appInfoMode) {
            AppInfoMode.TERMUX_PACKAGE ->
                getAppInfoMarkdownString(currentPackageContext, false)

            AppInfoMode.TERMUX_AND_PLUGIN_PACKAGE ->
                getAppInfoMarkdownString(currentPackageContext, true)

            AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES -> {
                appInfo.append(FableUtils.getAppInfoMarkdownString(currentPackageContext, false))

                val termuxPluginAppsInfo = FableUtils.getFablePluginAppsInfoMarkdownString(currentPackageContext)
                if (termuxPluginAppsInfo != null)
                    appInfo.append("\n\n").append(termuxPluginAppsInfo)
                appInfo.toString()
            }

            AppInfoMode.TERMUX_PLUGIN_PACKAGES ->
                FableUtils.getFablePluginAppsInfoMarkdownString(currentPackageContext)

            AppInfoMode.TERMUX_AND_CALLING_PACKAGE -> {
                appInfo.append(FableUtils.getAppInfoMarkdownString(currentPackageContext, false))
                if (!DataUtils.isNullOrEmpty(callingPackageName)) {
                    var callingPackageAppInfo: String? = null
                    if (TermuxConstants.TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST.contains(callingPackageName)) {
                        val fablePluginAppContext = PackageUtils.getContextForPackage(currentPackageContext, callingPackageName)
                        if (fablePluginAppContext != null)
                            appInfo.append(getAppInfoMarkdownString(fablePluginAppContext, false))
                        else
                            callingPackageAppInfo = AndroidUtils.getAppInfoMarkdownString(currentPackageContext, callingPackageName!!)
                    } else {
                        callingPackageAppInfo = AndroidUtils.getAppInfoMarkdownString(currentPackageContext, callingPackageName!!)
                    }

                    if (callingPackageAppInfo != null) {
                        val applicationInfo = PackageUtils.getApplicationInfoForPackage(currentPackageContext, callingPackageName!!)
                        if (applicationInfo != null) {
                            appInfo.append("\n\n## ").append(PackageUtils.getAppNameForPackage(currentPackageContext, applicationInfo)).append(" App Info\n")
                            appInfo.append(callingPackageAppInfo)
                            appInfo.append("\n##\n")
                        }
                    }
                }
                appInfo.toString()
            }
        }
    }

    /**
     * Get a markdown [String] for the apps info of all/any Fable plugin apps installed.
     */
    @JvmStatic
    fun getFablePluginAppsInfoMarkdownString(currentPackageContext: Context): String? {
        if (currentPackageContext == null) return "null"

        val markdownString = StringBuilder()

        val termuxPluginAppPackageNamesList = TermuxConstants.TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST

        if (termuxPluginAppPackageNamesList != null) {
            for (i in termuxPluginAppPackageNamesList.indices) {
                val termuxPluginAppPackageName = termuxPluginAppPackageNamesList[i]
                val fablePluginAppContext = PackageUtils.getContextForPackage(currentPackageContext, termuxPluginAppPackageName)
                // If the package context for the plugin app is not null, then assume its installed and get its info
                if (fablePluginAppContext != null) {
                    if (i != 0)
                        markdownString.append("\n\n")
                    markdownString.append(getAppInfoMarkdownString(fablePluginAppContext, false))
                }
            }
        }

        return if (markdownString.toString().isEmpty())
            null
        else
            markdownString.toString()
    }

    /**
     * Get a markdown [String] for the app info.
     */
    @JvmStatic
    fun getAppInfoMarkdownString(currentPackageContext: Context, returnFablePackageInfoToo: Boolean): String {
        if (currentPackageContext == null) return "null"

        val markdownString = StringBuilder()

        val fablePackageContext = getFablePackageContext(currentPackageContext)

        var termuxPackageName: String? = null
        var termuxAppName: String? = null
        if (fablePackageContext != null) {
            termuxPackageName = PackageUtils.getPackageNameForPackage(fablePackageContext)
            termuxAppName = PackageUtils.getAppNameForPackage(fablePackageContext)
        }

        val currentPackageName = PackageUtils.getPackageNameForPackage(currentPackageContext)
        val currentAppName = PackageUtils.getAppNameForPackage(currentPackageContext)

        val isFablePackage = (termuxPackageName != null && termuxPackageName == currentPackageName)

        if (returnFablePackageInfoToo && !isFablePackage)
            markdownString.append("## ").append(currentAppName).append(" App Info (Current)\n")
        else
            markdownString.append("## ").append(currentAppName).append(" App Info\n")
        markdownString.append(getAppInfoMarkdownStringInner(currentPackageContext))
        markdownString.append("\n##\n")

        if (returnFablePackageInfoToo && fablePackageContext != null && !isFablePackage) {
            markdownString.append("\n\n## ").append(termuxAppName).append(" App Info\n")
            markdownString.append(getAppInfoMarkdownStringInner(fablePackageContext))
            markdownString.append("\n##\n")
        }

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for the app info for the package associated with the `context`.
     */
    @JvmStatic
    fun getAppInfoMarkdownStringInner(@NonNull context: Context): String {
        val markdownString = StringBuilder()

        markdownString.append(AndroidUtils.getAppInfoMarkdownString(context))

        if (context.packageName == TermuxConstants.TERMUX_PACKAGE_NAME) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "TERMUX_APP_PACKAGE_MANAGER", FableBootstrap.TERMUX_APP_PACKAGE_MANAGER)
            AndroidUtils.appendPropertyToMarkdown(markdownString, "TERMUX_APP_PACKAGE_VARIANT", FableBootstrap.TERMUX_APP_PACKAGE_VARIANT)
        }

        val error = FableFileUtils.isFableFilesDirectoryAccessible(context, true, true)
        if (error != null) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "TERMUX_FILES_DIR", TermuxConstants.TERMUX_FILES_DIR_PATH)
            AndroidUtils.appendPropertyToMarkdown(markdownString, "IS_TERMUX_FILES_DIR_ACCESSIBLE", "false - " + Error.getMinimalErrorString(error))
        }

        val signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context)
        if (signingCertificateSHA256Digest != null) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "APK_RELEASE", getAPKRelease(signingCertificateSHA256Digest))
            AndroidUtils.appendPropertyToMarkdown(markdownString, "SIGNING_CERTIFICATE_SHA256_DIGEST", signingCertificateSHA256Digest)
        }

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for reporting an issue.
     */
    @JvmStatic
    fun getReportIssueMarkdownString(@NonNull context: Context): String {
        if (context == null) return "null"

        val markdownString = StringBuilder()

        markdownString.append("## Where To Report An Issue")

        markdownString.append("\n\n").append(context.getString(R.string.msg_report_issue, TermuxConstants.TERMUX_WIKI_URL)).append("\n")

        markdownString.append("\n\n### Email\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_SUPPORT_EMAIL_URL, TermuxConstants.TERMUX_SUPPORT_EMAIL_MAILTO_URL)).append("  ")

        markdownString.append("\n\n### Reddit\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_REDDIT_SUBREDDIT, TermuxConstants.TERMUX_REDDIT_SUBREDDIT_URL)).append("  ")

        markdownString.append("\n\n### GitHub Issues for Termux apps\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_API_APP_NAME, TermuxConstants.TERMUX_API_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_BOOT_APP_NAME, TermuxConstants.TERMUX_BOOT_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_FLOAT_APP_NAME, TermuxConstants.TERMUX_FLOAT_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_STYLING_APP_NAME, TermuxConstants.TERMUX_STYLING_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_TASKER_APP_NAME, TermuxConstants.TERMUX_TASKER_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_WIDGET_APP_NAME, TermuxConstants.TERMUX_WIDGET_GITHUB_ISSUES_REPO_URL)).append("  ")

        markdownString.append("\n\n### GitHub Issues for Termux packages\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for important links.
     */
    @JvmStatic
    fun getImportantLinksMarkdownString(@NonNull context: Context): String {
        if (context == null) return "null"

        val markdownString = StringBuilder()

        markdownString.append("## Important Links")

        markdownString.append("\n\n### GitHub\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_API_APP_NAME, TermuxConstants.TERMUX_API_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_BOOT_APP_NAME, TermuxConstants.TERMUX_BOOT_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_FLOAT_APP_NAME, TermuxConstants.TERMUX_FLOAT_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_STYLING_APP_NAME, TermuxConstants.TERMUX_STYLING_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_TASKER_APP_NAME, TermuxConstants.TERMUX_TASKER_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_WIDGET_APP_NAME, TermuxConstants.TERMUX_WIDGET_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_URL)).append("  ")

        markdownString.append("\n\n### Email\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_SUPPORT_EMAIL_URL, TermuxConstants.TERMUX_SUPPORT_EMAIL_MAILTO_URL)).append("  ")

        markdownString.append("\n\n### Reddit\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_REDDIT_SUBREDDIT, TermuxConstants.TERMUX_REDDIT_SUBREDDIT_URL)).append("  ")

        markdownString.append("\n\n### Wiki\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_WIKI, TermuxConstants.TERMUX_WIKI_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_GITHUB_WIKI_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_PACKAGES_GITHUB_WIKI_REPO_URL)).append("  ")

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for APT info of the app.
     */
    @JvmStatic
    fun geAPTInfoMarkdownString(@NonNull context: Context): String? {

        var aptInfoScript: String
        val inputStream: InputStream = context.resources.openRawResource(com.gph.fable.shared.R.raw.apt_info_script)
        try {
            aptInfoScript = IOUtils.toString(inputStream, Charset.defaultCharset())
        } catch (e: IOException) {
            Logger.logError(LOG_TAG, "Failed to get APT info script: " + e.message)
            return null
        }

        IOUtils.closeQuietly(inputStream)

        if (aptInfoScript.isEmpty()) {
            Logger.logError(LOG_TAG, "The APT info script is null or empty")
            return null
        }

        aptInfoScript = aptInfoScript.replace(Regex(Pattern.quote("@TERMUX_PREFIX@")), TermuxConstants.TERMUX_PREFIX_DIR_PATH)

        val executionCommand = ExecutionCommand(-1,
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", null, aptInfoScript,
            null, ExecutionCommand.Runner.APP_SHELL.getName(), false)
        executionCommand.commandLabel = "APT Info Command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val appShell = AppShell.execute(context, executionCommand, null, FableShellEnvironment(), null, true)
        if (appShell == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode != 0) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        if (!executionCommand.resultData.stderr.toString().isEmpty())
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())

        val markdownString = StringBuilder()

        markdownString.append("## ").append(TermuxConstants.TERMUX_APP_NAME).append(" APT Info\n\n")
        markdownString.append(executionCommand.resultData.stdout.toString())
        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for info for Fable debugging.
     */
    @JvmStatic
    fun getFableDebugMarkdownString(@NonNull context: Context): String? {
        val statInfo = FableFileUtils.getFableFilesStatMarkdownString(context)
        val logcatInfo = getLogcatDumpMarkdownString(context)

        return when {
            statInfo != null && logcatInfo != null -> statInfo + "\n\n" + logcatInfo
            statInfo != null -> statInfo
            else -> logcatInfo
        }
    }

    /**
     * Get a markdown [String] for logcat command dump.
     */
    @JvmStatic
    fun getLogcatDumpMarkdownString(@NonNull context: Context): String? {
        // Build script
        val logcatScript = "/system/bin/logcat -d -t 3000 2>&1"

        // Run script
        // Logging must be disabled for output of logcat command itself in StreamGobbler
        val executionCommand = ExecutionCommand(-1, "/system/bin/sh",
            null, logcatScript + "\n", "/", ExecutionCommand.Runner.APP_SHELL.getName(), true)
        executionCommand.commandLabel = "Logcat dump command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val appShell = AppShell.execute(context, executionCommand, null, FableShellEnvironment(), null, true)
        if (appShell == null || !executionCommand.isSuccessful()) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        // Build script output
        val logcatOutput = StringBuilder()
        logcatOutput.append("$ ").append(logcatScript)
        logcatOutput.append("\n").append(executionCommand.resultData.stdout.toString())

        val stderrSet = !executionCommand.resultData.stderr.toString().isEmpty()
        if (executionCommand.resultData.exitCode != 0 || stderrSet) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            if (stderrSet)
                logcatOutput.append("\n").append(executionCommand.resultData.stderr.toString())
            logcatOutput.append("\n").append("exit code: ").append(executionCommand.resultData.exitCode.toString())
        }

        // Build markdown output
        val markdownString = StringBuilder()
        markdownString.append("## Logcat Dump\n\n")
        markdownString.append("\n\n").append(MarkdownUtils.getMarkdownCodeForString(logcatOutput.toString(), true))
        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    @JvmStatic
    fun getAPKRelease(signingCertificateSHA256Digest: String?): String {
        if (signingCertificateSHA256Digest == null) return "null"

        return when (signingCertificateSHA256Digest.uppercase()) {
            TermuxConstants.APK_RELEASE_FDROID_SIGNING_CERTIFICATE_SHA256_DIGEST ->
                TermuxConstants.APK_RELEASE_FDROID
            TermuxConstants.APK_RELEASE_GITHUB_SIGNING_CERTIFICATE_SHA256_DIGEST ->
                TermuxConstants.APK_RELEASE_GITHUB
            TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST ->
                TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE
            TermuxConstants.APK_RELEASE_TERMUX_DEVS_SIGNING_CERTIFICATE_SHA256_DIGEST ->
                TermuxConstants.APK_RELEASE_TERMUX_DEVS
            else ->
                "Unknown"
        }
    }

    /**
     * Get a process id of the main app process of the [TermuxConstants.TERMUX_PACKAGE_NAME] package.
     */
    @JvmStatic
    fun getFableAppPID(context: Context?): String? {
        return PackageUtils.getPackagePID(context, TermuxConstants.TERMUX_PACKAGE_NAME)
    }
}
