package com.gph.fable.app.fragments.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import com.gph.fable.R
import com.gph.fable.shared.activity.media.AppCompatActivityUtils
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants
import com.gph.fable.shared.termux.theme.FableThemeUtils

@Keep
open class FablePreferencesFragment : FablePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = FablePreferencesDataStore.getInstance(context)
        setPreferencesFromResource(R.xml.fable_preferences, rootKey)

        val themeModePreference = findPreference<ListPreference>(
            TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE
        )
        val activity = activity as? AppCompatActivity
        if (themeModePreference != null && activity != null) {
            themeModePreference.onPreferenceChangeListener =
                androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
                    val mode = newValue.toString()
                    FableThemeUtils.setAppNightMode(mode)
                    AppCompatActivityUtils.setNightMode(activity, mode, true)
                    activity.recreate()
                    true
                }
        }
    }
}

class FablePreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val preferences = FableAppSharedPreferences.build(context, true)

    override fun getString(key: String?, defValue: String?): String? {
        if (preferences == null || key == null) return defValue

        return when (key) {
            TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE ->
                preferences.getThemeMode()
                    ?: TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_FABLE_THEME_MODE
            else -> defValue
        }
    }

    override fun putString(key: String?, value: String?) {
        if (preferences == null || key == null) return

        if (key == TermuxPreferenceConstants.TERMUX_APP.KEY_FABLE_THEME_MODE) {
            preferences.setThemeMode(value)
        }
    }

    companion object {
        private var instance: FablePreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): FablePreferencesDataStore {
            return instance ?: FablePreferencesDataStore(context).also { instance = it }
        }
    }
}
