package com.gph.fable.shared.termux.theme

import android.content.Context
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.gph.fable.shared.termux.settings.preferences.FableAppSharedPreferences
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants
import com.gph.fable.shared.termux.settings.properties.FableSharedProperties
import com.gph.fable.shared.theme.NightMode

object FableThemeUtils {

    /** Get the [TermuxPropertyConstants.KEY_NIGHT_MODE] value from the properties file on disk
     * and set it to app wide night mode value. */
    @JvmStatic
    fun setAppNightMode(@NonNull context: Context) {
        NightMode.setAppNightMode(FableSharedProperties.getNightMode(context))
    }

    /** Set name as app wide night mode value. */
    @JvmStatic
    fun setAppNightMode(@Nullable name: String?) {
        NightMode.setAppNightMode(name)
    }

    /**
     * 解析界面主题模式（工单 04）：设置项（system/light/dark）优先；
     * 未设置过时回退 termux.properties night-mode，再无则跟随系统。
     */
    @NonNull
    @JvmStatic
    fun getThemeMode(@NonNull context: Context, @Nullable propertiesNightMode: String?): String {
        val preferences = FableAppSharedPreferences.build(context, false)
        val storedMode = preferences?.getThemeMode()
        if (storedMode != null) {
            // 兼容旧值：light/dark → NightMode 枚举名 false/true。
            if ("light" == storedMode) {
                return NightMode.FALSE.getName()
            } else if ("dark" == storedMode) {
                return NightMode.TRUE.getName()
            }
            return storedMode
        }
        return propertiesNightMode ?: NightMode.SYSTEM.getName()
    }
}
