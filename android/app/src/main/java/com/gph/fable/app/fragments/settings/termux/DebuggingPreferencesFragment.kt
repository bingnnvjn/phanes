package com.gph.fable.app.fragments.settings.termux

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import com.gph.fable.R
import com.gph.fable.app.fragments.settings.FablePreferenceFragment
import com.gph.fable.shared.logger.Logger
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences

@Keep
open class DebuggingPreferencesFragment : FablePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = DebuggingPreferencesDataStore.getInstance(context)
        setPreferencesFromResource(R.xml.fable_debugging_preferences, rootKey)
        configureLoggingPreferences(context)
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory = findPreference<PreferenceCategory>("logging") ?: return
        val logLevelListPreference = findPreference<ListPreference>("log_level") ?: return
        val preferences = FableAppSharedPreferences.build(context, true) ?: return

        setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel())
        loggingCategory.addPreference(logLevelListPreference)
    }

    companion object {
        @JvmStatic
        fun setLogLevelListPreferenceData(
            logLevelListPreference: ListPreference?,
            context: Context,
            logLevel: Int
        ): ListPreference {
            val preference = logLevelListPreference ?: ListPreference(context)
            val logLevels = Logger.getLogLevelsArray()
            val logLevelLabels = Logger.getLogLevelLabelsArray(context, logLevels, true)

            preference.entryValues = logLevels
            preference.entries = logLevelLabels
            preference.value = logLevel.toString()
            preference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL)
            return preference
        }
    }
}

class DebuggingPreferencesDataStore private constructor(
    private val context: Context
) : PreferenceDataStore() {

    private val preferences = FableAppSharedPreferences.build(context, true)

    override fun getString(key: String?, defValue: String?): String? {
        if (preferences == null || key == null) return null

        return if (key == "log_level") preferences.getLogLevel().toString() else null
    }

    override fun putString(key: String?, value: String?) {
        if (preferences == null || key == null) return

        if (key == "log_level" && value != null) {
            preferences.setLogLevel(context, value.toInt())
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        if (preferences == null || key == null) return

        when (key) {
            "terminal_view_key_logging_enabled" -> preferences.setTerminalViewKeyLoggingEnabled(value)
            "crash_report_notifications_enabled" -> preferences.setCrashReportNotificationsEnabled(value)
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (preferences == null) return false

        return when (key) {
            "terminal_view_key_logging_enabled" -> preferences.isTerminalViewKeyLoggingEnabled()
            "crash_report_notifications_enabled" -> preferences.areCrashReportNotificationsEnabled(false)
            else -> false
        }
    }

    companion object {
        private var instance: DebuggingPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): DebuggingPreferencesDataStore {
            return instance ?: DebuggingPreferencesDataStore(context).also { instance = it }
        }
    }
}
