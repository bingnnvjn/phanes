package com.gph.fable.app;

import com.gph.fable.app.terminal.adapter.FableRenderCoreAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 缝 1（instrumentation）：新路径（fable-render）的 CoreAdapter 缝行为断言。
 *
 * JVM 单测无法加载 libfable-render.so（aarch64 Android ELF），因此
 * 字节→状态 / resize 重排 / 滚动 / 选择文本这一组缝行为在真机/模拟器上
 * 以本测试覆盖；同一组契约的 JVM 断言见 terminal-emulator CoreAdapterTest。
 */
@RunWith(AndroidJUnit4.class)
public class FableRenderCoreAdapterInstrumentedTest {

    @Test
    public void byteToStateResizeScrollSelection() {
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(80, 24);
        assertTrue(adapter.isValid());
        try {
            byte[] data = "hello\r\nworld\r\n".getBytes(StandardCharsets.UTF_8);
            adapter.write(data, data.length);

            adapter.setSelection(0, 0, 5);
            assertEquals("hello", adapter.getSelectionText());

            adapter.setSelection(0, 0, 0);
            adapter.setSelection(1, 0, 5);
            assertEquals("world", adapter.getSelectionText());

            adapter.setSelection(0, 0, 5);
            assertEquals("hello\nworld", adapter.getSelectionText());

            adapter.resize(40, 10);
            adapter.scroll(-10);
            adapter.reset();

            // 重建后 overlay 已清空。
            adapter.setSelection(0, 0, 5);
            assertEquals("", adapter.getSelectionText());
        } finally {
            adapter.destroy();
        }
    }

    @Test
    public void fontSizeAndPalette() {
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(40, 10);
        assertTrue(adapter.isValid());
        try {
            int[] cell = new int[2];
            adapter.getCellSize(cell);
            assertTrue(cell[0] > 0);
            assertTrue(cell[1] > 0);

            adapter.setFontSize(16f);
            adapter.getCellSize(cell);
            assertTrue(cell[0] > 0);
            assertTrue(cell[1] > 0);

            adapter.setPalette(0xFFFFFFFF, 0xFF000000, 0xFF0000FF, 0xFF00FF00);
            adapter.resetPalette();
        } finally {
            adapter.destroy();
        }
    }

    @Test
    public void scrollbackSelectionUsesViewportRelativeRows() {
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(40, 10);
        assertTrue(adapter.isValid());
        try {
            for (int i = 0; i < 30; i++) {
                byte[] line = ("row" + i).getBytes(StandardCharsets.UTF_8);
                adapter.write(line, line.length);
                adapter.write(new byte[] { '\r', '\n' }, 2);
            }

            // 回到滚动缓冲顶：0 = 视口顶行（最早可见行）。
            adapter.scroll(-10000);
            adapter.setSelection(1, 0, 4);
            adapter.setSelection(2, 0, 4);
            assertEquals("row1\nrow2", adapter.getSelectionText());

            adapter.setSelection(1, 0, 0);
            adapter.setSelection(0, 0, 4);
            assertEquals("row0", adapter.getSelectionText());
        } finally {
            adapter.destroy();
        }
    }
}
