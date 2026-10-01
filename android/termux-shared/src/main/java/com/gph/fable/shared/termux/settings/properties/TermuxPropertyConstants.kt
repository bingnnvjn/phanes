package com.gph.fable.shared.termux.settings.properties

import com.google.common.collect.ImmutableBiMap
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.theme.NightMode
import com.gph.fable.view.TerminalView
import java.util.Arrays
import java.util.HashSet

/**
 * A class that defines shared constants of the SharedProperties used by Fable app and its plugins.
 */
object TermuxPropertyConstants {

    private const val LOG_TAG = "TermuxPropertyConstants"

    /* boolean */

    /** Defines the key for whether file share receiver of the app is enabled. */
    const val KEY_DISABLE_FILE_SHARE_RECEIVER = "disable-file-share-receiver"

    /** Defines the key for whether file view receiver of the app is enabled. */
    const val KEY_DISABLE_FILE_VIEW_RECEIVER = "disable-file-view-receiver"

    /** Defines the key for whether hardware keyboard shortcuts are enabled. */
    const val KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS = "disable-hardware-keyboard-shortcuts"

    /** Defines the key for whether a toast will be shown when user changes the terminal session. */
    const val KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST = "disable-terminal-session-change-toast"

    /** Defines the key for whether to enforce character based input. */
    const val KEY_ENFORCE_CHAR_BASED_INPUT = "enforce-char-based-input"

    /** Defines the key for whether text for the extra keys buttons should be all capitalized automatically. */
    const val KEY_EXTRA_KEYS_TEXT_ALL_CAPS = "extra-keys-text-all-caps"

    /** Defines the key for whether to hide soft keyboard when app is started. */
    const val KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP = "hide-soft-keyboard-on-startup"

    /** Defines the key for whether the am socket server should be run at app startup. */
    const val KEY_RUN_TERMUX_AM_SOCKET_SERVER = "run-termux-am-socket-server"

    /** Defines the key for whether url links in terminal transcript will automatically open on click or on tap. */
    const val KEY_TERMINAL_ONCLICK_URL_OPEN = "terminal-onclick-url-open"

    /** Defines the key for whether to use black UI. */
    @Deprecated("")
    const val KEY_USE_BLACK_UI = "use-black-ui"

    /** Defines the key for whether to use ctrl space workaround. */
    const val KEY_USE_CTRL_SPACE_WORKAROUND = "ctrl-space-workaround"

    /** Defines the key for whether to use fullscreen. */
    const val KEY_USE_FULLSCREEN = "fullscreen"

    /** Defines the key for whether to use fullscreen workaround. */
    const val KEY_USE_FULLSCREEN_WORKAROUND = "use-fullscreen-workaround"

    /* int */

    /** Defines the key for the bell behaviour. */
    const val KEY_BELL_BEHAVIOUR = "bell-character"

    const val VALUE_BELL_BEHAVIOUR_VIBRATE = "vibrate"
    const val VALUE_BELL_BEHAVIOUR_BEEP = "beep"
    const val VALUE_BELL_BEHAVIOUR_IGNORE = "ignore"
    const val DEFAULT_VALUE_BELL_BEHAVIOUR = VALUE_BELL_BEHAVIOUR_VIBRATE

    const val IVALUE_BELL_BEHAVIOUR_VIBRATE = 1
    const val IVALUE_BELL_BEHAVIOUR_BEEP = 2
    const val IVALUE_BELL_BEHAVIOUR_IGNORE = 3
    const val DEFAULT_IVALUE_BELL_BEHAVIOUR = IVALUE_BELL_BEHAVIOUR_VIBRATE

    /** Defines the bidirectional map for bell behaviour values and their internal values. */
    @JvmField
    val MAP_BELL_BEHAVIOUR: ImmutableBiMap<String, Int> =
        ImmutableBiMap.Builder<String, Int>()
            .put(VALUE_BELL_BEHAVIOUR_VIBRATE, IVALUE_BELL_BEHAVIOUR_VIBRATE)
            .put(VALUE_BELL_BEHAVIOUR_BEEP, IVALUE_BELL_BEHAVIOUR_BEEP)
            .put(VALUE_BELL_BEHAVIOUR_IGNORE, IVALUE_BELL_BEHAVIOUR_IGNORE)
            .build()

    /** Defines the key for the terminal cursor blink rate. */
    const val KEY_TERMINAL_CURSOR_BLINK_RATE = "terminal-cursor-blink-rate"
    @JvmField
    val IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN: Int = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN
    @JvmField
    val IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX: Int = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX
    const val DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE = 0

    /** Defines the key for the terminal cursor style. */
    const val KEY_TERMINAL_CURSOR_STYLE = "terminal-cursor-style"

    const val VALUE_TERMINAL_CURSOR_STYLE_BLOCK = "block"
    const val VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = "underline"
    const val VALUE_TERMINAL_CURSOR_STYLE_BAR = "bar"

    // 工单 31：旧 TerminalEmulator 常量迁移为字面量（DECSCUSR 值 0/1/2）。
    const val IVALUE_TERMINAL_CURSOR_STYLE_BLOCK = 0
    const val IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = 1
    const val IVALUE_TERMINAL_CURSOR_STYLE_BAR = 2
    const val DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE = IVALUE_TERMINAL_CURSOR_STYLE_BLOCK

    /** Defines the bidirectional map for terminal cursor styles and their internal values. */
    @JvmField
    val MAP_TERMINAL_CURSOR_STYLE: ImmutableBiMap<String, Int> =
        ImmutableBiMap.Builder<String, Int>()
            .put(VALUE_TERMINAL_CURSOR_STYLE_BLOCK, IVALUE_TERMINAL_CURSOR_STYLE_BLOCK)
            .put(VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE)
            .put(VALUE_TERMINAL_CURSOR_STYLE_BAR, IVALUE_TERMINAL_CURSOR_STYLE_BAR)
            .build()

    /**
     * Defines the key for how many days old the access time should be of files that should be
     * deleted from $TMPDIR on exit.
     */
    const val KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = "delete-tmpdir-files-older-than-x-days-on-exit"
    const val IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN = -1
    const val IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX = 100000
    const val DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = 3

    /** Defines the key for the terminal margin on left and right in dp units. */
    const val KEY_TERMINAL_MARGIN_HORIZONTAL = "terminal-margin-horizontal"
    const val IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN = 0
    const val IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX = 100
    const val DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL = 3

    /** Defines the key for the terminal margin on top and bottom in dp units. */
    const val KEY_TERMINAL_MARGIN_VERTICAL = "terminal-margin-vertical"
    const val IVALUE_TERMINAL_MARGIN_VERTICAL_MIN = 0
    const val IVALUE_TERMINAL_MARGIN_VERTICAL_MAX = 100
    const val DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL = 0

    /** Defines the key for the terminal transcript rows. */
    const val KEY_TERMINAL_TRANSCRIPT_ROWS = "terminal-transcript-rows"
    // 工单 31：旧 TerminalEmulator 常量迁移为字面量。
    const val IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN = 100
    const val IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX = 50000
    const val DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS = 2000

    /* float */

    /** Defines the key for the terminal toolbar height. */
    const val KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = "terminal-toolbar-height"
    const val IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN = 0.4f
    const val IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX = 3f
    const val DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = 1f

    /* Integer */

    /** Defines the key for create session shortcut. */
    const val KEY_SHORTCUT_CREATE_SESSION = "shortcut.create-session"
    /** Defines the key for next session shortcut. */
    const val KEY_SHORTCUT_NEXT_SESSION = "shortcut.next-session"
    /** Defines the key for previous session shortcut. */
    const val KEY_SHORTCUT_PREVIOUS_SESSION = "shortcut.previous-session"
    /** Defines the key for rename session shortcut. */
    const val KEY_SHORTCUT_RENAME_SESSION = "shortcut.rename-session"

    const val ACTION_SHORTCUT_CREATE_SESSION = 1
    const val ACTION_SHORTCUT_NEXT_SESSION = 2
    const val ACTION_SHORTCUT_PREVIOUS_SESSION = 3
    const val ACTION_SHORTCUT_RENAME_SESSION = 4

    /** Defines the bidirectional map for session shortcut values and their internal actions. */
    @JvmField
    val MAP_SESSION_SHORTCUTS: ImmutableBiMap<String, Int> =
        ImmutableBiMap.Builder<String, Int>()
            .put(KEY_SHORTCUT_CREATE_SESSION, ACTION_SHORTCUT_CREATE_SESSION)
            .put(KEY_SHORTCUT_NEXT_SESSION, ACTION_SHORTCUT_NEXT_SESSION)
            .put(KEY_SHORTCUT_PREVIOUS_SESSION, ACTION_SHORTCUT_PREVIOUS_SESSION)
            .put(KEY_SHORTCUT_RENAME_SESSION, ACTION_SHORTCUT_RENAME_SESSION)
            .build()

    /* String */

    /** Defines the key for whether back key will behave as escape key or literal back key. */
    const val KEY_BACK_KEY_BEHAVIOUR = "back-key"

    const val IVALUE_BACK_KEY_BEHAVIOUR_BACK = "back"
    const val IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE = "escape"
    const val DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR = IVALUE_BACK_KEY_BEHAVIOUR_BACK

    /** Defines the bidirectional map for back key behaviour values and their internal values. */
    @JvmField
    val MAP_BACK_KEY_BEHAVIOUR: ImmutableBiMap<String, String> =
        ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_BACK_KEY_BEHAVIOUR_BACK, IVALUE_BACK_KEY_BEHAVIOUR_BACK)
            .put(IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE, IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE)
            .build()

    /** Defines the key for the default working directory. */
    const val KEY_DEFAULT_WORKING_DIRECTORY = "default-working-directory"
    /** Defines the default working directory. */
    @JvmField
    val DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY: String = TermuxConstants.TERMUX_HOME_DIR_PATH

    /** Defines the key for extra keys. */
    const val KEY_EXTRA_KEYS = "extra-keys"
    const val DEFAULT_IVALUE_EXTRA_KEYS = "[['ESC',{key: 'DRAWER', popup: 'PASTE'},'SCROLL','HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"

    /** Defines the key for extra keys style. */
    const val KEY_EXTRA_KEYS_STYLE = "extra-keys-style"
    const val DEFAULT_IVALUE_EXTRA_KEYS_STYLE = "default"

    /** Defines the key for [NightMode]. */
    const val KEY_NIGHT_MODE = "night-mode"

    @JvmField
    val IVALUE_NIGHT_MODE_TRUE: String = NightMode.TRUE.getName()
    @JvmField
    val IVALUE_NIGHT_MODE_FALSE: String = NightMode.FALSE.getName()
    @JvmField
    val IVALUE_NIGHT_MODE_SYSTEM: String = NightMode.SYSTEM.getName()
    @JvmField
    val DEFAULT_IVALUE_NIGHT_MODE: String = IVALUE_NIGHT_MODE_SYSTEM

    /** Defines the bidirectional map for [NightMode] values and their internal values. */
    @JvmField
    val MAP_NIGHT_MODE: ImmutableBiMap<String, String> =
        ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_NIGHT_MODE_TRUE, IVALUE_NIGHT_MODE_TRUE)
            .put(IVALUE_NIGHT_MODE_FALSE, IVALUE_NIGHT_MODE_FALSE)
            .put(IVALUE_NIGHT_MODE_SYSTEM, IVALUE_NIGHT_MODE_SYSTEM)
            .build()

    /** Defines the key for whether toggle soft keyboard request will show/hide or enable/disable keyboard. */
    const val KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = "soft-keyboard-toggle-behaviour"

    const val IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE = "show/hide"
    const val IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE = "enable/disable"
    const val DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE

    /** Defines the bidirectional map for toggle soft keyboard behaviour values and their internal values. */
    @JvmField
    val MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR: ImmutableBiMap<String, String> =
        ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE)
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE)
            .build()

    /** Defines the key for whether volume keys will behave as virtual or literal volume keys. */
    const val KEY_VOLUME_KEYS_BEHAVIOUR = "volume-keys"

    const val IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL = "virtual"
    const val IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME = "volume"
    const val DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR = IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL

    /** Defines the bidirectional map for volume keys behaviour values and their internal values. */
    @JvmField
    val MAP_VOLUME_KEYS_BEHAVIOUR: ImmutableBiMap<String, String> =
        ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL, IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL)
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME, IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME)
            .build()

    /** Defines the set for keys loaded by termux. */
    @JvmField
    val TERMUX_APP_PROPERTIES_LIST: Set<String> = HashSet(Arrays.asList(
        /* boolean */
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS,

        /* int */
        KEY_BELL_BEHAVIOUR,
        KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
        KEY_TERMINAL_CURSOR_BLINK_RATE,
        KEY_TERMINAL_CURSOR_STYLE,
        KEY_TERMINAL_MARGIN_HORIZONTAL,
        KEY_TERMINAL_MARGIN_VERTICAL,
        KEY_TERMINAL_TRANSCRIPT_ROWS,

        /* float */
        KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,

        /* Integer */
        KEY_SHORTCUT_CREATE_SESSION,
        KEY_SHORTCUT_NEXT_SESSION,
        KEY_SHORTCUT_PREVIOUS_SESSION,
        KEY_SHORTCUT_RENAME_SESSION,

        /* String */
        KEY_BACK_KEY_BEHAVIOUR,
        KEY_DEFAULT_WORKING_DIRECTORY,
        KEY_EXTRA_KEYS,
        KEY_EXTRA_KEYS_STYLE,
        KEY_NIGHT_MODE,
        KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
        KEY_VOLUME_KEYS_BEHAVIOUR
    ))

    /** Defines the set for keys loaded by termux that have default boolean behaviour with false as default. */
    @JvmField
    val TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: Set<String> = HashSet(Arrays.asList(
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS
    ))

    /** Defines the set for keys loaded by termux that have default boolean behaviour with true as default. */
    @JvmField
    val TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: Set<String> = HashSet(Arrays.asList(
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER
    ))

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with false as default. */
    @JvmField
    val TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: Set<String> = HashSet(Arrays.asList<String>())

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with true as default. */
    @JvmField
    val TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: Set<String> = HashSet(Arrays.asList<String>())
}
