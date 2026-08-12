package com.gph.fable.shared.theme

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatDelegate
import com.gph.fable.shared.logger.Logger

/** The modes used by to decide night mode for themes. */
enum class NightMode(private val mName: String, @AppCompatDelegate.NightMode val mode: Int) {

    /** Night theme should be enabled. */
    TRUE("true", AppCompatDelegate.MODE_NIGHT_YES),

    /** Dark theme should be enabled. */
    FALSE("false", AppCompatDelegate.MODE_NIGHT_NO),

    /**
     * Use night or dark theme depending on system night mode.
     */
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    fun getName(): String {
        return mName
    }

    companion object {
        /** The current app wide night mode used by various libraries. Defaults to [SYSTEM]. */
        private var APP_NIGHT_MODE: NightMode? = null

        private const val LOG_TAG = "NightMode"

        /** Get [NightMode] for `name` if found, otherwise `null`. */
        @Nullable
        @JvmStatic
        fun modeOf(name: String?): NightMode? {
            for (v in NightMode.values()) {
                if (v.getName() == name) {
                    return v
                }
            }

            return null
        }

        /** Get [NightMode] for `name` if found, otherwise `def`. */
        @NonNull
        @JvmStatic
        fun modeOf(@Nullable name: String?, @NonNull def: NightMode): NightMode {
            val nightMode = modeOf(name)
            return nightMode ?: def
        }

        /** Set [APP_NIGHT_MODE]. */
        @JvmStatic
        fun setAppNightMode(@Nullable name: String?) {
            if (name == null || name.isEmpty()) {
                APP_NIGHT_MODE = SYSTEM
            } else {
                val nightMode = NightMode.modeOf(name)
                if (nightMode == null) {
                    Logger.logError(LOG_TAG, "Invalid APP_NIGHT_MODE \"" + name + "\"")
                    return
                }
                APP_NIGHT_MODE = nightMode
            }

            Logger.logVerbose(LOG_TAG, "Set APP_NIGHT_MODE to \"" + APP_NIGHT_MODE!!.getName() + "\"")
        }

        /** Get [APP_NIGHT_MODE]. */
        @NonNull
        @JvmStatic
        fun getAppNightMode(): NightMode {
            if (APP_NIGHT_MODE == null)
                APP_NIGHT_MODE = SYSTEM

            return APP_NIGHT_MODE!!
        }
    }
}
