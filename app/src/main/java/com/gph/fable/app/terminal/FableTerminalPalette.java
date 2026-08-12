package com.gph.fable.app.terminal;

import android.content.Context;

import androidx.annotation.NonNull;

import com.gph.fable.shared.termux.TermuxConstants;
import com.gph.fable.shared.theme.NightMode;
import com.gph.fable.shared.theme.ThemeUtils;
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
 * 旧路径（TerminalView/TerminalEmulator）已随工单 31 删除。
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

    /** 内置深色 ANSI 16 色（与旧 TerminalColorScheme 默认一致）。 */
    private static final int[] DARK_ANSI = {
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF6495ED, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
    };

    /** 内置浅色 ANSI 16 色（白底可读：普通色加深、亮色用中等亮度）。 */
    private static final int[] LIGHT_ANSI = {
        0xFF000000, 0xFFC50F1F, 0xFF0F7A1F, 0xFFB58900,
        0xFF0037DA, 0xFF881798, 0xFF007C8A, 0xFF808080,
        0xFF595959, 0xFFE74856, 0xFF16A012, 0xFFC19C00,
        0xFF3B78FF, 0xFFB4009E, 0xFF0098A6, 0xFFE0E0E0
    };

    private FableTerminalPalette() {
    }

    /** 一套可 push 的终端配色（ARGB）。 */
    public static final class Palette {
        public final boolean custom;
        public final int foreground;
        public final int background;
        public final int selection;
        public final int cursor;
        public final int[] ansi;

        Palette(boolean custom, int foreground, int background, int selection, int cursor, int[] ansi) {
            this.custom = custom;
            this.foreground = foreground;
            this.background = background;
            this.selection = selection;
            this.cursor = cursor;
            this.ansi = ansi.clone();
        }
    }

    /** 按外壳主题（设置三选一：跟随系统/浅色/深色）与 colors.properties 解析配色板。 */
    @NonNull
    public static Palette resolve(@NonNull Context context) {
        // 用户拍板：外壳选浅色 = 终端用浅色主题，选深色 = 深色主题；跟随系统则随系统。
        boolean dark = ThemeUtils.shouldEnableDarkTheme(
            context, NightMode.getAppNightMode().getName());
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
        int[] ansi = new int[16];
        System.arraycopy(builtIn.ansi, 0, ansi, 0, 16);
        for (int i = 0; i < 16; i++) {
            int color = parse(props.getProperty("color" + i), ansi[i]);
            ansi[i] = color;
        }

        return new Palette(true, foreground, background, selection, cursor, ansi);
    }

    /** 内置明/暗配色（无 colors.properties 时）。 */
    @NonNull
    public static Palette builtIn(boolean dark) {
        if (dark) {
            return new Palette(false, DARK_FOREGROUND, DARK_BACKGROUND, DARK_SELECTION, DARK_CURSOR, DARK_ANSI);
        }
        return new Palette(false, LIGHT_FOREGROUND, LIGHT_BACKGROUND, LIGHT_SELECTION, LIGHT_CURSOR, LIGHT_ANSI);
    }

    /** 把当前配色板 push 到 CoreAdapter 缝（不支持配色板的实现直接忽略）。 */
    public static void apply(@NonNull Context context, CoreAdapter adapter) {
        apply(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, adapter,
            ThemeUtils.shouldEnableDarkTheme(context, NightMode.getAppNightMode().getName()));
    }

    /** push 入口（测试用）。 */
    static void apply(File colorsFile, CoreAdapter adapter, boolean dark) {
        if (adapter == null || !adapter.supportsPalette()) return;
        Palette palette = resolve(colorsFile, dark);
        adapter.setPalette(palette.foreground, palette.background, palette.selection, palette.cursor);
        adapter.setAnsiPalette(palette.ansi);
    }

    private static int parse(String value, int fallback) {
        if (value == null) return fallback;
        int color = parseColor(value.trim());
        return color == 0 ? fallback : color;
    }

    /**
     * 工单 31：颜色解析（旧 TerminalColors#parse 迁移）。支持 XQueryColor 格式：
     * #RGB/#RRGGBB/#RRRGGGBBB/#RRRRGGGGBBBB 与 rgb:<r>/<g>/<b>（1-4 位十六进制）。
     * 成功返回 0xFFRRGGBB，失败返回 0。
     */
    private static int parseColor(String c) {
        try {
            int skipInitial, skipBetween;
            if (c.charAt(0) == '#') {
                skipInitial = 1;
                skipBetween = 0;
            } else if (c.startsWith("rgb:")) {
                skipInitial = 4;
                skipBetween = 1;
            } else {
                return 0;
            }
            int charsForColors = c.length() - skipInitial - 2 * skipBetween;
            if (charsForColors % 3 != 0) return 0;
            int componentLength = charsForColors / 3;
            double mult = 255 / (Math.pow(2, componentLength * 4) - 1);

            int currentPosition = skipInitial;
            String rString = c.substring(currentPosition, currentPosition + componentLength);
            currentPosition += componentLength + skipBetween;
            String gString = c.substring(currentPosition, currentPosition + componentLength);
            currentPosition += componentLength + skipBetween;
            String bString = c.substring(currentPosition, currentPosition + componentLength);

            int r = (int) (Integer.parseInt(rString, 16) * mult);
            int g = (int) (Integer.parseInt(gString, 16) * mult);
            int b = (int) (Integer.parseInt(bString, 16) * mult);
            return 0xFF << 24 | r << 16 | g << 8 | b;
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return 0;
        }
    }

    /** 背景亮则黑光标，背景暗则白光标（与 TerminalColorScheme 一致）。 */
    private static int cursorFor(int background) {
        return isDarkBackground(background) ? 0xFFFFFFFF : 0xFF000000;
    }

    /** 背景是否偏暗（供状态栏图标取色/光标对比复用）。 */
    public static boolean isDarkBackground(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double brightness = Math.sqrt(r * r * 0.241 + g * g * 0.691 + b * b * 0.068);
        return brightness < 130;
    }
}
