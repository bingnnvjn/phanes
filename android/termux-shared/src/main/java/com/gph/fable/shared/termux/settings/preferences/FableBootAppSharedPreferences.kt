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
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_BOOT_APP

class FableBootAppSharedPreferences private constructor(@NonNull context: Context) :
    AppSharedPreferences(
        context,
        SharedPreferenceUtils.getPrivateSharedPreferences(context,
            TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
            TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    ) {

    fun getLogLevel(readFromFile: Boolean): Int {
        return if (readFromFile)
            SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        else
            SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
    }

    fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_BOOT_APP.KEY_LOG_LEVEL, logLevel, commitToFile)
    }

    companion object {
        private const val LOG_TAG = "FableBootAppSharedPreferences"

        /**
         * Get [FableBootAppSharedPreferences].
         */
        @Nullable
        @JvmStatic
        fun build(@NonNull context: Context): FableBootAppSharedPreferences? {
            val fableBootPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME)
            return if (fableBootPackageContext == null)
                null
            else
                FableBootAppSharedPreferences(fableBootPackageContext)
        }

        /**
         * Get [FableBootAppSharedPreferences].
         */
        @JvmStatic
        fun build(@NonNull context: Context, exitAppOnError: Boolean): FableBootAppSharedPreferences? {
            val fableBootPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME, exitAppOnError)
            return if (fableBootPackageContext == null)
                null
            else
                FableBootAppSharedPreferences(fableBootPackageContext)
        }
    }
}
