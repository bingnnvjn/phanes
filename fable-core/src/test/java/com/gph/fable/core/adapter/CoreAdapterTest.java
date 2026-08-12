package com.gph.fable.core.adapter;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 缝契约测试（JVM）：CoreAdapter 行为契约，用 fake in-memory 核心断言。
 *
 * JVM 内无法加载 libfable-render.so（aarch64 Android ELF），因此缝契约用
 * fake 核心断言；新路径（fable-render）同一组行为由 Rust 单测、离屏自检
 * 与真机验收（工单 40）覆盖。旧路径实现（TerminalEmulatorCoreAdapter）已随
 * 工单 31 删除。
 */
public class CoreAdapterTest {

    private FakeCoreAdapter mFake;

    @Before
    public void setUp() {
        mFake = new FakeCoreAdapter(10, 5);
    }

    @Test
    public void fakeCoreTitleBellModes() {
        // OSC 0 标题 + 消费语义。
        byte[] title0 = "\u001b]0;fake-title\u0007".getBytes(StandardCharsets.UTF_8);
        mFake.write(title0, title0.length);
        assertEquals("fake-title", mFake.getTitle());
        assertTrue("首次读取应消费标题变更", mFake.consumeTitleChanged());
        assertFalse("消费后标记应清除", mFake.consumeTitleChanged());

        // OSC 2 覆盖标题（标题保留，标记再次置位）。
        byte[] title2 = "\u001b]2;second-title\u0007".getBytes(StandardCharsets.UTF_8);
        mFake.write(title2, title2.length);
        assertEquals("second-title", mFake.getTitle());
        assertTrue(mFake.consumeTitleChanged());

        // BEL：OSC 终止 BEL 不算 bell，独立 BEL 才算。
        mFake.write(new byte[] { 0x07 }, 1);
        assertTrue(mFake.consumeBell());
        assertFalse("消费后 bell 应清除", mFake.consumeBell());

        // 模式：alt screen / mouse tracking / 光标隐藏 / 闪烁。
        byte[] on = "\u001b[?1049h\u001b[?1000h\u001b[?25l\u001b[?12h"
                .getBytes(StandardCharsets.UTF_8);
        mFake.write(on, on.length);
        assertTrue(mFake.getModeAlternateScreen());
        assertTrue(mFake.getModeMouseTracking());
        assertFalse(mFake.getModeCursorVisible());
        assertTrue(mFake.getModeCursorBlink());

        // X10（?9）单独开启也计入 mouse tracking（与核心 any-mode 语义一致）。
        byte[] x10 = "\u001b[?1000l\u001b[?9h".getBytes(StandardCharsets.UTF_8);
        mFake.write(x10, x10.length);
        assertTrue("X10 也属于 mouse tracking", mFake.getModeMouseTracking());

        byte[] off = "\u001b[?1049l\u001b[?1000l\u001b[?25h\u001b[?12l"
                .getBytes(StandardCharsets.UTF_8);
        byte[] x10Off = "\u001b[?9l".getBytes(StandardCharsets.UTF_8);
        mFake.write(x10Off, x10Off.length);
        mFake.write(off, off.length);
        assertFalse(mFake.getModeAlternateScreen());
        assertFalse(mFake.getModeMouseTracking());
        assertTrue(mFake.getModeCursorVisible());
        assertFalse(mFake.getModeCursorBlink());
    }

    @Test
    public void fakeCoreCursorKeysKeypadBracketedAndBlinkPhase() {
        // 初始安全值。
        assertFalse(mFake.getModeCursorKeysApplication());
        assertFalse(mFake.getModeKeypadApplication());
        assertFalse(mFake.getModeBracketedPaste());
        assertTrue("初始闪烁相位可见", mFake.getCursorBlinkPhase());

        // DECCKM / DECKPAM / bracketed paste 开启。
        byte[] on = "\u001b[?1h\u001b[?66h\u001b[?2004h".getBytes(StandardCharsets.UTF_8);
        mFake.write(on, on.length);
        assertTrue(mFake.getModeCursorKeysApplication());
        assertTrue(mFake.getModeKeypadApplication());
        assertTrue(mFake.getModeBracketedPaste());

        // 光标闪烁相位 feed：UI 闪烁线程推送，渲染层与核心可见性 AND。
        mFake.setCursorBlinkState(false);
        assertFalse(mFake.getCursorBlinkPhase());
        mFake.setCursorBlinkState(true);
        assertTrue(mFake.getCursorBlinkPhase());

        // 全部关闭。
        byte[] off = "\u001b[?1l\u001b[?66l\u001b[?2004l".getBytes(StandardCharsets.UTF_8);
        mFake.write(off, off.length);
        assertFalse(mFake.getModeCursorKeysApplication());
        assertFalse(mFake.getModeKeypadApplication());
        assertFalse(mFake.getModeBracketedPaste());
    }

    @Test
    public void fakeCoreSelectionAndScroll() {
        mFake = new FakeCoreAdapter(10, 3);
        byte[] data = "hello\r\nworld\r\nrow2\r\nrow3\r\nrow4\r\n"
                .getBytes(StandardCharsets.UTF_8);
        mFake.write(data, data.length);

        // 底部视口 = 最新 mRows 行（external row 0 = 屏幕顶行，与核心一致）。
        mFake.setSelection(0, 0, 4);
        assertEquals("row2", mFake.getSelectionText());
        mFake.setSelection(0, 0, 0);
        mFake.setSelection(1, 0, 4);
        assertEquals("row3", mFake.getSelectionText());

        // 多行选择按行序拼接（跨行 '\n'，与 fable-render selection_text 一致）。
        mFake.setSelection(0, 0, 4);
        assertEquals("row2\nrow3", mFake.getSelectionText());

        // 滚动：向上钳制到 transcript 顶（视口行内容随滚动变化）。
        mFake.scroll(-100);
        assertEquals(-2, mFake.getTopRow());
        mFake.setSelection(1, 0, 0);
        mFake.setSelection(2, 0, 0);
        mFake.setSelection(0, 0, 5);
        assertEquals("hello", mFake.getSelectionText());
        mFake.setSelection(0, 0, 0);
        mFake.setSelection(1, 0, 5);
        assertEquals("world", mFake.getSelectionText());

        // 向下滚动回到最新。
        mFake.scroll(100);
        assertEquals(0, mFake.getTopRow());
        mFake.setSelection(1, 0, 0);
        mFake.setSelection(0, 0, 4);
        assertEquals("row2", mFake.getSelectionText());
    }

    @Test
    public void fakeCoreScrollbackRowsAndTranscript() {
        mFake = new FakeCoreAdapter(6, 3);
        byte[] data = "aa\r\nbb\r\ncc\r\ndd\r\nee\r\n".getBytes(StandardCharsets.UTF_8);
        mFake.write(data, data.length);

        // 5 行输出在 3 行屏上留下 2 行历史（与核心 scrollback 语义一致）。
        assertEquals(2, mFake.getScrollbackRows());

        // 转录：活动屏 + 历史按行连接。
        assertEquals("aa\nbb\ncc\ndd\nee", mFake.getTranscriptText(false, false));
        assertEquals("aa\nbb\ncc\ndd\nee", mFake.getTranscriptText(true, false));
        assertEquals("aa\nbb\ncc\ndd\nee", mFake.getTranscriptText(false, true));

        // 外部行语义：0 = 活动屏顶行，负 = 历史。
        assertEquals("cc", mFake.getText(0, 0, 2));
        assertEquals("aa", mFake.getText(-2, 0, 2));
        assertEquals("", mFake.getText(-3, 0, 2));
        assertEquals("bb", mFake.getText(-1, 0, 2));
    }

    @Test
    public void fakeCoreWordQueries() {
        mFake = new FakeCoreAdapter(10, 3);
        byte[] data = "hello world\r\nfoo-bar baz\r\nfill\r\nanother\r\n"
                .getBytes(StandardCharsets.UTF_8);
        mFake.write(data, data.length);

        // 4 行输出在 3 行屏上留 1 行历史；外部行 0 = 活动屏顶行 "foo-bar baz"。
        assertArrayEquals(new int[] { 0, 7 }, mFake.getWordBoundsAt(3, 0));
        assertEquals("foo-bar", mFake.getWordAt(3, 0));
        assertArrayEquals(new int[] { 8, 11 }, mFake.getWordBoundsAt(9, 0));
        assertEquals("baz", mFake.getWordAt(9, 0));

        // 空格上无单词。
        assertNull(mFake.getWordBoundsAt(7, 0));
        assertEquals("", mFake.getWordAt(7, 0));

        // 历史行（外部行 -1 = "hello world"）。
        assertArrayEquals(new int[] { 6, 11 }, mFake.getWordBoundsAt(8, -1));
        assertEquals("world", mFake.getWordAt(8, -1));
    }

    @Test
    public void fakeCoreMouseProtocolModes() {
        assertFalse(mFake.getModeMouseSgr());
        assertFalse(mFake.getModeMouseButtonEvent());

        // SGR（1006）+ button-event（1002）。
        byte[] on = "\u001b[?1006h\u001b[?1002h".getBytes(StandardCharsets.UTF_8);
        mFake.write(on, on.length);
        assertTrue(mFake.getModeMouseSgr());
        assertTrue(mFake.getModeMouseButtonEvent());

        // any-event（1003）也计入 button-event。
        byte[] any = "\u001b[?1002l\u001b[?1003h".getBytes(StandardCharsets.UTF_8);
        mFake.write(any, any.length);
        assertTrue(mFake.getModeMouseButtonEvent());

        byte[] off = "\u001b[?1006l\u001b[?1003l".getBytes(StandardCharsets.UTF_8);
        mFake.write(off, off.length);
        assertFalse(mFake.getModeMouseSgr());
        assertFalse(mFake.getModeMouseButtonEvent());
    }

    @Test
    public void fakeCoreSafeDefaults() {
        // 空核心的安全默认值。
        assertEquals("", mFake.getTitle());
        assertFalse(mFake.consumeTitleChanged());
        assertFalse(mFake.consumeBell());
        assertFalse(mFake.getModeAlternateScreen());
        assertFalse(mFake.getModeMouseTracking());
        assertTrue(mFake.getModeCursorVisible());
        assertFalse(mFake.getModeCursorBlink());
        assertFalse(mFake.getModeCursorKeysApplication());
        assertFalse(mFake.getModeKeypadApplication());
        assertFalse(mFake.getModeBracketedPaste());
        assertFalse(mFake.getModeMouseSgr());
        assertFalse(mFake.getModeMouseButtonEvent());
        assertEquals(0, mFake.getScrollbackRows());
        assertEquals("", mFake.getText(0, 0, 5));
        assertNull(mFake.getWordBoundsAt(0, 0));
        assertEquals("", mFake.getWordAt(0, 0));
        assertEquals("", mFake.getTranscriptText(false, false));
    }

    /**
     * fake 核心实现：模拟 libghostty-vt 的 title/bell/mode 解析与
     * 行缓冲/视口滚动，覆盖 CoreAdapter 缝在 JVM 内的行为契约
     * （真实核心行为由 Rust 单测与离屏自检覆盖）。
     */
    private static final class FakeCoreAdapter implements CoreAdapter {

        private final int mColumns;
        private int mRows;
        private final List<String> mLines = new ArrayList<>();
        private final StringBuilder mCurrent = new StringBuilder();
        private final Map<Integer, int[]> mSelection = new TreeMap<>();
        private int mTopRow;

        private String mTitle = "";
        private boolean mTitleChanged;
        private boolean mBell;
        private boolean mAltScreen;
        private boolean mMouseX10;
        private boolean mMouseNormal;
        private boolean mMouseButton;
        private boolean mMouseAny;
        private boolean mCursorVisible = true;
        private boolean mCursorBlink;
        private boolean mCursorKeysApp;
        private boolean mKeypadApp;
        private boolean mBracketedPaste;
        private boolean mMouseSgr;
        private boolean mCursorBlinkPhase = true;

        FakeCoreAdapter(int columns, int rows) {
            this.mColumns = columns;
            this.mRows = rows;
        }

        @Override
        public void write(byte[] data, int len) {
            feed(new String(data, 0, len, StandardCharsets.UTF_8));
        }

        private void feed(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\u001b' && i + 1 < s.length() && s.charAt(i + 1) == ']') {
                    int bel = s.indexOf('\u0007', i);
                    int st = s.indexOf("\u001b\\", i);
                    int term;
                    if (bel == -1 && st == -1) return;
                    if (bel == -1) {
                        term = st;
                    } else if (st == -1) {
                        term = bel;
                    } else {
                        term = Math.min(bel, st);
                    }
                    String osc = s.substring(i + 2, term);
                    int semi = osc.indexOf(';');
                    if (semi > 0) {
                        String cmd = osc.substring(0, semi);
                        if (cmd.equals("0") || cmd.equals("2")) {
                            mTitle = osc.substring(semi + 1);
                            mTitleChanged = true;
                        }
                    }
                    i = term + (st == term ? 2 : 1);
                    continue;
                }
                if (c == '\u0007') {
                    mBell = true;
                    continue;
                }
                if (c == '\u001b' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                    int h = s.indexOf('h', i);
                    int l = s.indexOf('l', i);
                    int t;
                    boolean set;
                    if (h == -1 && l == -1) return;
                    if (h == -1) {
                        t = l;
                        set = false;
                    } else if (l == -1) {
                        t = h;
                        set = true;
                    } else if (h < l) {
                        t = h;
                        set = true;
                    } else {
                        t = l;
                        set = false;
                    }
                    String body = s.substring(i + 2, t);
                    if (body.equals("?1047") || body.equals("?1049")) mAltScreen = set;
                    if (body.equals("?9")) mMouseX10 = set;
                    if (body.equals("?1000")) mMouseNormal = set;
                    if (body.equals("?1002")) mMouseButton = set;
                    if (body.equals("?1003")) mMouseAny = set;
                    if (body.equals("?1006")) mMouseSgr = set;
                    if (body.equals("?25")) mCursorVisible = set;
                    if (body.equals("?12")) mCursorBlink = set;
                    if (body.equals("?1")) mCursorKeysApp = set;
                    if (body.equals("?66")) mKeypadApp = set;
                    if (body.equals("?2004")) mBracketedPaste = set;
                    i = t;
                    continue;
                }
                if (c == '\r' || c == '\n') {
                    if (mCurrent.length() > 0) {
                        mLines.add(mCurrent.toString());
                        mCurrent.setLength(0);
                    }
                    continue;
                }
                mCurrent.append(c);
            }
        }

        @Override
        public String getTitle() {
            return mTitle;
        }

        @Override
        public boolean consumeTitleChanged() {
            boolean changed = mTitleChanged;
            mTitleChanged = false;
            return changed;
        }

        @Override
        public boolean consumeBell() {
            boolean bell = mBell;
            mBell = false;
            return bell;
        }

        @Override
        public boolean getModeAlternateScreen() {
            return mAltScreen;
        }

        @Override
        public boolean getModeMouseTracking() {
            return mMouseX10 || mMouseNormal || mMouseButton || mMouseAny;
        }

        @Override
        public boolean getModeCursorVisible() {
            return mCursorVisible;
        }

        @Override
        public boolean getModeCursorBlink() {
            return mCursorBlink;
        }

        @Override
        public boolean getModeCursorKeysApplication() {
            return mCursorKeysApp;
        }

        @Override
        public boolean getModeKeypadApplication() {
            return mKeypadApp;
        }

        @Override
        public boolean getModeBracketedPaste() {
            return mBracketedPaste;
        }

        @Override
        public void setCursorBlinkState(boolean cursorVisible) {
            mCursorBlinkPhase = cursorVisible;
        }

        @Override
        public int getScrollbackRows() {
            return Math.max(0, mLines.size() - mRows);
        }

        @Override
        public String getText(int row, int startCol, int endCol) {
            int lineIndex = row + (mLines.size() - mRows);
            if (lineIndex < 0 || lineIndex >= mLines.size()) return "";
            String line = mLines.get(lineIndex);
            StringBuilder out = new StringBuilder();
            int end = Math.min(endCol, line.length());
            for (int c = Math.max(0, startCol); c < end; c++) {
                out.append(line.charAt(c));
            }
            return out.toString();
        }

        @Override
        public int[] getWordBoundsAt(int column, int externalRow) {
            int lineIndex = externalRow + (mLines.size() - mRows);
            if (lineIndex < 0 || lineIndex >= mLines.size()) return null;
            String line = mLines.get(lineIndex);
            if (column < 0 || column >= line.length() || line.charAt(column) == ' ') return null;
            int start = column;
            int end = column;
            while (start > 0 && line.charAt(start - 1) != ' ') start--;
            while (end < line.length() && line.charAt(end) != ' ') end++;
            return new int[] { start, end };
        }

        @Override
        public String getWordAt(int column, int externalRow) {
            int[] bounds = getWordBoundsAt(column, externalRow);
            if (bounds == null) return "";
            int lineIndex = externalRow + (mLines.size() - mRows);
            return mLines.get(lineIndex).substring(bounds[0], bounds[1]);
        }

        @Override
        public boolean getModeMouseSgr() {
            return mMouseSgr;
        }

        @Override
        public boolean getModeMouseButtonEvent() {
            return mMouseButton || mMouseAny;
        }

        @Override
        public String getTranscriptText(boolean linesJoined, boolean trim) {
            String out = String.join("\n", mLines);
            return trim ? out.trim() : out;
        }

        @Override
        public void resize(int columns, int rows) {
            mRows = rows;
        }

        @Override
        public void scroll(int delta) {
            int minTop = Math.min(-(mLines.size() - mRows), 0);
            mTopRow = Math.min(0, Math.max(minTop, mTopRow + delta));
        }

        @Override
        public void setSelection(int row, int startCol, int endCol) {
            if (endCol > startCol) {
                mSelection.put(row, new int[] { startCol, endCol });
            } else {
                mSelection.remove(row);
            }
        }

        @Override
        public String getSelectionText() {
            StringBuilder out = new StringBuilder();
            boolean first = true;
            for (Map.Entry<Integer, int[]> entry : mSelection.entrySet()) {
                int externalRow = mTopRow + entry.getKey();
                int lineIndex = externalRow + (mLines.size() - mRows);
                if (lineIndex < 0 || lineIndex >= mLines.size()) continue;
                String line = mLines.get(lineIndex);
                int[] range = entry.getValue();
                int start = Math.min(range[0], line.length());
                int end = Math.min(range[1], line.length());
                if (end <= start) continue;
                if (!first) out.append('\n');
                first = false;
                out.append(line, start, end);
            }
            return out.toString();
        }

        @Override
        public void setFontSize(float sizePx) {
        }

        @Override
        public void getCellSize(int[] out) {
        }

        @Override
        public void setPalette(int fgArgb, int bgArgb, int selectionArgb, int cursorArgb) {
        }

        @Override
        public void setAnsiPalette(int[] ansiArgb) {
        }

        @Override
        public void resetPalette() {
        }

        @Override
        public void attach(android.view.Surface surface, int widthPx, int heightPx) {
        }

        @Override
        public void detach() {
        }

        @Override
        public void render(int widthPx, int heightPx) {
        }

        @Override
        public void forceRender(int widthPx, int heightPx) {
        }

        @Override
        public void reset() {
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean supportsSelectionText() {
            return true;
        }

        @Override
        public boolean supportsFontSize() {
            return false;
        }

        @Override
        public boolean supportsPalette() {
            return false;
        }

        @Override
        public boolean supportsScrollback() {
            return true;
        }

        int getTopRow() {
            return mTopRow;
        }

        boolean getCursorBlinkPhase() {
            return mCursorBlinkPhase;
        }
    }
}
