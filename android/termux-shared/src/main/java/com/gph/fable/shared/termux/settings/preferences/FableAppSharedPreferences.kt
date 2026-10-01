package com.gph.fable.shared.termux.settings.preferences

import android.content.Context
import android.util.TypedValue
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.android.PackageUtils
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.settings.preferences.AppSharedPreferences
import com.gph.fable.shared.settings.preferences.SharedPreferenceUtils
import com.gph.fable.shared.termux.FableUtils
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP

class FableAppSharedPreferences private constructor(@NonNull context: Context) :
    AppSharedPreferences(
        context,
        SharedPreferenceUtils.getPrivateSharedPreferences(context,
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    ) {

    private var MIN_FONTSIZE: Int = 0
    private var MAX_FONTSIZE: Int = 0
    private var DEFAULT_FONTSIZE: Int = 0

    init {
        setFontVariables(context)
    }

    fun shouldShowTerminalToolbar(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR)
    }

    fun setShowTerminalToolbar(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, value, false)
    }

    fun toogleShowTerminalToolbar(): Boolean {
        val currentValue = shouldShowTerminalToolbar()
        setShowTerminalToolbar(!currentValue)
        return !currentValue
    }

    fun isTerminalMarginAdjustmentEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, TERMUX_APP.DEFAULT_TERMINAL_MARGIN_ADJUSTMENT)
    }

    fun setTerminalMarginAdjustment(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, value, false)
    }

    fun isSoftKeyboardEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED)
    }

    fun setSoftKeyboardEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, value, false)
    }

    fun isSoftKeyboardEnabledOnlyIfNoHardware(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE)
    }

    fun setSoftKeyboardEnabledOnlyIfNoHardware(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value, false)
    }

    fun shouldKeepScreenOn(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON)
    }

    fun setKeepScreenOn(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, value, false)
    }

    fun setFontVariables(context: Context) {
        val sizes = getDefaultFontSizes(context)

        DEFAULT_FONTSIZE = sizes[0]
        MIN_FONTSIZE = sizes[1]
        MAX_FONTSIZE = sizes[2]
    }

    fun getFontSize(): Int {
        val fontSize = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE)
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE)
    }

    fun setFontSize(value: Int) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, value, false)
    }

    fun changeFontSize(increase: Boolean) {
        var fontSize = getFontSize()

        fontSize += (if (increase) 1 else -1) * 2
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE))

        setFontSize(fontSize)
    }

    fun getCurrentSession(): String? {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, null, true)
    }

    fun setCurrentSession(value: String?) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, value, false)
    }

    /** 界面主题模式：system/light/dark；未设置过返回 null（回退 termux.properties night-mode）。 */
    @Nullable
    fun getThemeMode(): String? {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_FABLE_THEME_MODE, null, true)
    }

    /** 设置界面主题模式：system/light/dark。 */
    fun setThemeMode(value: String?) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_FABLE_THEME_MODE, value, false)
    }

    /** 字号（dp 视角，供设置页滑块显示；存量值按 px/density 换算保持兼容）。 */
    fun getFontSizeDp(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return Math.round(getFontSize() / density)
    }

    /** 设置字号（dp → px 存储，与既有 KEY_FONTSIZE px 语义一致）。 */
    fun setFontSizeDp(context: Context, sizeDp: Int) {
        val density = context.resources.displayMetrics.density
        setFontSize(Math.round(sizeDp * density))
    }

    fun getLogLevel(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
    }

    fun setLogLevel(context: Context, logLevel: Int) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, logLevel, false)
    }

    fun getLastNotificationId(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID)
    }

    fun setLastNotificationId(notificationId: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, notificationId, false)
    }

    @Synchronized
    fun getAndIncrementAppShellNumberSinceBoot(): Int {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true, Integer.MAX_VALUE)
    }

    @Synchronized
    fun resetAppShellNumberSinceBoot() {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true)
    }

    @Synchronized
    fun getAndIncrementTerminalSessionNumberSinceBoot(): Int {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell
        return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true, Integer.MAX_VALUE)
    }

    @Synchronized
    fun resetTerminalSessionNumberSinceBoot() {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
            TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true)
    }

    fun isTerminalViewKeyLoggingEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TERMUX_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED)
    }

    fun setTerminalViewKeyLoggingEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, false)
    }

    fun arePluginErrorNotificationsEnabled(readFromFile: Boolean): Boolean {
        return if (readFromFile)
            SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED)
        else
            SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED)
    }

    fun setPluginErrorNotificationsEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, value, false)
    }

    fun areCrashReportNotificationsEnabled(readFromFile: Boolean): Boolean {
        return if (readFromFile)
            SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED)
        else
            SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED)
    }

    fun setCrashReportNotificationsEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, value, false)
    }

    companion object {
        private const val LOG_TAG = "FableAppSharedPreferences"

        /**
         * Get [FableAppSharedPreferences].
         */
        @Nullable
        @JvmStatic
        fun build(@NonNull context: Context): FableAppSharedPreferences? {
            val fablePackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME)
            return if (fablePackageContext == null)
                null
            else
                FableAppSharedPreferences(fablePackageContext)
        }

        /**
         * Get [FableAppSharedPreferences].
         */
        @JvmStatic
        fun build(@NonNull context: Context, exitAppOnError: Boolean): FableAppSharedPreferences? {
            val fablePackageContext = FableUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_PACKAGE_NAME, exitAppOnError)
            return if (fablePackageContext == null)
                null
            else
                FableAppSharedPreferences(fablePackageContext)
        }

        @JvmStatic
        fun getDefaultFontSizes(context: Context): IntArray {
            val dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics)

            val sizes = IntArray(3)

            // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
            // to prevent invisible text due to zoom be mistake:
            sizes[1] = (4f * dipInPixels).toInt() // min

            // http://www.google.com/design/spec/style/typography.html#typography-line-height
            var defaultFontSize = Math.round(12 * dipInPixels)
            // Make it divisible by 2 since that is the minimal adjustment step:
            if (defaultFontSize % 2 == 1) defaultFontSize--

            sizes[0] = defaultFontSize // default

            sizes[2] = 256 // max

            return sizes
        }
    }
}
