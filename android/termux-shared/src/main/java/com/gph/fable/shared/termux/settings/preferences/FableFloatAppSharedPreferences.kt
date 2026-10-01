package com.gph.fable.shared.termux.settings.preferences

import android.content.Context
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.settings.preferences.AppSharedPreferences
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_FLOAT_APP

class FableFloatAppSharedPreferences private constructor(@NonNull context: Context) :
    AppSharedPreferences(
        context,
        SharedPreferenceUtils.getPrivateSharedPreferences(context,
            TermuxConstants.TERMUX_FLOAT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
            TermuxConstants.TERMUX_FLOAT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    ) {

    private var MIN_FONTSIZE: Int = 0
    private var MAX_FONTSIZE: Int = 0
    private var DEFAULT_FONTSIZE: Int = 0

    init {
        setFontVariables(context)
    }

    fun getWindowX(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_X, 200)
    }

    fun setWindowX(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_X, value, false)
    }

    fun getWindowY(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_Y, 200)
    }

    fun setWindowY(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_Y, value, false)
    }

    fun getWindowWidth(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_WIDTH, 500)
    }

    fun setWindowWidth(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_WIDTH, value, false)
    }

    fun getWindowHeight(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_HEIGHT, 500)
    }

    fun setWindowHeight(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_WINDOW_HEIGHT, value, false)
    }

    fun setFontVariables(context: Context) {
        val sizes = FableAppSharedPreferences.getDefaultFontSizes(context)

        DEFAULT_FONTSIZE = sizes[0]
        MIN_FONTSIZE = sizes[1]
        MAX_FONTSIZE = sizes[2]
    }

    fun getFontSize(): Int {
        val fontSize = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TERMUX_FLOAT_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE)
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE)
    }

    fun setFontSize(value: Int) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TERMUX_FLOAT_APP.KEY_FONTSIZE, value, false)
    }

    fun changeFontSize(increase: Boolean) {
        var fontSize = getFontSize()

        fontSize += (if (increase) 1 else -1) * 2
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE))

        setFontSize(fontSize)
    }

    fun getLogLevel(readFromFile: Boolean): Int {
        return if (readFromFile)
            SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_FLOAT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        else
            SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
    }

    fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_FLOAT_APP.KEY_LOG_LEVEL, logLevel, commitToFile)
    }

    fun isTerminalViewKeyLoggingEnabled(readFromFile: Boolean): Boolean {
        return if (readFromFile)
            SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_FLOAT_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TERMUX_FLOAT_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED)
        else
            SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_FLOAT_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TERMUX_FLOAT_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED)
    }

    fun setTerminalViewKeyLoggingEnabled(value: Boolean, commitToFile: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_FLOAT_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, commitToFile)
    }

    companion object {
        private const val LOG_TAG = "FableFloatAppSharedPreferences"

        /**
         * Get [FableFloatAppSharedPreferences].
         */
        @Nullable
        @JvmStatic
        fun build(@NonNull context: Context): FableFloatAppSharedPreferences? {
            val fableFloatPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME)
            return if (fableFloatPackageContext == null)
                null
            else
                FableFloatAppSharedPreferences(fableFloatPackageContext)
        }

        /**
         * Get [FableFloatAppSharedPreferences].
         */
        @JvmStatic
        fun build(@NonNull context: Context, exitAppOnError: Boolean): FableFloatAppSharedPreferences? {
            val fableFloatPackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME, exitAppOnError)
            return if (fableFloatPackageContext == null)
                null
            else
                FableFloatAppSharedPreferences(fableFloatPackageContext)
        }
    }
}
