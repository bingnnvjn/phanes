package com.gph.fable.shared.settings.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.gph.fable.shared.logger.Logger

object SharedPreferenceUtils {

    private const val LOG_TAG = "SharedPreferenceUtils"

    /**
     * Get [SharedPreferences] instance of the preferences file 'name' with the operating mode
     * [Context.MODE_PRIVATE].
     */
    @JvmStatic
    fun getPrivateSharedPreferences(context: Context, name: String): SharedPreferences {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /**
     * Get [SharedPreferences] instance of the preferences file 'name' with the operating mode
     * [Context.MODE_PRIVATE] and [Context.MODE_MULTI_PROCESS].
     */
    @JvmStatic
    fun getPrivateAndMultiProcessSharedPreferences(context: Context, name: String): SharedPreferences {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
    }

    /**
     * Get a `boolean` from [SharedPreferences].
     */
    @JvmStatic
    fun getBoolean(sharedPreferences: SharedPreferences?, key: String, def: Boolean): Boolean {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting boolean value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        return try {
            sharedPreferences.getBoolean(key, def)
        } catch (e: ClassCastException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting boolean value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e)
            def
        }
    }

    /**
     * Set a `boolean` in [SharedPreferences].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setBoolean(sharedPreferences: SharedPreferences?, key: String, value: Boolean, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting boolean value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putBoolean(key, value).commit()
        else
            sharedPreferences.edit().putBoolean(key, value).apply()
    }

    /**
     * Get a `float` from [SharedPreferences].
     */
    @JvmStatic
    fun getFloat(sharedPreferences: SharedPreferences?, key: String, def: Float): Float {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting float value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        return try {
            sharedPreferences.getFloat(key, def)
        } catch (e: ClassCastException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting float value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e)
            def
        }
    }

    /**
     * Set a `float` in [SharedPreferences].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setFloat(sharedPreferences: SharedPreferences?, key: String, value: Float, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting float value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putFloat(key, value).commit()
        else
            sharedPreferences.edit().putFloat(key, value).apply()
    }

    /**
     * Get an `int` from [SharedPreferences].
     */
    @JvmStatic
    fun getInt(sharedPreferences: SharedPreferences?, key: String, def: Int): Int {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting int value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        return try {
            sharedPreferences.getInt(key, def)
        } catch (e: ClassCastException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting int value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e)
            def
        }
    }

    /**
     * Set an `int` in [SharedPreferences].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setInt(sharedPreferences: SharedPreferences?, key: String, value: Int, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting int value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putInt(key, value).commit()
        else
            sharedPreferences.edit().putInt(key, value).apply()
    }

    /**
     * Get an `int` in [SharedPreferences] and increment it.
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun getAndIncrementInt(sharedPreferences: SharedPreferences?, key: String, def: Int,
                           commitToFile: Boolean, resetValue: Int?): Int {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring incrementing int value for the \"" + key + "\" key into null shared preferences.")
            return def
        }

        var curValue = getInt(sharedPreferences, key, def)
        if (resetValue != null && (curValue < 0)) curValue = resetValue

        var newValue = curValue + 1
        if (resetValue != null && newValue < 0) newValue = resetValue

        setInt(sharedPreferences, key, newValue, commitToFile)
        return curValue
    }

    /**
     * Get a `long` from [SharedPreferences].
     */
    @JvmStatic
    fun getLong(sharedPreferences: SharedPreferences?, key: String, def: Long): Long {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting long value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        return try {
            sharedPreferences.getLong(key, def)
        } catch (e: ClassCastException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting long value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e)
            def
        }
    }

    /**
     * Set a `long` in [SharedPreferences].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setLong(sharedPreferences: SharedPreferences?, key: String, value: Long, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting long value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putLong(key, value).commit()
        else
            sharedPreferences.edit().putLong(key, value).apply()
    }

    /**
     * Get a [String] from [SharedPreferences].
     */
    @JvmStatic
    fun getString(sharedPreferences: SharedPreferences?, key: String, def: String?, defIfEmpty: Boolean): String? {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting String value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        return try {
            val value = sharedPreferences.getString(key, def)
            if (defIfEmpty && (value == null || value.isEmpty()))
                def
            else
                value
        } catch (e: ClassCastException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting String value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e)
            def
        }
    }

    /**
     * Set a [String] in [SharedPreferences].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setString(sharedPreferences: SharedPreferences?, key: String, value: String?, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting String value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putString(key, value).commit()
        else
            sharedPreferences.edit().putString(key, value).apply()
    }

    /**
     * Get a [Set] of [String] from [SharedPreferences].
     */
    @JvmStatic
    fun getStringSet(sharedPreferences: SharedPreferences?, key: String, def: Set<String>?): Set<String>? {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting Set<String> value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        return try {
            sharedPreferences.getStringSet(key, def)
        } catch (e: ClassCastException) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting Set<String> value for the \"" + key + "\" key from shared preferences. Returning default value \"" + def + "\".", e)
            def
        }
    }

    /**
     * Set a [Set] of [String] in [SharedPreferences].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setStringSet(sharedPreferences: SharedPreferences?, key: String, value: Set<String>?, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting Set<String> value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putStringSet(key, value).commit()
        else
            sharedPreferences.edit().putStringSet(key, value).apply()
    }

    /**
     * Get an `int` from [SharedPreferences] that is stored as a [String].
     */
    @JvmStatic
    fun getIntStoredAsString(sharedPreferences: SharedPreferences?, key: String, def: Int): Int {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Error getting int value for the \"" + key + "\" key from null shared preferences. Returning default value \"" + def + "\".")
            return def
        }

        val intValue = try {
            val stringValue = sharedPreferences.getString(key, Integer.toString(def))
            if (stringValue != null)
                Integer.parseInt(stringValue)
            else
                def
        } catch (e: NumberFormatException) {
            def
        } catch (e: ClassCastException) {
            def
        }

        return intValue
    }

    /**
     * Set an `int` into [SharedPreferences] that is stored as a [String].
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setIntStoredAsString(sharedPreferences: SharedPreferences?, key: String, value: Int, commitToFile: Boolean) {
        if (sharedPreferences == null) {
            Logger.logError(LOG_TAG, "Ignoring setting int value \"" + value + "\" for the \"" + key + "\" key into null shared preferences.")
            return
        }

        if (commitToFile)
            sharedPreferences.edit().putString(key, Integer.toString(value)).commit()
        else
            sharedPreferences.edit().putString(key, Integer.toString(value)).apply()
    }
}
