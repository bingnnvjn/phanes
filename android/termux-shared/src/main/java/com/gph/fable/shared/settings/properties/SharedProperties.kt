package com.gph.fable.shared.settings.properties

import android.content.Context
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.google.common.collect.BiMap
import com.google.common.collect.ImmutableBiMap
import com.google.common.primitives.Primitives
import com.gph.fable.shared.R
import com.gph.fable.shared.file.FileUtils
import com.gph.fable.shared.file.filesystem.FileType
import com.gph.fable.shared.logger.Logger
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.Properties

/**
 * An implementation similar to android's [android.content.SharedPreferences] interface for
 * reading and writing to and from ".properties" files which also maintains an in-memory cache for
 * the key/value pairs when an instance object is used.
 */
class SharedProperties(
    @NonNull context: Context,
    @Nullable propertiesFile: File?,
    propertiesList: Set<String>?,
    @NonNull sharedPropertiesParser: SharedPropertiesParser
) {

    /**
     * The [Properties] object that maintains an in-memory cache of values loaded from the
     * [mPropertiesFile] file.
     */
    private var mProperties: Properties

    /**
     * The [HashMap] object that maintains an in-memory cache of internal values for the values
     * loaded from the [mPropertiesFile] file.
     */
    private var mMap: Map<String, Any?>

    private val mContext: Context
    private val mPropertiesFile: File?
    private val mPropertiesList: Set<String>?
    private val mSharedPropertiesParser: SharedPropertiesParser

    private val mLock = Any()

    init {
        mContext = context.applicationContext
        mPropertiesFile = propertiesFile
        mPropertiesList = propertiesList
        mSharedPropertiesParser = sharedPropertiesParser

        mProperties = Properties()
        mMap = HashMap()
    }

    /**
     * Load the properties defined by [mPropertiesList] or all properties if its `null`
     * from the [mPropertiesFile] file to update the [mProperties] and [mMap] in-memory cache.
     */
    fun loadPropertiesFromDisk() {
        synchronized(mLock) {
            // Get properties from mPropertiesFile
            var properties = getProperties(false)

            // We still need to load default values into mMap, so we assume no properties defined if
            // reading from mPropertiesFile failed
            if (properties == null)
                properties = Properties()

            val map = HashMap<String, Any?>()
            val newProperties = Properties()

            var propertiesList = mPropertiesList
            if (propertiesList == null)
                propertiesList = properties.stringPropertyNames()

            for (key in propertiesList) {
                val value = properties.getProperty(key) // value will be null if key does not exist in propertiesFile

                // Call the SharedPropertiesParser.getInternalPropertyValueFromValue interface method
                // to get the internal value to store in the mMap.
                val internalValue = mSharedPropertiesParser.getInternalPropertyValueFromValue(mContext, key, value)

                // If the internal value was successfully added to map, then also add value to newProperties
                // We only store values in-memory defined by propertiesList
                if (putToMap(map, key, internalValue)) { // null internalValue will be put into map
                    putToProperties(newProperties, key, value) // null value will **not** be put into properties
                }
            }

            mMap = map
            mProperties = newProperties
        }
    }

    /**
     * Get the [Properties] object for the [mPropertiesFile].
     */
    fun getProperties(cached: Boolean): Properties? {
        synchronized(mLock) {
            return if (cached) {
                if (mProperties == null) mProperties = Properties()
                getPropertiesCopy(mProperties)
            } else {
                getPropertiesFromFile(mContext, mPropertiesFile, mSharedPropertiesParser)
            }
        }
    }

    /**
     * Get the [String] value for the key passed from the [mPropertiesFile].
     */
    fun getProperty(key: String, cached: Boolean): String? {
        synchronized(mLock) {
            return getProperties(cached)!!.get(key) as String?
        }
    }

    /**
     * Get the [mMap] object for the [mPropertiesFile].
     */
    fun getInternalProperties(): Map<String, Any?> {
        synchronized(mLock) {
            if (mMap == null) mMap = HashMap()
            return getMapCopy(mMap)!!
        }
    }

    /**
     * Get the internal [Any] value for the key passed from the [mPropertiesFile].
     */
    fun getInternalProperty(key: String?): Any? {
        synchronized(mLock) {
            // null keys are not allowed to be stored in mMap
            return if (key != null)
                getInternalProperties()[key]
            else
                null
        }
    }

    companion object {

        /** Defines the bidirectional map for boolean values and their internal values */
        @JvmField
        val MAP_GENERIC_BOOLEAN: ImmutableBiMap<String, Boolean> =
            ImmutableBiMap.Builder<String, Boolean>()
                .put("true", true)
                .put("false", false)
                .build()

        /** Defines the bidirectional map for inverted boolean values and their internal values */
        @JvmField
        val MAP_GENERIC_INVERTED_BOOLEAN: ImmutableBiMap<String, Boolean> =
            ImmutableBiMap.Builder<String, Boolean>()
                .put("true", false)
                .put("false", true)
                .build()

        private const val LOG_TAG = "SharedProperties"

        /**
         * A static function to get the [Properties] object for the propertiesFile. A lock is not
         * taken when this function is called.
         */
        @JvmStatic
        fun getPropertiesFromFile(context: Context?, propertiesFile: File?, @Nullable sharedPropertiesParser: SharedPropertiesParser?): Properties? {
            val properties = Properties()

            if (propertiesFile == null) {
                Logger.logWarn(LOG_TAG, "Not loading properties since file is null")
                return properties
            }

            try {
                FileInputStream(propertiesFile).use { input ->
                    Logger.logVerbose(LOG_TAG, "Loading properties from \"" + propertiesFile.absolutePath + "\" file")
                    properties.load(InputStreamReader(input, StandardCharsets.UTF_8))
                }
            } catch (e: Exception) {
                if (context != null)
                    Toast.makeText(context,
                        context.getString(R.string.error_could_not_open_properties_file, propertiesFile.absolutePath, e.message),
                        Toast.LENGTH_LONG).show()
                Logger.logStackTraceWithMessage(LOG_TAG, "Error loading properties file \"" + propertiesFile.absolutePath + "\"", e)
                return null
            }

            return if (sharedPropertiesParser != null && context != null)
                sharedPropertiesParser.preProcessPropertiesOnReadFromDisk(context, properties)
            else
                properties
        }

        /**
         * Returns the first [File] found in `propertiesFilePaths` from which app properties can be loaded.
         */
        @JvmStatic
        fun getPropertiesFileFromList(propertiesFilePaths: List<String>?, @NonNull logTag: String): File? {
            if (propertiesFilePaths == null || propertiesFilePaths.size == 0)
                return null

            for (propertiesFilePath in propertiesFilePaths) {
                val propertiesFile = File(propertiesFilePath)

                // Symlinks **will not** be followed.
                val fileType = FileUtils.getFileType(propertiesFilePath, false)
                if (fileType == FileType.REGULAR) {
                    if (propertiesFile.canRead())
                        return propertiesFile
                    else
                        Logger.logWarn(logTag, "Ignoring properties file at \"" + propertiesFilePath + "\" since it is not readable")
                } else if (fileType != FileType.NO_EXIST) {
                    Logger.logWarn(logTag, "Ignoring properties file at \"" + propertiesFilePath + "\" of type: \"" + fileType.getName() + "\"")
                }
            }

            Logger.logDebug(logTag, "No readable properties file found at: " + propertiesFilePaths)
            return null
        }

        @JvmStatic
        fun getProperty(context: Context?, propertiesFile: File?, key: String, def: String?): String? {
            return getProperty(context, propertiesFile, key, def, null)
        }

        /**
         * A static function to get the [String] value for the [Properties] key read from
         * the propertiesFile file.
         */
        @JvmStatic
        fun getProperty(context: Context?, propertiesFile: File?, key: String, def: String?, @Nullable sharedPropertiesParser: SharedPropertiesParser?): String? {
            return getDefaultIfNull(getDefaultIfNull<Properties>(getPropertiesFromFile(context, propertiesFile, sharedPropertiesParser), Properties())!!.get(key) as String?, def)
        }

        /**
         * A static function to get the internal [Any] value for the [String] value for
         * the [Properties] key read from the propertiesFile file.
         */
        @JvmStatic
        fun getInternalProperty(context: Context, propertiesFile: File?, key: String, @NonNull sharedPropertiesParser: SharedPropertiesParser): Any? {
            val value = getDefaultIfNull<Properties>(getPropertiesFromFile(context, propertiesFile, sharedPropertiesParser), Properties())!!.get(key) as String?

            // Call the SharedPropertiesParser.getInternalPropertyValueFromValue interface method
            // to get the internal value to return.
            return sharedPropertiesParser.getInternalPropertyValueFromValue(context, key, value)
        }

        @JvmStatic
        fun isPropertyValueTrue(context: Context?, propertiesFile: File?, key: String, logErrorOnInvalidValue: Boolean): Boolean {
            return isPropertyValueTrue(context, propertiesFile, key, logErrorOnInvalidValue, null)
        }

        /**
         * A static function to check if the value is `true` for [Properties] key read from
         * the propertiesFile file.
         */
        @JvmStatic
        fun isPropertyValueTrue(context: Context?, propertiesFile: File?, key: String, logErrorOnInvalidValue: Boolean, @Nullable sharedPropertiesParser: SharedPropertiesParser?): Boolean {
            return getBooleanValueForStringValue(key, getProperty(context, propertiesFile, key, null, sharedPropertiesParser), false, logErrorOnInvalidValue, LOG_TAG)
        }

        @JvmStatic
        fun isPropertyValueFalse(context: Context?, propertiesFile: File?, key: String, logErrorOnInvalidValue: Boolean): Boolean {
            return isPropertyValueFalse(context, propertiesFile, key, logErrorOnInvalidValue, null)
        }

        /**
         * A static function to check if the value is `false` for [Properties] key read from
         * the propertiesFile file.
         */
        @JvmStatic
        fun isPropertyValueFalse(context: Context?, propertiesFile: File?, key: String, logErrorOnInvalidValue: Boolean, @Nullable sharedPropertiesParser: SharedPropertiesParser?): Boolean {
            return getInvertedBooleanValueForStringValue(key, getProperty(context, propertiesFile, key, null, sharedPropertiesParser), true, logErrorOnInvalidValue, LOG_TAG)
        }

        /**
         * Put a value in a map. The key cannot be `null`.
         */
        @JvmStatic
        fun putToMap(map: HashMap<String, Any?>?, key: String?, value: Any?): Boolean {
            if (map == null) {
                Logger.logError(LOG_TAG, "Map passed to SharedProperties.putToProperties() is null")
                return false
            }

            // null keys are not allowed to be stored in mMap
            if (key == null) {
                Logger.logError(LOG_TAG, "Cannot put a null key into properties map")
                return false
            }

            var put = false
            if (value != null) {
                val clazz = value.javaClass
                if (clazz.isPrimitive || Primitives.isWrapperType(clazz) || value is String) {
                    put = true
                }
            } else {
                put = true
            }

            if (put) {
                map.put(key, value)
                return true
            } else {
                Logger.logError(LOG_TAG, "Cannot put a non-primitive value for the key \"" + key + "\" into properties map")
                return false
            }
        }

        /**
         * Put a value in a [Properties]. The key cannot be `null`.
         */
        @JvmStatic
        fun putToProperties(properties: Properties?, key: String?, value: String?): Boolean {
            if (properties == null) {
                Logger.logError(LOG_TAG, "Properties passed to SharedProperties.putToProperties() is null")
                return false
            }

            // null keys are not allowed to be stored in mMap
            if (key == null) {
                Logger.logError(LOG_TAG, "Cannot put a null key into properties")
                return false
            }

            if (value != null) {
                properties.put(key, value)
                return true
            } else {
                properties.remove(key)
            }

            return true
        }

        @JvmStatic
        fun getPropertiesCopy(inputProperties: Properties?): Properties? {
            if (inputProperties == null) return null

            val outputProperties = Properties()
            for (key in inputProperties.stringPropertyNames()) {
                outputProperties.put(key, inputProperties.get(key))
            }

            return outputProperties
        }

        @JvmStatic
        fun getMapCopy(map: Map<String, Any?>?): Map<String, Any?>? {
            return if (map == null) null else HashMap(map)
        }

        /**
         * Get the boolean value for the [String] value.
         */
        @JvmStatic
        fun getBooleanValueForStringValue(value: String?): Boolean? {
            return MAP_GENERIC_BOOLEAN[toLowerCase(value)]
        }

        /**
         * Get the boolean value for the [String] value.
         */
        @JvmStatic
        fun getBooleanValueForStringValue(key: String?, value: String?, def: Boolean, logErrorOnInvalidValue: Boolean, logTag: String): Boolean {
            return getDefaultIfNotInMap(key, MAP_GENERIC_BOOLEAN, toLowerCase(value), def, logErrorOnInvalidValue, logTag) as Boolean
        }

        /**
         * Get the inverted boolean value for the [String] value.
         */
        @JvmStatic
        fun getInvertedBooleanValueForStringValue(key: String?, value: String?, def: Boolean, logErrorOnInvalidValue: Boolean, logTag: String): Boolean {
            return getDefaultIfNotInMap(key, MAP_GENERIC_INVERTED_BOOLEAN, toLowerCase(value), def, logErrorOnInvalidValue, logTag) as Boolean
        }

        /**
         * Get the value for the `inputValue` [Any] key from a [BiMap], otherwise
         * default value if key not found in `map`.
         */
        @JvmStatic
        fun getDefaultIfNotInMap(key: String?, @NonNull map: BiMap<*, *>, inputValue: Any?, defaultOutputValue: Any, logErrorOnInvalidValue: Boolean, logTag: String): Any? {
            val outputValue = map[inputValue]
            if (outputValue == null) {
                val defaultInputValue = map.inverse()[defaultOutputValue]
                if (defaultInputValue == null)
                    Logger.logError(LOG_TAG, "The default output value \"" + defaultOutputValue + "\" for the key \"" + key + "\" does not exist as a value in the BiMap passed to getDefaultIfNotInMap(): " + map.values)

                if (logErrorOnInvalidValue && inputValue != null) {
                    if (key != null)
                        Logger.logError(logTag, "The value \"" + inputValue + "\" for the key \"" + key + "\" is invalid. Using default value \"" + defaultInputValue + "\" instead.")
                    else
                        Logger.logError(logTag, "The value \"" + inputValue + "\" is invalid. Using default value \"" + defaultInputValue + "\" instead.")
                }

                return defaultOutputValue
            } else {
                return outputValue
            }
        }

        /**
         * Get the `int` `value` as is if between `min` and `max` (inclusive), otherwise
         * return default value.
         */
        @JvmStatic
        fun getDefaultIfNotInRange(key: String?, value: Int, def: Int, min: Int, max: Int, logErrorOnInvalidValue: Boolean, ignoreErrorIfValueZero: Boolean, logTag: String): Int {
            if (value < min || value > max) {
                if (logErrorOnInvalidValue && (!ignoreErrorIfValueZero || value != 0)) {
                    if (key != null)
                        Logger.logError(logTag, "The value \"" + value + "\" for the key \"" + key + "\" is not within the range " + min + "-" + max + " (inclusive). Using default value \"" + def + "\" instead.")
                    else
                        Logger.logError(logTag, "The value \"" + value + "\" is not within the range " + min + "-" + max + " (inclusive). Using default value \"" + def + "\" instead.")
                }
                return def
            } else {
                return value
            }
        }

        /**
         * Get the `float` `value` as is if between `min` and `max` (inclusive), otherwise
         * return default value.
         */
        @JvmStatic
        fun getDefaultIfNotInRange(key: String?, value: Float, def: Float, min: Float, max: Float, logErrorOnInvalidValue: Boolean, ignoreErrorIfValueZero: Boolean, logTag: String): Float {
            if (value < min || value > max) {
                if (logErrorOnInvalidValue && (!ignoreErrorIfValueZero || value != 0f)) {
                    if (key != null)
                        Logger.logError(logTag, "The value \"" + value + "\" for the key \"" + key + "\" is not within the range " + min + "-" + max + " (inclusive). Using default value \"" + def + "\" instead.")
                    else
                        Logger.logError(logTag, "The value \"" + value + "\" is not within the range " + min + "-" + max + " (inclusive). Using default value \"" + def + "\" instead.")
                }
                return def
            } else {
                return value
            }
        }

        /**
         * Get the object itself if it is not `null`, otherwise default.
         */
        @JvmStatic
        fun <T> getDefaultIfNull(@Nullable obj: T?, @Nullable def: T?): T? {
            return obj ?: def
        }

        /**
         * Get the [String] object itself if it is not `null` or empty, otherwise default.
         */
        @JvmStatic
        fun getDefaultIfNullOrEmpty(@Nullable obj: String?, @Nullable def: String?): String? {
            return if (obj == null || obj.isEmpty()) def else obj
        }

        /**
         * Convert the [String] value to lowercase.
         */
        @JvmStatic
        fun toLowerCase(value: String?): String? {
            return value?.lowercase()
        }
    }
}
