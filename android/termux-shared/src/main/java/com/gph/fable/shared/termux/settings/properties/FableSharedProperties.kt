package com.gph.fable.shared.termux.settings.properties

import android.content.Context
import androidx.annotation.NonNull
import com.gph.fable.shared.data.DataUtils
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.settings.properties.SharedProperties
import com.gph.fable.shared.settings.properties.SharedPropertiesParser
import com.gph.fable.shared.termux.TermuxConstants
import java.io.File
import java.util.HashMap
import java.util.Properties

abstract class FableSharedProperties(
    @NonNull context: Context,
    @NonNull label: String,
    propertiesFilePaths: List<String>?,
    @NonNull propertiesList: Set<String>,
    @NonNull sharedPropertiesParser: SharedPropertiesParser
) {

    @JvmField
    protected val mContext: Context
    @JvmField
    protected val mLabel: String
    @JvmField
    protected val mPropertiesFilePaths: List<String>?
    @JvmField
    protected val mPropertiesList: Set<String>
    @JvmField
    protected val mSharedPropertiesParser: SharedPropertiesParser
    @JvmField
    protected var mPropertiesFile: File? = null
    @JvmField
    protected var mSharedProperties: SharedProperties? = null

    init {
        mContext = context.applicationContext
        mLabel = label
        mPropertiesFilePaths = propertiesFilePaths
        mPropertiesList = propertiesList
        mSharedPropertiesParser = sharedPropertiesParser
        loadFablePropertiesFromDisk()
    }

    /**
     * Reload the properties from disk into an in-memory cache.
     */
    @Synchronized
    fun loadFablePropertiesFromDisk() {
        // Properties files must be searched everytime since no file may exist when constructor is
        // called or a higher priority file may have been created afterward. #2836
        mPropertiesFile = SharedProperties.getPropertiesFileFromList(mPropertiesFilePaths, LOG_TAG)
        mSharedProperties = null
        mSharedProperties = SharedProperties(mContext, mPropertiesFile, mPropertiesList, mSharedPropertiesParser)

        mSharedProperties!!.loadPropertiesFromDisk()
        dumpPropertiesToLog()
        dumpInternalPropertiesToLog()
    }

    /**
     * Get the [Properties] from the [mPropertiesFile] file.
     */
    fun getProperties(cached: Boolean): Properties? {
        return mSharedProperties!!.getProperties(cached)
    }

    /**
     * Get the [String] value for the key passed from the [mPropertiesFile] file.
     */
    fun getPropertyValue(key: String, def: String?, cached: Boolean): String? {
        return SharedProperties.getDefaultIfNull(mSharedProperties!!.getProperty(key, cached), def)
    }

    /**
     * A function to check if the value is `true` for [Properties] key read from
     * the [mPropertiesFile] file.
     */
    fun isPropertyValueTrue(key: String, cached: Boolean, logErrorOnInvalidValue: Boolean): Boolean {
        return SharedProperties.getBooleanValueForStringValue(key, getPropertyValue(key, null, cached), false, logErrorOnInvalidValue, LOG_TAG)
    }

    /**
     * A function to check if the value is `false` for [Properties] key read from
     * the [mPropertiesFile] file.
     */
    fun isPropertyValueFalse(key: String, cached: Boolean, logErrorOnInvalidValue: Boolean): Boolean {
        return SharedProperties.getInvertedBooleanValueForStringValue(key, getPropertyValue(key, null, cached), true, logErrorOnInvalidValue, LOG_TAG)
    }

    /**
     * Get the internal value [Any] in-memory cache for the [mPropertiesFile] file.
     */
    fun getInternalProperties(): Map<String, Any?> {
        return mSharedProperties!!.getInternalProperties()
    }

    /**
     * Get the internal [Any] value for the key passed from the [mPropertiesFile] file.
     */
    fun getInternalPropertyValue(key: String, cached: Boolean): Any? {
        val value: Any?
        if (cached) {
            value = mSharedProperties!!.getInternalProperty(key)
            // If the value is not null since key was found or if the value was null since the
            // object stored for the key was itself null, we detect the later by checking if the key
            // exists in the map.
            if (value != null || mSharedProperties!!.getInternalProperties().containsKey(key)) {
                return value
            } else {
                // This should not happen normally unless mMap was modified after the
                // loadFablePropertiesFromDisk() call
                // A null value can still be returned by
                // getInternalPropertyValueFromValue(Context,String,String) for some keys
                val defaultValue = getInternalFablePropertyValueFromValue(mContext, key, null)
                Logger.logWarn(LOG_TAG, "The value for \"" + key + "\" not found in SharedProperties cache, force returning default value: `" + defaultValue + "`")
                return defaultValue
            }
        } else {
            // We get the property value directly from file and return its internal value
            return getInternalFablePropertyValueFromValue(mContext, key, mSharedProperties!!.getProperty(key, false))
        }
    }

    fun shouldAllowExternalApps(): Boolean {
        return getInternalPropertyValue(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, true) as Boolean
    }

    fun isFileShareReceiverDisabled(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_FILE_SHARE_RECEIVER, true) as Boolean
    }

    fun isFileViewReceiverDisabled(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_FILE_VIEW_RECEIVER, true) as Boolean
    }

    fun areHardwareKeyboardShortcutsDisabled(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, true) as Boolean
    }

    fun areTerminalSessionChangeToastsDisabled(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, true) as Boolean
    }

    fun isEnforcingCharBasedInput(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, true) as Boolean
    }

    fun shouldExtraKeysTextBeAllCaps(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, true) as Boolean
    }

    fun shouldSoftKeyboardBeHiddenOnStartup(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, true) as Boolean
    }

    fun shouldRunFableAmSocketServer(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, true) as Boolean
    }

    fun shouldOpenTerminalTranscriptURLOnClick(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN, true) as Boolean
    }

    fun isUsingCtrlSpaceWorkaround(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_CTRL_SPACE_WORKAROUND, true) as Boolean
    }

    fun isUsingFullScreen(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_FULLSCREEN, true) as Boolean
    }

    fun isUsingFullScreenWorkAround(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_FULLSCREEN_WORKAROUND, true) as Boolean
    }

    fun getBellBehaviour(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, true) as Int
    }

    fun getDeleteTMPDIRFilesOlderThanXDaysOnExit(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, true) as Int
    }

    fun getTerminalCursorBlinkRate(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, true) as Int
    }

    fun getTerminalCursorStyle(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, true) as Int
    }

    fun getTerminalMarginHorizontal(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL, true) as Int
    }

    fun getTerminalMarginVertical(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL, true) as Int
    }

    fun getTerminalTranscriptRows(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, true) as Int
    }

    fun getTerminalToolbarHeightScaleFactor(): Float {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, true) as Float
    }

    fun isBackKeyTheEscapeKey(): Boolean {
        return TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE == getInternalPropertyValue(TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, true)
    }

    fun getDefaultWorkingDirectory(): String? {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY, true) as String?
    }

    fun getNightMode(): String? {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_NIGHT_MODE, true) as String?
    }

    fun shouldEnableDisableSoftKeyboardOnToggle(): Boolean {
        return TermuxPropertyConstants.IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE == getInternalPropertyValue(TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true)
    }

    fun areVirtualVolumeKeysDisabled(): Boolean {
        return TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME == getInternalPropertyValue(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, true)
    }

    fun dumpPropertiesToLog() {
        val properties = getProperties(true)
        val propertiesDump = StringBuilder()

        propertiesDump.append(mLabel).append(" Termux Properties:")
        if (properties != null) {
            for (key in properties.stringPropertyNames()) {
                propertiesDump.append("\n").append(key).append(": `").append(properties.get(key)).append("`")
            }
        } else {
            propertiesDump.append(" null")
        }

        Logger.logVerbose(LOG_TAG, propertiesDump.toString())
    }

    fun dumpInternalPropertiesToLog() {
        val internalProperties = getInternalProperties()
        val internalPropertiesDump = StringBuilder()

        internalPropertiesDump.append(mLabel).append(" Internal Properties:")
        if (internalProperties != null) {
            for (key in internalProperties.keys) {
                internalPropertiesDump.append("\n").append(key).append(": `").append(internalProperties[key]).append("`")
            }
        }

        Logger.logVerbose(LOG_TAG, internalPropertiesDump.toString())
    }

    /**
     * The class that implements the [SharedPropertiesParser] interface.
     */
    class SharedPropertiesParserClient : SharedPropertiesParser {
        @NonNull
        override fun preProcessPropertiesOnReadFromDisk(@NonNull context: Context, @NonNull properties: Properties): Properties {
            return replaceUseBlackUIProperty(properties)
        }

        /**
         * Override the [SharedPropertiesParser.getInternalPropertyValueFromValue] interface function.
         */
        override fun getInternalPropertyValueFromValue(@NonNull context: Context, key: String?, value: String?): Any? {
            return getInternalFablePropertyValueFromValue(context, key, value)
        }
    }

    companion object {
        const val LOG_TAG = "FableSharedProperties"

        /**
         * Get the internal [Any] value for the key passed from the first file found in
         * [TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST].
         */
        @JvmStatic
        fun getFableInternalPropertyValue(context: Context, key: String): Any? {
            return SharedProperties.getInternalProperty(context,
                SharedProperties.getPropertiesFileFromList(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, LOG_TAG),
                key, SharedPropertiesParserClient())
        }

        @NonNull
        @JvmStatic
        fun replaceUseBlackUIProperty(@NonNull properties: Properties): Properties {
            val useBlackUIStringValue = properties.getProperty(TermuxPropertyConstants.KEY_USE_BLACK_UI)
            if (useBlackUIStringValue == null) return properties

            Logger.logWarn(LOG_TAG, "Removing deprecated property " + TermuxPropertyConstants.KEY_USE_BLACK_UI + "=" + useBlackUIStringValue)
            properties.remove(TermuxPropertyConstants.KEY_USE_BLACK_UI)

            // If KEY_NIGHT_MODE is not set
            if (properties.getProperty(TermuxPropertyConstants.KEY_NIGHT_MODE) == null) {
                val useBlackUI = SharedProperties.getBooleanValueForStringValue(useBlackUIStringValue)
                if (useBlackUI != null) {
                    val termuxAppTheme = if (useBlackUI) TermuxPropertyConstants.IVALUE_NIGHT_MODE_TRUE
                    else TermuxPropertyConstants.IVALUE_NIGHT_MODE_FALSE
                    Logger.logWarn(LOG_TAG, "Replacing deprecated property " + TermuxPropertyConstants.KEY_USE_BLACK_UI + "=" + useBlackUI + " with " + TermuxPropertyConstants.KEY_NIGHT_MODE + "=" + termuxAppTheme)
                    properties.put(TermuxPropertyConstants.KEY_NIGHT_MODE, termuxAppTheme)
                }
            }

            return properties
        }

        /**
         * A static function that should return the internal Fable [Any] for a key/value pair
         * read from properties file.
         */
        @JvmStatic
        fun getInternalFablePropertyValueFromValue(context: Context, key: String?, value: String?): Any? {
            if (key == null) return null
            /*
              For keys where a MAP_* is checked by respective functions. Note that value to this function
              would actually be the key for the MAP_*:
              - If the value is currently null, then searching MAP_* should also return null and internal default value will be used.
              - If the value is not null and does not exist in MAP_*, then internal default value will be used.
              - If the value is not null and does exist in MAP_*, then internal value returned by map will be used.
             */
            return when (key) {
                /* int */
                TermuxPropertyConstants.KEY_BELL_BEHAVIOUR ->
                    getBellBehaviourInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT ->
                    getDeleteTMPDIRFilesOlderThanXDaysOnExitInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE ->
                    getTerminalCursorBlinkRateInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE ->
                    getTerminalCursorStyleInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL ->
                    getTerminalMarginHorizontalInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL ->
                    getTerminalMarginVerticalInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS ->
                    getTerminalTranscriptRowsInternalPropertyValueFromValue(value)

                /* float */
                TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR ->
                    getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(value)

                /* Integer (may be null) */
                TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION,
                TermuxPropertyConstants.KEY_SHORTCUT_NEXT_SESSION,
                TermuxPropertyConstants.KEY_SHORTCUT_PREVIOUS_SESSION,
                TermuxPropertyConstants.KEY_SHORTCUT_RENAME_SESSION ->
                    getCodePointForSessionShortcuts(key, value)

                /* String (may be null) */
                TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR ->
                    getBackKeyBehaviourInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY ->
                    getDefaultWorkingDirectoryInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_EXTRA_KEYS ->
                    getExtraKeysInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE ->
                    getExtraKeysStyleInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_NIGHT_MODE ->
                    getNightModeInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR ->
                    getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(value)
                TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR ->
                    getVolumeKeysBehaviourInternalPropertyValueFromValue(value)

                else -> {
                    // default false boolean behaviour
                    if (TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                        SharedProperties.getBooleanValueForStringValue(key, value, false, true, LOG_TAG)
                    // default true boolean behaviour
                    else if (TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                        SharedProperties.getBooleanValueForStringValue(key, value, true, true, LOG_TAG)
                    // default inverted boolean behaviours are empty; just use String object as is (may be null)
                    else
                        value
                }
            }
        }

        /**
         * Returns the internal value after mapping it based on
         * [TermuxPropertyConstants.MAP_BELL_BEHAVIOUR] if the value is not `null`
         * and is valid, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR].
         */
        @JvmStatic
        fun getBellBehaviourInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, TermuxPropertyConstants.MAP_BELL_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR, true, LOG_TAG) as Int
        }

        /**
         * Returns the int for the value if its not null and is between the delete tmpdir min and max,
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT].
         */
        @JvmStatic
        fun getDeleteTMPDIRFilesOlderThanXDaysOnExitInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
                DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT),
                TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
                TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN,
                TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX,
                true, true, LOG_TAG)
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE].
         */
        @JvmStatic
        fun getTerminalCursorBlinkRateInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE,
                DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE),
                TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE,
                TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN,
                TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX,
                true, true, LOG_TAG)
        }

        /**
         * Returns the internal value after mapping it based on
         * [TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE] if the value is not `null`
         * and is valid, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE].
         */
        @JvmStatic
        fun getTerminalCursorStyleInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE, true, LOG_TAG) as Int
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL].
         */
        @JvmStatic
        fun getTerminalMarginHorizontalInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL,
                DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL),
                TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL,
                TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN,
                TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX,
                true, true, LOG_TAG)
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL].
         */
        @JvmStatic
        fun getTerminalMarginVerticalInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL,
                DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL),
                TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL,
                TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN,
                TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX,
                true, true, LOG_TAG)
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS].
         */
        @JvmStatic
        fun getTerminalTranscriptRowsInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS,
                DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS),
                TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS,
                TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN,
                TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX,
                true, true, LOG_TAG)
        }

        /**
         * Returns the float for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR].
         */
        @JvmStatic
        fun getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(value: String?): Float {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,
                DataUtils.getFloatFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR),
                TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,
                TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN,
                TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX,
                true, true, LOG_TAG)
        }

        /**
         * Returns the code point for the value if key is not `null` and value is not `null` and is valid,
         * otherwise returns `null`.
         */
        @JvmStatic
        fun getCodePointForSessionShortcuts(key: String?, value: String?): Int? {
            if (key == null) return null
            if (value == null) return null
            val parts = value.lowercase().trim().split("\\+")
            val input = if (parts.size == 2) parts[1].trim() else null
            if (!(parts.size == 2 && parts[0].trim() == "ctrl") || input!!.isEmpty() || input.length > 2) {
                Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>")
                return null
            }

            val c = input[0]
            var codePoint = c.code
            if (Character.isLowSurrogate(c)) {
                if (input.length != 2 || Character.isHighSurrogate(input[1])) {
                    Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>")
                    return null
                } else {
                    codePoint = Character.toCodePoint(input[1], c)
                }
            }

            return codePoint
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR].
         */
        @JvmStatic
        fun getBackKeyBehaviourInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, TermuxPropertyConstants.MAP_BACK_KEY_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR, true, LOG_TAG) as String?
        }

        /**
         * Returns the path itself if a directory exists at it and is readable, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY].
         */
        @JvmStatic
        fun getDefaultWorkingDirectoryInternalPropertyValueFromValue(path: String?): String {
            if (path == null || path.isEmpty()) return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY
            val workDir = File(path)
            if (!workDir.exists() || !workDir.isDirectory || !workDir.canRead()) {
                // Fallback to default directory if user configured working directory does not exist,
                // is not a directory or is not readable.
                Logger.logError(LOG_TAG, "The path \"" + path + "\" for the key \"" + TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY + "\" does not exist, is not a directory or is not readable. Using default value \"" + TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY + "\" instead.")
                return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY
            } else {
                return path
            }
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS].
         */
        @JvmStatic
        fun getExtraKeysInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNullOrEmpty(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS)
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE].
         */
        @JvmStatic
        fun getExtraKeysStyleInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNullOrEmpty(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE)
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_NIGHT_MODE].
         */
        @JvmStatic
        fun getNightModeInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_NIGHT_MODE,
                TermuxPropertyConstants.MAP_NIGHT_MODE, SharedProperties.toLowerCase(value),
                TermuxPropertyConstants.DEFAULT_IVALUE_NIGHT_MODE, true, LOG_TAG) as String?
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR].
         */
        @JvmStatic
        fun getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true, LOG_TAG) as String?
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns
         * [TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR].
         */
        @JvmStatic
        fun getVolumeKeysBehaviourInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, TermuxPropertyConstants.MAP_VOLUME_KEYS_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR, true, LOG_TAG) as String?
        }

        /** Get the [TermuxPropertyConstants.KEY_NIGHT_MODE] value from the properties file on disk. */
        @JvmStatic
        fun getNightMode(context: Context): String? {
            return getFableInternalPropertyValue(context, TermuxPropertyConstants.KEY_NIGHT_MODE) as String?
        }
    }
}
