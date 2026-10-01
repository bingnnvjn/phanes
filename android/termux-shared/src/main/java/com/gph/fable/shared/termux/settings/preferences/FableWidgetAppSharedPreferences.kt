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
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_WIDGET_APP
import java.util.UUID

class FableWidgetAppSharedPreferences private constructor(@NonNull context: Context) :
    AppSharedPreferences(
        context,
        SharedPreferenceUtils.getPrivateSharedPreferences(context,
            TermuxConstants.TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
            TermuxConstants.TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    ) {

    fun getGeneratedToken(): String? {
        var token = SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_WIDGET_APP.KEY_TOKEN, null, true)
        if (token == null) {
            token = UUID.randomUUID().toString()
            SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_WIDGET_APP.KEY_TOKEN, token, true)
        }
        return token
    }

    fun getLogLevel(readFromFile: Boolean): Int {
        return if (readFromFile)
            SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_WIDGET_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        else
            SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_WIDGET_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
    }

    fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_WIDGET_APP.KEY_LOG_LEVEL, logLevel, commitToFile)
    }

    companion object {
        private const val LOG_TAG = "FableWidgetAppSharedPreferences"

        @JvmStatic
        fun getGeneratedToken(@NonNull context: Context): String? {
            val preferences = FableWidgetAppSharedPreferences.build(context, true)
            if (preferences == null) return null
            return preferences.getGeneratedToken()
        }

        /**
         * Get [FableWidgetAppSharedPreferences].
         */
        @Nullable
        @JvmStatic
        fun build(@NonNull context: Context): FableWidgetAppSharedPreferences? {
            val fableWidgetPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME)
            return if (fableWidgetPackageContext == null)
                null
            else
                FableWidgetAppSharedPreferences(fableWidgetPackageContext)
        }

        /**
         * Get [FableWidgetAppSharedPreferences].
         */
        @JvmStatic
        fun build(@NonNull context: Context, exitAppOnError: Boolean): FableWidgetAppSharedPreferences? {
            val fableWidgetPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME, exitAppOnError)
            return if (fableWidgetPackageContext == null)
                null
            else
                FableWidgetAppSharedPreferences(fableWidgetPackageContext)
        }
    }
}
