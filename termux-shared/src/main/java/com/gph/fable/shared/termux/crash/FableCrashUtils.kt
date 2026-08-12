package com.gph.fable.shared.termux.crash

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.R
import com.gph.fable.shared.activities.ReportActivity
import com.gph.fable.shared.android.AndroidUtils
import com.gph.fable.shared.crash.CrashHandler
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.models.ReportInfo
import com.gph.fable.shared.notification.NotificationUtils
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.TermuxConstants.TERMUX_APP
import com.gph.fable.shared.termux.models.UserAction
import com.gph.fable.shared.termux.notification.FableNotificationUtils
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants
import java.nio.charset.Charset

class FableCrashUtils internal constructor(private val mType: TYPE) : CrashHandler.CrashHandlerClient {

    enum class TYPE {
        UNCAUGHT_EXCEPTION,
        CAUGHT_EXCEPTION;
    }

    override fun onPreLogCrash(context: Context, thread: Thread, throwable: Throwable): Boolean {
        return false
    }

    override fun onPostLogCrash(currentPackageContext: Context, thread: Thread, throwable: Throwable) {
        if (currentPackageContext == null) return
        val currentPackageName = currentPackageContext.packageName

        // Do not notify if is a non-Fable app
        val context = FableUtils.getFablePackageContext(currentPackageContext)
        if (context == null) {
            Logger.logWarn(LOG_TAG, "Ignoring call to onPostLogCrash() since failed to get \"" + TermuxConstants.TERMUX_PACKAGE_NAME + "\" package context from \"" + currentPackageName + "\" context")
            return
        }

        // If an uncaught exception, then do not notify since the Fable app itself would be crashing
        if (TYPE.UNCAUGHT_EXCEPTION == mType && TermuxConstants.TERMUX_PACKAGE_NAME == currentPackageName)
            return

        val message = TERMUX_APP.TERMUX_ACTIVITY_NAME + " that \"" + currentPackageName + "\" app crashed"

        try {
            Logger.logInfo(LOG_TAG, "Sending broadcast to notify " + message)
            val intent = Intent(TERMUX_APP.TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
            intent.setPackage(TermuxConstants.TERMUX_PACKAGE_NAME)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to notify " + message, e)
        }
    }

    @NonNull
    override fun getCrashLogFilePath(context: Context): String {
        return TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH
    }

    override fun getAppInfoMarkdownString(context: Context): String? {
        return FableUtils.getAppInfoMarkdownString(context, true)
    }

    companion object {
        private const val LOG_TAG = "FableCrashUtils"

        /**
         * Set default uncaught crash handler of the app to [CrashHandler] for Fable app
         * and its plugins to log crashes at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH].
         */
        @JvmStatic
        fun setDefaultCrashHandler(@NonNull context: Context) {
            CrashHandler.setDefaultCrashHandler(context, FableCrashUtils(TYPE.UNCAUGHT_EXCEPTION))
        }

        /**
         * Set uncaught crash handler of current non-main thread to [CrashHandler] for Fable app
         * and its plugins.
         */
        @JvmStatic
        fun setCrashHandler(@NonNull context: Context) {
            CrashHandler.setCrashHandler(context, FableCrashUtils(TYPE.CAUGHT_EXCEPTION))
        }

        /**
         * Get [CrashHandler] for Fable app and its plugins.
         */
        @JvmStatic
        fun getCrashHandler(@NonNull context: Context): CrashHandler {
            return CrashHandler.getCrashHandler(context, FableCrashUtils(TYPE.CAUGHT_EXCEPTION))
        }

        /**
         * Log a crash to [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH] and notify Fable app.
         */
        @JvmStatic
        fun logCrash(@NonNull context: Context, throwable: Throwable?) {
            if (throwable == null) return
            CrashHandler.logCrash(context, FableCrashUtils(TYPE.CAUGHT_EXCEPTION), Thread.currentThread(), throwable)
        }

        /**
         * Notify the user of an app crash by reading the crash info from the crash log file.
         */
        @JvmStatic
        fun notifyAppCrashFromCrashLogFile(currentPackageContext: Context?, logTagParam: String?) {
            if (currentPackageContext == null) return
            val currentPackageName = currentPackageContext.packageName

            val context = FableUtils.getFablePackageContext(currentPackageContext)
            if (context == null) {
                Logger.logWarn(LOG_TAG, "Ignoring call to notifyAppCrash() since failed to get \"" + TermuxConstants.TERMUX_PACKAGE_NAME + "\" package context from \"" + currentPackageName + "\" context")
                return
            }

            val preferences = FableAppSharedPreferences.build(context)
            if (preferences == null) return

            // If user has disabled notifications for crashes
            if (!preferences.areCrashReportNotificationsEnabled(false))
                return

            Thread {
                notifyAppCrashFromCrashLogFileInner(context, logTagParam)
            }.start()
        }

        @Synchronized
        private fun notifyAppCrashFromCrashLogFileInner(context: Context, logTagParam: String?) {
            val logTag = DataUtils.getDefaultIfNull(logTagParam, LOG_TAG)

            if (!FileUtils.regularFileExists(TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, false))
                return

            val error: Error?
            val reportStringBuilder = StringBuilder()

            // Read report string from crash log file
            val readError = FileUtils.readTextFromFile("crash log", TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, Charset.defaultCharset(), reportStringBuilder, false)
            if (readError != null) {
                Logger.logErrorExtended(logTag, readError.toString())
                return
            }

            // Move crash log file to backup location if it exists
            error = FileUtils.moveRegularFile("crash log", TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH, true)
            if (error != null) {
                Logger.logErrorExtended(logTag, error.toString())
            }

            val reportString = reportStringBuilder.toString()

            if (reportString.isEmpty())
                return

            Logger.logDebug(logTag, "A crash log file found at \"" + TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH + "\".")

            sendCrashReportNotification(context, logTag, null, null, reportString, false, false, null, false)
        }

        /**
         * Send a crash report notification.
         */
        @JvmStatic
        fun sendCrashReportNotification(currentPackageContext: Context?, logTag: String?,
                                        title: CharSequence?, message: String?, throwable: Throwable?) {
            sendCrashReportNotification(currentPackageContext, logTag,
                title, message,
                MarkdownUtils.getMarkdownCodeForString(Logger.getMessageAndStackTraceString(message, throwable), true),
                false, false, true)
        }

        /**
         * Send a crash report notification.
         */
        @JvmStatic
        fun sendCrashReportNotification(currentPackageContext: Context?, logTag: String?,
                                        title: CharSequence?, notificationTextString: String?,
                                        message: String?) {
            sendCrashReportNotification(currentPackageContext, logTag,
                title, notificationTextString, message,
                false, false, true)
        }

        /**
         * Send a crash report notification.
         */
        @JvmStatic
        fun sendCrashReportNotification(currentPackageContext: Context?, logTag: String?,
                                        title: CharSequence?, notificationTextString: String?,
                                        message: String?, forceNotification: Boolean,
                                        showToast: Boolean,
                                        addDeviceInfo: Boolean) {
            sendCrashReportNotification(currentPackageContext, logTag,
                title, notificationTextString, "## " + title + "\n\n" + message + "\n\n",
                forceNotification, showToast, FableUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGE, addDeviceInfo)
        }

        /**
         * Send a crash report notification.
         */
        @JvmStatic
        fun sendCrashReportNotification(currentPackageContext: Context?, logTag: String?,
                                        title: CharSequence?,
                                        notificationTextString: String?,
                                        message: String?, forceNotification: Boolean,
                                        showToast: Boolean,
                                        appInfoMode: FableUtils.AppInfoMode?,
                                        addDeviceInfo: Boolean) {
            // Note: Do not change currentPackageContext or fablePackageContext passed to functions or things will break

            if (currentPackageContext == null) return
            val currentPackageName = currentPackageContext.packageName

            val fablePackageContext = FableUtils.getFablePackageContext(currentPackageContext)
            if (fablePackageContext == null) {
                Logger.logWarn(LOG_TAG, "Ignoring call to sendCrashReportNotification() since failed to get \"" + TermuxConstants.TERMUX_PACKAGE_NAME + "\" package context from \"" + currentPackageName + "\" context")
                return
            }

            val preferences = FableAppSharedPreferences.build(fablePackageContext)
            if (preferences == null) return

            // If user has disabled notifications for crashes
            if (!preferences.areCrashReportNotificationsEnabled(true) && !forceNotification)
                return

            var logTag = logTag ?: LOG_TAG

            if (showToast)
                Logger.showToast(currentPackageContext, notificationTextString, true)

            // Send a notification to show the crash log which when clicked will open the ReportActivity
            var title = title
            if (title == null || title.toString().isEmpty())
                title = TermuxConstants.TERMUX_APP_NAME + " Crash Report"

            Logger.logDebug(logTag, "Sending \"" + title + "\" notification.")

            val reportString = StringBuilder(message)

            if (appInfoMode != null)
                reportString.append("\n\n").append(FableUtils.getAppInfoMarkdownString(currentPackageContext, appInfoMode, currentPackageName))

            if (addDeviceInfo)
                reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(currentPackageContext, true))

            val userActionName = UserAction.CRASH_REPORT.getName()

            val reportInfo = ReportInfo(userActionName, logTag, title.toString())
            reportInfo.setReportString(reportString.toString())
            reportInfo.setReportStringSuffix("\n\n" + FableUtils.getReportIssueMarkdownString(currentPackageContext))
            reportInfo.setAddReportInfoHeaderToMarkdown(true)
            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                Environment.getExternalStorageDirectory().toString() + "/" +
                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true))

            val result = ReportActivity.newInstance(fablePackageContext, reportInfo)
            if (result.contentIntent == null) return

            // Must ensure result code for PendingIntents and id for notification are unique otherwise will override previous
            val nextNotificationId = FableNotificationUtils.getNextNotificationId(fablePackageContext)

            // 工单 05：targetSdk 31+ 必须显式指定 PendingIntent 可变性；通知点击/删除意图用 IMMUTABLE。
            val contentIntent = PendingIntent.getActivity(fablePackageContext, nextNotificationId, result.contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            var deleteIntent: PendingIntent? = null
            if (result.deleteIntent != null)
                deleteIntent = PendingIntent.getBroadcast(fablePackageContext, nextNotificationId, result.deleteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            // Setup the notification channel if not already set up
            setupCrashReportsNotificationChannel(fablePackageContext)

            // Use markdown in notification
            val notificationTextCharSequence = MarkdownUtils.getSpannedMarkdownText(fablePackageContext, notificationTextString)
            //CharSequence notificationTextCharSequence = notificationTextString;

            // Build the notification
            val builder = getCrashReportsNotificationBuilder(currentPackageContext, fablePackageContext,
                title, notificationTextCharSequence, notificationTextCharSequence, contentIntent, deleteIntent,
                NotificationUtils.NOTIFICATION_MODE_VIBRATE)
            if (builder == null) return

            // Send the notification
            val notificationManager = NotificationUtils.getNotificationManager(fablePackageContext)
            if (notificationManager != null)
                notificationManager.notify(nextNotificationId, builder.build())
        }

        /**
         * Get [Notification.Builder] for crash reports notification channel.
         */
        @Nullable
        @JvmStatic
        fun getCrashReportsNotificationBuilder(currentPackageContext: Context,
                                               fablePackageContext: Context,
                                               title: CharSequence?,
                                               notificationText: CharSequence?,
                                               notificationBigText: CharSequence?,
                                               contentIntent: PendingIntent?,
                                               deleteIntent: PendingIntent?,
                                               notificationMode: Int): Notification.Builder? {
            return FableNotificationUtils.getFableOrPluginAppNotificationBuilder(
                currentPackageContext, fablePackageContext,
                TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH,
                title, notificationText, notificationBigText, contentIntent, deleteIntent, notificationMode)
        }

        /**
         * Setup the notification channel for crash reports.
         */
        @JvmStatic
        fun setupCrashReportsNotificationChannel(context: Context?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            NotificationUtils.setupNotificationChannel(context, TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID,
                context!!.getString(R.string.fable_crash_reports_notification_channel_name), NotificationManager.IMPORTANCE_HIGH)
        }
    }
}
