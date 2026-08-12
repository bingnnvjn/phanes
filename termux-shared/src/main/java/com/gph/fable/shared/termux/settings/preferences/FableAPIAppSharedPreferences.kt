package com.gph.fable.shared.termux.settings.preferences

import android.content.Context
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.settings.preferences.AppSharedPreferences
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_API_APP

class FableAPIAppSharedPreferences private constructor(@NonNull context: Context) :
    AppSharedPreferences(
        context,
        SharedPreferenceUtils.getPrivateSharedPreferences(context,
            TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
            TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    ) {

    fun getLogLevel(readFromFile: Boolean): Int {
        return if (readFromFile)
            SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        else
            SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
    }

    fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, logLevel, commitToFile)
    }

    fun getLastPendingIntentRequestCode(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE)
    }

    fun setLastPendingIntentRequestCode(lastPendingIntentRequestCode: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, lastPendingIntentRequestCode, true)
    }

    companion object {
        private const val LOG_TAG = "FableAPIAppSharedPreferences"

        /**
         * Get [FableAPIAppSharedPreferences].
         */
        @Nullable
        @JvmStatic
        fun build(@NonNull context: Context): FableAPIAppSharedPreferences? {
            val fableAPIPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_API_PACKAGE_NAME)
            return if (fableAPIPackageContext == null)
                null
            else
                FableAPIAppSharedPreferences(fableAPIPackageContext)
        }

        /**
         * Get [FableAPIAppSharedPreferences].
         */
        @JvmStatic
        fun build(@NonNull context: Context, exitAppOnError: Boolean): FableAPIAppSharedPreferences? {
            val fableAPIPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_API_PACKAGE_NAME, exitAppOnError)
            return if (fableAPIPackageContext == null)
                null
            else
                FableAPIAppSharedPreferences(fableAPIPackageContext)
        }
    }
}
