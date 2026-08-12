package com.gph.fable.shared.termux.settings.preferences

import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP
import com.gph.fable.shared.theme.NightMode

/**
 * A class that defines shared constants of the SharedPreferences used by Fable app and its plugins.
 */
object TermuxPreferenceConstants {

    /**
     * Fable app constants.
     */
    object TERMUX_APP {

        /**
         * Defines the key for whether terminal view margin adjustment is enabled or not.
         */
        const val KEY_TERMINAL_MARGIN_ADJUSTMENT = "terminal_margin_adjustment"
        const val DEFAULT_TERMINAL_MARGIN_ADJUSTMENT = true

        /**
         * Defines the key for whether to show terminal toolbar containing extra keys and text input field.
         */
        const val KEY_SHOW_TERMINAL_TOOLBAR = "show_extra_keys"
        const val DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR = true

        /**
         * Defines the key for whether the soft keyboard will be enabled.
         */
        const val KEY_SOFT_KEYBOARD_ENABLED = "soft_keyboard_enabled"
        const val DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED = true

        /**
         * Defines the key for whether the soft keyboard will be enabled only if no hardware keyboard attached.
         */
        const val KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = "soft_keyboard_enabled_only_if_no_hardware"
        const val DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE = false

        /**
         * Defines the key for whether to always keep screen on.
         */
        const val KEY_KEEP_SCREEN_ON = "screen_always_on"
        const val DEFAULT_VALUE_KEEP_SCREEN_ON = false

        /**
         * Defines the key for font size of terminal view.
         */
        const val KEY_FONTSIZE = "fontsize"

        /**
         * Defines the key for current terminal session.
         */
        const val KEY_CURRENT_SESSION = "current_session"

        /**
         * Defines the key for Fable 界面主题（跟随系统/浅色/深色，工单 04）。
         */
        const val KEY_FABLE_THEME_MODE = "fable_theme_mode"

        @JvmField
        val DEFAULT_VALUE_FABLE_THEME_MODE: String = NightMode.SYSTEM.getName()

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"

        /**
         * Defines the key for last used notification id.
         */
        const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
        const val DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID = 0

        /**
         * The app shell number since boot.
         */
        const val KEY_APP_SHELL_NUMBER_SINCE_BOOT = "app_shell_number_since_boot"
        const val DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT = 0

        /**
         * The terminal session number since boot.
         */
        const val KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT = "terminal_session_number_since_boot"
        const val DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT = 0

        /**
         * Defines the key for whether terminal view key logging is enabled or not.
         */
        const val KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED = "terminal_view_key_logging_enabled"
        const val DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED = false

        /**
         * Defines the key for whether flashes and notifications for plugin errors are enabled or not.
         */
        const val KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED = "plugin_error_notifications_enabled"
        const val DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED = true

        /**
         * Defines the key for whether notifications for crash reports are enabled or not.
         */
        const val KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED = "crash_report_notifications_enabled"
        const val DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED = true
    }

    /**
     * Fable:API app constants.
     */
    object TERMUX_API_APP {

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"

        /**
         * Defines the key for last used PendingIntent request code.
         */
        const val KEY_LAST_PENDING_INTENT_REQUEST_CODE = "last_pending_intent_request_code"
        const val DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE = 0
    }

    /**
     * Fable:Boot app constants.
     */
    object TERMUX_BOOT_APP {

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"
    }

    /**
     * Fable:Float app constants.
     */
    object TERMUX_FLOAT_APP {

        /**
         * The float window x coordinate.
         */
        const val KEY_WINDOW_X = "window_x"

        /**
         * The float window y coordinate.
         */
        const val KEY_WINDOW_Y = "window_y"

        /**
         * The float window width.
         */
        const val KEY_WINDOW_WIDTH = "window_width"

        /**
         * The float window height.
         */
        const val KEY_WINDOW_HEIGHT = "window_height"

        /**
         * Defines the key for font size of terminal view.
         */
        const val KEY_FONTSIZE = "fontsize"

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"

        /**
         * Defines the key for whether terminal view key logging is enabled or not.
         */
        const val KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED = "terminal_view_key_logging_enabled"
        const val DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED = false
    }

    /**
     * Fable:Styling app constants.
     */
    object TERMUX_STYLING_APP {

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"
    }

    /**
     * Fable:Tasker app constants.
     */
    object TERMUX_TASKER_APP {

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"

        /**
         * Defines the key for last used PendingIntent request code.
         */
        const val KEY_LAST_PENDING_INTENT_REQUEST_CODE = "last_pending_intent_request_code"
        const val DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE = 0
    }

    /**
     * Fable:Widget app constants.
     */
    object TERMUX_WIDGET_APP {

        /**
         * Defines the key for current log level.
         */
        const val KEY_LOG_LEVEL = "log_level"

        /**
         * Defines the key for current token for shortcuts.
         */
        const val KEY_TOKEN = "token"
    }
}
