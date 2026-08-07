package com.gph.fable.terminal.adapter;

import com.gph.fable.terminal.TerminalEmulator;
import com.gph.fable.terminal.TerminalOutput;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

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
}
