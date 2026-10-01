package com.gph.fable.app.fragments.settings.termux

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import com.gph.fable.R
import com.gph.fable.app.fragments.settings.FablePreferenceFragment
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.preferences.TermuxPreferenceConstants

@Keep
open class TerminalViewPreferencesFragment : FablePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = TerminalViewPreferencesDataStore.getInstance(context)
        setPreferencesFromResource(R.xml.fable_terminal_view_preferences, rootKey)
    }
}

class TerminalViewPreferencesDataStore private constructor(
    private val context: Context
) : PreferenceDataStore() {

    private val preferences = FableAppSharedPreferences.build(context, true)

    override fun putBoolean(key: String?, value: Boolean) {
        if (preferences == null || key == null) return

        if (key == "terminal_margin_adjustment") {
            preferences.setTerminalMarginAdjustment(value)
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (preferences == null || key == null) return defValue

        return if (key == TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE) {
            preferences.getFontSizeDp(context)
        } else {
            defValue
        }
    }

    override fun putInt(key: String?, value: Int) {
        if (preferences == null || key == null) return

        if (key == TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE) {
            preferences.setFontSizeDp(context, value)
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (preferences == null) return false

        return if (key == "terminal_margin_adjustment") {
            preferences.isTerminalMarginAdjustmentEnabled()
        } else {
            false
        }
    }

    companion object {
        private var instance: TerminalViewPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TerminalViewPreferencesDataStore {
            return instance ?: TerminalViewPreferencesDataStore(context).also { instance = it }
        }
    }
}
