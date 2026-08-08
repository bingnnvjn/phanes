package com.gph.fable.app.terminal;

import android.content.Context;

import androidx.annotation.NonNull;

import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.theme.ThemeUtils;
import com.gph.fable.terminal.TerminalColors;
import com.gph.fable.terminal.adapter.CoreAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Fable 终端配色板（工单 04，ADR-0002 第 2 节）。
 *
 * - 有 {@code ~/.termux/colors.properties}：以它为准（fg/bg/cursor，可扩展 selection），
 *   不随系统明暗切换；
 * - 无该文件：内置明/暗各一套，随系统明暗（外壳主题）走。
 *
 * 经 CoreAdapter 缝的 {@code setPalette} push 给 fable-render（工单 14 API）；
 * 旧路径（TerminalView/TerminalEmulator）仍由 TermuxTerminalSessionActivityClient
 * 的 checkForFontAndColors 处理，本类只作用于新路径。
 */
public final class FableTerminalPalette {

    public static final int DARK_FOREGROUND = 0xFFFFFFFF;
    public static final int DARK_BACKGROUND = 0xFF000000;
    public static final int DARK_SELECTION = 0xFF335588;
    public static final int DARK_CURSOR = 0xFFFFFFFF;

    public static final int LIGHT_FOREGROUND = 0xFF000000;
    public static final int LIGHT_BACKGROUND = 0xFFFFFFFF;
    public static final int LIGHT_SELECTION = 0xFFBBDEFB;
    public static final int LIGHT_CURSOR = 0xFF000000;

    private FableTerminalPalette() {
    }

    /** 一套可 push 的终端配色（ARGB）。 */
    public static final class Palette {
        public final boolean custom;
        public final int foreground;
        public final int background;
        public final int selection;
        public final int cursor;

        Palette(boolean custom, int foreground, int background, int selection, int cursor) {
            this.custom = custom;
            this.foreground = foreground;
            this.background = background;
            this.selection = selection;
            this.cursor = cursor;
        }
    }

    /** 按系统明暗与 colors.properties 解析配色板。 */
    @NonNull
    public static Palette resolve(@NonNull Context context) {
        // ADR-0002：无 colors.properties 时内置明/暗随系统明暗走（不随外壳三选一强制主题）。
        boolean dark = ThemeUtils.isNightModeEnabled(context);
        return resolve(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, dark);
    }

    /** 解析入口（测试可用临时文件直接驱动）。 */
    @NonNull
    public static Palette resolve(File colorsFile, boolean dark) {
        if (colorsFile != null && colorsFile.isFile()) {
            Properties props = new Properties();
            try (InputStream in = new FileInputStream(colorsFile)) {
                props.load(in);
            } catch (Exception e) {
                return builtIn(dark);
            }
            return fromProperties(props, dark);
        }
        return builtIn(dark);
    }

    /** 从 colors.properties 解析；缺失项回退内置明/暗（dark 只影响缺省选择色）。 */
    @NonNull
    static Palette fromProperties(@NonNull Properties props, boolean dark) {
        Palette builtIn = builtIn(dark);

        int foreground = parse(props.getProperty("foreground"), builtIn.foreground);
        int background = parse(props.getProperty("background"), builtIn.background);
        int selection = parse(props.getProperty("selection"), builtIn.selection);
        int cursor = parse(props.getProperty("cursor"), cursorFor(background));

        return new Palette(true, foreground, background, selection, cursor);
    }

    /** 内置明/暗配色（无 colors.properties 时）。 */
    @NonNull
    public static Palette builtIn(boolean dark) {
        if (dark) {
            return new Palette(false, DARK_FOREGROUND, DARK_BACKGROUND, DARK_SELECTION, DARK_CURSOR);
        }
        return new Palette(false, LIGHT_FOREGROUND, LIGHT_BACKGROUND, LIGHT_SELECTION, LIGHT_CURSOR);
    }

    /** 把当前配色板 push 到 CoreAdapter 缝（不支持配色板的实现直接忽略）。 */
    public static void apply(@NonNull Context context, CoreAdapter adapter) {
        apply(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, adapter,
            ThemeUtils.isNightModeEnabled(context));
    }

    /** push 入口（测试用）。 */
    static void apply(File colorsFile, CoreAdapter adapter, boolean dark) {
        if (adapter == null || !adapter.supportsPalette()) return;
        Palette palette = resolve(colorsFile, dark);
        adapter.setPalette(palette.foreground, palette.background, palette.selection, palette.cursor);
    }

    private static int parse(String value, int fallback) {
        if (value == null) return fallback;
        int color = TerminalColors.parse(value.trim());
        return color == 0 ? fallback : color;
    }

    /** 背景亮则黑光标，背景暗则白光标（与 TerminalColorScheme 一致）。 */
    private static int cursorFor(int background) {
        int r = (background >> 16) & 0xFF;
        int g = (background >> 8) & 0xFF;
        int b = background & 0xFF;
        double brightness = Math.sqrt(r * r * 0.241 + g * g * 0.691 + b * b * 0.068);
        return brightness >= 130 ? 0xFF000000 : 0xFFFFFFFF;
    }
}
