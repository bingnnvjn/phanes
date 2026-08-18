package com.gph.fable.app.fragments.settings.termux

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import com.gph.fable.R
import com.gph.fable.app.fragments.settings.FablePreferenceFragment
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences

@Keep
open class TerminalIOPreferencesFragment : FablePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = TerminalIOPreferencesDataStore.getInstance(context)
        setPreferencesFromResource(R.xml.fable_terminal_io_preferences, rootKey)
    }
}

class TerminalIOPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val preferences = FableAppSharedPreferences.build(context, true)

    override fun putBoolean(key: String?, value: Boolean) {
        if (preferences == null || key == null) return

        when (key) {
            "soft_keyboard_enabled" -> preferences.setSoftKeyboardEnabled(value)
            "soft_keyboard_enabled_only_if_no_hardware" ->
                preferences.setSoftKeyboardEnabledOnlyIfNoHardware(value)
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (preferences == null) return false

        return when (key) {
            "soft_keyboard_enabled" -> preferences.isSoftKeyboardEnabled()
            "soft_keyboard_enabled_only_if_no_hardware" ->
                preferences.isSoftKeyboardEnabledOnlyIfNoHardware()
            else -> false
        }
    }

    companion object {
        private var instance: TerminalIOPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TerminalIOPreferencesDataStore {
            return instance ?: TerminalIOPreferencesDataStore(context).also { instance = it }
        }
    }
}
