package com.gph.fable.shared.crash

import android.content.Context
import androidx.annotation.NonNull
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.markdown.MarkdownUtils
import com.gph.fable.shared.errors.Error
import com.gph.fable.shared.android.AndroidUtils
import java.nio.charset.Charset

/**
 * Catches uncaught exceptions and logs them.
 */
class CrashHandler private constructor(
    @NonNull private val mContext: Context,
    @NonNull private val mCrashHandlerClient: CrashHandlerClient,
    private val mIsDefaultHandler: Boolean
) : Thread.UncaughtExceptionHandler {

    private val mDefaultUEH: Thread.UncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(@NonNull thread: Thread, @NonNull throwable: Throwable) {
        Logger.logInfo(LOG_TAG, "uncaughtException() for " + thread + ": " + throwable.message)
        logCrash(thread, throwable)

        // Don't stop the app if not on the main thread
        if (mIsDefaultHandler)
            mDefaultUEH.uncaughtException(thread, throwable)
    }

    fun logCrash(@NonNull thread: Thread, @NonNull throwable: Throwable) {
        if (!mCrashHandlerClient.onPreLogCrash(mContext, thread, throwable)) {
            logCrashToFile(mContext, mCrashHandlerClient, thread, throwable)
            mCrashHandlerClient.onPostLogCrash(mContext, thread, throwable)
        }
    }

    fun logCrashToFile(@NonNull context: Context,
                       @NonNull crashHandlerClient: CrashHandlerClient,
                       @NonNull thread: Thread, @NonNull throwable: Throwable) {
        val reportString = StringBuilder()

        reportString.append("## Crash Details\n")
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Thread", thread.toString(), "-"))
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Timestamp", AndroidUtils.getCurrentMilliSecondUTCTimeStamp(), "-"))
        reportString.append("\n\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Crash Message", throwable.message, "-"))
        reportString.append("\n\n").append(Logger.getStackTracesMarkdownString("Stacktrace", Logger.getStackTracesStringArray(throwable)))

        val appInfoMarkdownString = crashHandlerClient.getAppInfoMarkdownString(context)
        if (appInfoMarkdownString != null && !appInfoMarkdownString.isEmpty())
            reportString.append("\n\n").append(appInfoMarkdownString)

        reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context))

        // Log report string to logcat
        Logger.logError(reportString.toString())

        // Write report string to crash log file
        val error = FileUtils.writeTextToFile("crash log", crashHandlerClient.getCrashLogFilePath(context),
            Charset.defaultCharset(), reportString.toString(), false)
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString())
        }
    }

    interface CrashHandlerClient {

        /**
         * Called before [logCrashToFile] is called.
         *
         * @return Should return `true` if crash has been handled and should not be logged,
         * otherwise `false`.
         */
        fun onPreLogCrash(context: Context, thread: Thread, throwable: Throwable): Boolean

        /**
         * Called after [logCrashToFile] is called.
         */
        fun onPostLogCrash(context: Context, thread: Thread, throwable: Throwable)

        /**
         * Get crash log file path.
         */
        @NonNull
        fun getCrashLogFilePath(context: Context): String

        /**
         * Get app info markdown string to add to crash log.
         */
        fun getAppInfoMarkdownString(context: Context): String?

    }

    companion object {
        private const val LOG_TAG = "CrashUtils"

        /**
         * Set default uncaught crash handler for the app to [CrashHandler].
         */
        @JvmStatic
        fun setDefaultCrashHandler(@NonNull context: Context, @NonNull crashHandlerClient: CrashHandlerClient) {
            if (!(Thread.getDefaultUncaughtExceptionHandler() is CrashHandler)) {
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, crashHandlerClient, true))
            }
        }

        /**
         * Set uncaught crash handler of current non-main thread to [CrashHandler].
         */
        @JvmStatic
        fun setCrashHandler(@NonNull context: Context, @NonNull crashHandlerClient: CrashHandlerClient) {
            Thread.currentThread().setUncaughtExceptionHandler(CrashHandler(context, crashHandlerClient, false))
        }

        /**
         * Get [CrashHandler] instance that can be set as uncaught crash handler of a non-main thread.
         */
        @JvmStatic
        fun getCrashHandler(@NonNull context: Context, @NonNull crashHandlerClient: CrashHandlerClient): CrashHandler {
            return CrashHandler(context, crashHandlerClient, false)
        }

        /**
         * Log a crash in the crash log file.
         */
        @JvmStatic
        fun logCrash(@NonNull context: Context,
                     @NonNull crashHandlerClient: CrashHandlerClient,
                     @NonNull thread: Thread, @NonNull throwable: Throwable) {
            Logger.logInfo(LOG_TAG, "logCrash() for " + thread + ": " + throwable.message)
            CrashHandler(context, crashHandlerClient, false).logCrash(thread, throwable)
        }
    }
}
