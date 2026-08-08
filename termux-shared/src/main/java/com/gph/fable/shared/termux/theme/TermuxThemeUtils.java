package com.gph.fable.shared.termux.theme;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gph.fable.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.gph.fable.shared.termux.settings.properties.TermuxPropertyConstants;
import com.gph.fable.shared.termux.settings.properties.TermuxSharedProperties;
import com.gph.fable.shared.theme.NightMode;

public class TermuxThemeUtils {

    /** Get the {@link TermuxPropertyConstants#KEY_NIGHT_MODE} value from the properties file on disk
     * and set it to app wide night mode value. */
    public static void setAppNightMode(@NonNull Context context) {
        NightMode.setAppNightMode(TermuxSharedProperties.getNightMode(context));
    }

    /** Set name as app wide night mode value. */
    public static void setAppNightMode(@Nullable String name) {
        NightMode.setAppNightMode(name);
    }

    /**
     * 解析界面主题模式（工单 04）：设置项（system/light/dark）优先；
     * 未设置过时回退 termux.properties night-mode，再无则跟随系统。
     */
    @NonNull
    public static String getThemeMode(@NonNull Context context, @Nullable String propertiesNightMode) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, false);
        String storedMode = preferences != null ? preferences.getThemeMode() : null;
        if (storedMode != null) {
            // 兼容旧值：light/dark → NightMode 枚举名 false/true。
            if ("light".equals(storedMode)) {
                return NightMode.FALSE.getName();
            } else if ("dark".equals(storedMode)) {
                return NightMode.TRUE.getName();
            }
            return storedMode;
        }
        return propertiesNightMode != null ? propertiesNightMode : NightMode.SYSTEM.getName();
    }

}
