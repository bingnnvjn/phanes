package com.gph.fable.app.session;

import android.content.Context;

import com.gph.fable.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.gph.fable.terminal.session.FableSessionFactory;
import com.gph.fable.terminal.session.JavaFableSessionFactory;

/**
 * 会话层切换开关（工单 26，ADR-0008 决策 5）：
 * 构建期默认 Rust；设置页 debug 项可切回 Java（持久化 SharedPreferences）。
 * 只影响新建会话（已有会话保持创建时的后端）。
 */
public final class FableSessionSwitch {

    private FableSessionSwitch() {
    }

    /** 由设置页开关决定；未设置时默认 Rust（构建期默认）。 */
    public static boolean isRustEnabled(Context context) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        return preferences != null && preferences.isFableSessionRustEnabled();
    }

    public static FableSessionFactory getFactory(Context context) {
        return factoryFor(isRustEnabled(context));
    }

    /** 纯映射（JVM 可测）：true → Rust 工厂，false → Java 工厂。 */
    public static FableSessionFactory factoryFor(boolean rustEnabled) {
        return rustEnabled ? RustFableSessionFactory.INSTANCE : JavaFableSessionFactory.INSTANCE;
    }
}
