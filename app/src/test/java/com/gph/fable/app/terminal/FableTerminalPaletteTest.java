package com.gph.fable.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Surface;

import com.gph.fable.terminal.adapter.CoreAdapter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 缝 2（JVM）：终端配色板——colors.properties 解析、内置明暗、缝 push 行为。 */
public class FableTerminalPaletteTest {

    @Rule
    public TemporaryFolder mTmpDir = new TemporaryFolder();

    private File writeColorsFile(String content) throws Exception {
        File file = mTmpDir.newFile("colors.properties");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    public void builtInDarkAndLight() {
        FableTerminalPalette.Palette dark = FableTerminalPalette.builtIn(true);
        assertFalse(dark.custom);
        assertEquals(0xFFFFFFFF, dark.foreground);
        assertEquals(0xFF000000, dark.background);
        assertEquals(0xFF335588, dark.selection);
        assertEquals(0xFFFFFFFF, dark.cursor);

        FableTerminalPalette.Palette light = FableTerminalPalette.builtIn(false);
        assertFalse(light.custom);
        assertEquals(0xFF000000, light.foreground);
        assertEquals(0xFFFFFFFF, light.background);
        assertEquals(0xFFBBDEFB, light.selection);
        assertEquals(0xFF000000, light.cursor);
        assertEquals(0xFFC50F1F, light.ansi[1]);
        assertEquals(0xFF0037DA, light.ansi[4]);
    }

    @Test
    public void fromPropertiesParsesForegroundBackgroundCursor() {
        Properties props = new Properties();
        props.setProperty("foreground", "#123456");
        props.setProperty("background", "rgb:ff/00/00");
        props.setProperty("cursor", "#abcdef");
        props.setProperty("color5", "#ff00ff");

        FableTerminalPalette.Palette palette = FableTerminalPalette.fromProperties(props, true);

        assertTrue(palette.custom);
        assertEquals(0xFF123456, palette.foreground);
        assertEquals(0xFFFF0000, palette.background);
        assertEquals(0xFFABCDEF, palette.cursor);
        // color5 进 ANSI 数组，其余按内置深色回退。
        assertEquals(0xFF335588, palette.selection);
        assertEquals(0xFFFF00FF, palette.ansi[5]);
        assertEquals(0xFFCD0000, palette.ansi[1]);
    }

    @Test
    public void missingCursorUsesContrastAgainstBackground() {
        Properties darkBg = new Properties();
        darkBg.setProperty("background", "#000000");
        assertEquals(0xFFFFFFFF, FableTerminalPalette.fromProperties(darkBg, true).cursor);

        Properties lightBg = new Properties();
        lightBg.setProperty("background", "#ffffff");
        assertEquals(0xFF000000, FableTerminalPalette.fromProperties(lightBg, true).cursor);
    }

    @Test
    public void missingColorsFallBackToBuiltInForThatMode() {
        Properties props = new Properties();
        props.setProperty("foreground", "#ff0000");

        FableTerminalPalette.Palette dark = FableTerminalPalette.fromProperties(props, true);
        assertEquals(0xFFFF0000, dark.foreground);
        assertEquals(0xFF000000, dark.background);

        FableTerminalPalette.Palette light = FableTerminalPalette.fromProperties(props, false);
        assertEquals(0xFFFF0000, light.foreground);
        assertEquals(0xFFFFFFFF, light.background);
    }

    @Test
    public void resolvePrefersColorsFileAndIgnoresMode() throws Exception {
        File colorsFile = writeColorsFile("foreground=#112233\nbackground=#445566\ncursor=#778899\n");

        FableTerminalPalette.Palette dark = FableTerminalPalette.resolve(colorsFile, true);
        FableTerminalPalette.Palette light = FableTerminalPalette.resolve(colorsFile, false);

        assertTrue(dark.custom);
        assertTrue(light.custom);
        assertEquals(0xFF112233, dark.foreground);
        assertEquals(0xFF445566, dark.background);
        assertEquals(0xFF778899, dark.cursor);
        // 有自定义配色时不随系统明暗切换（ADR-0002）。
        assertEquals(dark.foreground, light.foreground);
        assertEquals(dark.background, light.background);
    }

    @Test
    public void resolveUsesBuiltInWhenNoColorsFile() throws Exception {
        File missing = new File(mTmpDir.getRoot(), "does-not-exist.properties");

        FableTerminalPalette.Palette dark = FableTerminalPalette.resolve(missing, true);
        FableTerminalPalette.Palette light = FableTerminalPalette.resolve(missing, false);

        assertFalse(dark.custom);
        assertFalse(light.custom);
        assertEquals(0xFF000000, dark.background);
        assertEquals(0xFFFFFFFF, light.background);
    }

    @Test
    public void applyPushesPaletteOnlyWhenSupported() throws Exception {
        RecordingAdapter supporting = new RecordingAdapter(true);
        FableTerminalPalette.apply(mTmpDir.getRoot().getAbsoluteFile(), supporting, true);
        assertEquals(1, supporting.mPalettePushes);
        assertEquals(1, supporting.mAnsiPushes);
        assertEquals(0xFFFFFFFF, supporting.mFg);
        assertEquals(0xFF000000, supporting.mBg);
        assertEquals(0xFFCD0000, supporting.mAnsi[1]);

        RecordingAdapter unsupported = new RecordingAdapter(false);
        FableTerminalPalette.apply(mTmpDir.getRoot().getAbsoluteFile(), unsupported, true);
        assertEquals(0, unsupported.mPalettePushes);
        assertEquals(0, unsupported.mAnsiPushes);
    }

    private static final class RecordingAdapter implements CoreAdapter {
        final boolean mSupportsPalette;
        int mPalettePushes;
        int mAnsiPushes;
        int mFg;
        int mBg;
        int[] mAnsi;

        RecordingAdapter(boolean supportsPalette) {
            mSupportsPalette = supportsPalette;
        }

        @Override
        public void setPalette(int fgArgb, int bgArgb, int selectionArgb, int cursorArgb) {
            mPalettePushes++;
            mFg = fgArgb;
            mBg = bgArgb;
        }

        @Override
        public void setAnsiPalette(int[] ansiArgb) {
            mAnsiPushes++;
            mAnsi = ansiArgb;
        }

        @Override
        public boolean supportsPalette() {
            return mSupportsPalette;
        }

        @Override
        public void write(byte[] data, int len) {
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public void scroll(int delta) {
        }

        @Override
        public void setSelection(int row, int startCol, int endCol) {
        }

        @Override
        public String getSelectionText() {
            return "";
        }

        @Override
        public void setFontSize(float sizePx) {
        }

        @Override
        public void getCellSize(int[] out) {
        }

        @Override
        public void resetPalette() {
        }

        @Override
        public void attach(Surface surface, int widthPx, int heightPx) {
        }

        @Override
        public void detach() {
        }

        @Override
        public void render(int widthPx, int heightPx) {
        }

        @Override
        public void reset() {
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean supportsSelectionText() {
            return false;
        }

        @Override
        public boolean supportsFontSize() {
            return false;
        }

        @Override
        public boolean supportsScrollback() {
            return false;
        }
    }
}
