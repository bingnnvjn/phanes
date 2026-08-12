package com.gph.fable.terminal.adapter;

import com.gph.fable.terminal.TerminalEmulator;
import com.gph.fable.terminal.TerminalOutput;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 缝 2（核心逻辑 JVM 单测）：CoreAdapter 缝行为契约。
 *
 * JVM 内无法加载 libfable-render.so（aarch64 Android ELF），因此缝契约用
 * 旧路径实现 {@link TerminalEmulatorCoreAdapter} 断言（字节→状态 / resize
 * 重排 / 滚动）；新路径（fable-render）同一组行为由缝 1 instrumentation
 * （FableRenderCoreAdapterInstrumentedTest）与真机验收覆盖。
 */
public class CoreAdapterTest {

    private static final int CELL_W = 13;
    private static final int CELL_H = 15;

    private TerminalEmulator mEmulator;
    private TerminalEmulatorCoreAdapter mAdapter;

    private static class MockTerminalOutput extends TerminalOutput {
        @Override
        public void write(byte[] data, int offset, int count) {
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
        }

        @Override
        public void onCopyTextToClipboard(String text) {
        }

        @Override
        public void onPasteTextFromClipboard() {
        }

        @Override
        public void onBell() {
        }

        @Override
        public void onColorsChanged() {
        }
    }

    @Before
    public void setUp() {
        mEmulator = new TerminalEmulator(new MockTerminalOutput(), 10, 5, CELL_W, CELL_H, 10, null);
        mAdapter = new TerminalEmulatorCoreAdapter(mEmulator, CELL_W, CELL_H);
    }

    @Test
    public void writeBytesThenSelectionText() {
        byte[] data = "hello\r\nworld\r\n".getBytes(StandardCharsets.UTF_8);
        mAdapter.write(data, data.length);

        mAdapter.setSelection(0, 0, 5);
        assertEquals("hello", mAdapter.getSelectionText());

        // 缝语义：overlay 逐行独立存续，换行选择前先清除旧行。
        mAdapter.setSelection(0, 0, 0);
        mAdapter.setSelection(1, 0, 5);
        assertEquals("world", mAdapter.getSelectionText());

        // 多行选择按行序拼接，跨行 '\n'（与 fable-render selection_text 语义一致）。
        mAdapter.setSelection(0, 0, 0);
        mAdapter.setSelection(0, 0, 5);
        assertEquals("hello\nworld", mAdapter.getSelectionText());

        // startCol == endCol 清除该行。
        mAdapter.setSelection(0, 0, 0);
        assertEquals("world", mAdapter.getSelectionText());
    }

    @Test
    public void resizeReflowsWrappedLine() {
        byte[] data = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        mAdapter.write(data, data.length);

        // 10 列下折成两行。
        mAdapter.setSelection(0, 0, 10);
        assertEquals("0123456789", mAdapter.getSelectionText());
        mAdapter.setSelection(0, 0, 0);
        mAdapter.setSelection(1, 0, 6);
        assertEquals("ABCDEF", mAdapter.getSelectionText());

        // 扩到 20 列后重排为一行，选中整行取回完整文本。
        mAdapter.resize(20, 5);
        assertEquals(20, mEmulator.mColumns);
        assertEquals(5, mEmulator.mRows);
        mAdapter.setSelection(1, 0, 0);
        mAdapter.setSelection(0, 0, 16);
        assertEquals("0123456789ABCDEF", mAdapter.getSelectionText());
    }

    @Test
    public void scrollClampsToTranscript() {
        for (int i = 0; i < 15; i++) {
            byte[] line = ("line" + i + "\r\n").getBytes(StandardCharsets.UTF_8);
            mAdapter.write(line, line.length);
        }

        // 15 行 CRLF 输出在 5 行屏上产生 11 行 transcript；向上滚动被钳制到 transcript 顶。
        mAdapter.scroll(-100);
        assertEquals(-11, mAdapter.getTopRow());

        // 向下滚动回到最新。
        mAdapter.scroll(100);
        assertEquals(0, mAdapter.getTopRow());
    }

    @Test
    public void scrollbackSelectionUsesViewportRelativeRows() {
        for (int i = 0; i < 12; i++) {
            byte[] line = ("row" + i).getBytes(StandardCharsets.UTF_8);
            mAdapter.write(line, line.length);
            mAdapter.write(new byte[] { '\r', '\n' }, 2);
        }

        // 回到 transcript 顶。缝约定 0 = 视口顶行（旧路径 TerminalBuffer 外部坐标
        // 同样以 0 = 屏幕顶行为基准，行内容随视口滚动而变）。
        mAdapter.scroll(-100);
        int topRow = mAdapter.getTopRow();
        assertTrue(topRow < 0);

        // 用旧路径 TerminalBuffer 自身确认视口顶行内容，再断言适配器选择取到同一行。
        int visibleRows = 5;
        String expectedTop = mEmulator.getScreen()
                .getSelectedText(0, topRow, mEmulator.mColumns, topRow + visibleRows - 1);
        assertTrue(expectedTop.length() > 0);

        // 视口相对行 1..2 跨两行选择：内容 = 视口顶行的后两行。
        String[] topLines = expectedTop.split("\n");
        assertTrue("transcript 应至少 3 行可见, got=" + expectedTop, topLines.length >= 3);
        String expected = topLines[1] + "\n" + topLines[2];
        mAdapter.setSelection(1, 0, 4);
        mAdapter.setSelection(2, 0, 4);
        assertEquals(expected, mAdapter.getSelectionText());

        // 单行选择（视口行 0 = 视口顶行）。
        mAdapter.setSelection(1, 0, 0);
        mAdapter.setSelection(2, 0, 0);
        mAdapter.setSelection(0, 0, 4);
        assertEquals(topLines[0], mAdapter.getSelectionText());
    }

    @Test
    public void capabilitiesDescribeLegacyPath() {
        assertTrue(mAdapter.supportsSelectionText());
        assertTrue(mAdapter.supportsScrollback());
        assertFalse(mAdapter.supportsFontSize());
        assertFalse(mAdapter.supportsPalette());

        int[] cell = new int[2];
        mAdapter.getCellSize(cell);
        assertEquals(CELL_W, cell[0]);
        assertEquals(CELL_H, cell[1]);
    }

    @Test
    public void newCapabilitiesDefaultToSafeValues() {
        // 新能力全部走 CoreAdapter 默认方法，既有实现（旧路径）不破坏编译。
        assertEquals("", mAdapter.getTitle());
        assertFalse(mAdapter.consumeTitleChanged());
        assertFalse(mAdapter.consumeBell());
        assertFalse(mAdapter.getModeAlternateScreen());
        assertFalse(mAdapter.getModeMouseTracking());
        assertTrue(mAdapter.getModeCursorVisible());
        assertFalse(mAdapter.getModeCursorBlink());
    }

    @Test
    public void fakeCoreTitleBellModes() {
        FakeCoreAdapter fake = new FakeCoreAdapter(10, 5);

        // OSC 0 标题 + 消费语义。
        byte[] title0 = "\u001b]0;fake-title\u0007".getBytes(StandardCharsets.UTF_8);
        fake.write(title0, title0.length);
        assertEquals("fake-title", fake.getTitle());
        assertTrue("首次读取应消费标题变更", fake.consumeTitleChanged());
        assertFalse("消费后标记应清除", fake.consumeTitleChanged());

        // OSC 2 覆盖标题（标题保留，标记再次置位）。
        byte[] title2 = "\u001b]2;second-title\u0007".getBytes(StandardCharsets.UTF_8);
        fake.write(title2, title2.length);
        assertEquals("second-title", fake.getTitle());
        assertTrue(fake.consumeTitleChanged());

        // BEL：OSC 终止 BEL 不算 bell，独立 BEL 才算。
        fake.write(new byte[] { 0x07 }, 1);
        assertTrue(fake.consumeBell());
        assertFalse("消费后 bell 应清除", fake.consumeBell());

        // 模式：alt screen / mouse tracking / 光标隐藏 / 闪烁。
        byte[] on = "\u001b[?1049h\u001b[?1000h\u001b[?25l\u001b[?12h"
                .getBytes(StandardCharsets.UTF_8);
        fake.write(on, on.length);
        assertTrue(fake.getModeAlternateScreen());
        assertTrue(fake.getModeMouseTracking());
        assertFalse(fake.getModeCursorVisible());
        assertTrue(fake.getModeCursorBlink());

        // X10（?9）单独开启也计入 mouse tracking（与核心 any-mode 语义一致）。
        byte[] x10 = "\u001b[?1000l\u001b[?9h".getBytes(StandardCharsets.UTF_8);
        fake.write(x10, x10.length);
        assertTrue("X10 也属于 mouse tracking", fake.getModeMouseTracking());

        byte[] off = "\u001b[?1049l\u001b[?1000l\u001b[?25h\u001b[?12l"
                .getBytes(StandardCharsets.UTF_8);
        byte[] x10Off = "\u001b[?9l".getBytes(StandardCharsets.UTF_8);
        fake.write(x10Off, x10Off.length);
        fake.write(off, off.length);
        assertFalse(fake.getModeAlternateScreen());
        assertFalse(fake.getModeMouseTracking());
        assertTrue(fake.getModeCursorVisible());
        assertFalse(fake.getModeCursorBlink());
    }

    @Test
    public void fakeCoreSelectionAndScroll() {
        FakeCoreAdapter fake = new FakeCoreAdapter(10, 3);
        byte[] data = "hello\r\nworld\r\nrow2\r\nrow3\r\nrow4\r\n"
                .getBytes(StandardCharsets.UTF_8);
        fake.write(data, data.length);

        // 底部视口 = 最新 mRows 行（external row 0 = 屏幕顶行，与旧路径一致）。
        fake.setSelection(0, 0, 4);
        assertEquals("row2", fake.getSelectionText());
        fake.setSelection(0, 0, 0);
        fake.setSelection(1, 0, 4);
        assertEquals("row3", fake.getSelectionText());

        // 多行选择按行序拼接（跨行 '\n'，与 fable-render selection_text 一致）。
        fake.setSelection(0, 0, 4);
        assertEquals("row2\nrow3", fake.getSelectionText());

        // 滚动：向上钳制到 transcript 顶（视口行内容随滚动变化）。
        fake.scroll(-100);
        assertEquals(-2, fake.getTopRow());
        fake.setSelection(1, 0, 0);
        fake.setSelection(2, 0, 0);
        fake.setSelection(0, 0, 5);
        assertEquals("hello", fake.getSelectionText());
        fake.setSelection(0, 0, 0);
        fake.setSelection(1, 0, 5);
        assertEquals("world", fake.getSelectionText());

        // 向下滚动回到最新。
        fake.scroll(100);
        assertEquals(0, fake.getTopRow());
        fake.setSelection(1, 0, 0);
        fake.setSelection(0, 0, 4);
        assertEquals("row2", fake.getSelectionText());
    }

    /**
     * fake 核心实现：模拟 libghostty-vt 的 title/bell/mode 解析与
     * 行缓冲/视口滚动，覆盖 CoreAdapter 缝在 JVM 内的行为契约
     * （真实核心行为由 Rust 单测与离屏自检覆盖）。
     */
    private static final class FakeCoreAdapter implements CoreAdapter {

        private final int mRows;
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

        FakeCoreAdapter(int columns, int rows) {
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
                    if (body.equals("?25")) mCursorVisible = set;
                    if (body.equals("?12")) mCursorBlink = set;
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
        public void resize(int columns, int rows) {
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
                // external row 0 = 屏幕顶行；transcript 为负（与 TerminalBuffer 一致）。
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
    }
}
