package com.gph.fable.app.terminal.adapter;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.gph.fable.app.RenderCore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 缝 1（instrumentation）：新路径（fable-render）的 CoreAdapter 缝行为断言。
 *
 * JVM 单测无法加载 libfable-render.so（aarch64 Android ELF），因此
 * 字节→状态 / resize 重排 / 滚动 / 选择文本这一组缝行为在真机/模拟器上
 * 以本测试覆盖；同一组契约的 JVM 断言见 core CoreAdapterTest。
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

            // overlay 按行独立存续；清除上一段的 row2，避免残留选择参与本次断言。
            adapter.setSelection(2, 0, 0);
            adapter.setSelection(1, 0, 0);
            adapter.setSelection(0, 0, 4);
            assertEquals("row0", adapter.getSelectionText());
        } finally {
            adapter.destroy();
        }
    }

    @Test
    public void titleBellAndModes() {
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(80, 24);
        assertTrue(adapter.isValid());
        try {
            byte[] title = "\u001b]0;fable-title\u0007".getBytes(StandardCharsets.UTF_8);
            adapter.write(title, title.length);
            assertEquals("fable-title", adapter.getTitle());
            assertTrue("标题变更应可消费", adapter.consumeTitleChanged());
            assertFalse("消费后标记应清除", adapter.consumeTitleChanged());

            adapter.write(new byte[] { 0x07 }, 1);
            assertTrue("bell 应可消费", adapter.consumeBell());
            assertFalse("消费后 bell 应清除", adapter.consumeBell());

            byte[] on = "\u001b[?1049h\u001b[?1000h\u001b[?25l\u001b[?12h"
                    .getBytes(StandardCharsets.UTF_8);
            adapter.write(on, on.length);
            assertTrue(adapter.getModeAlternateScreen());
            assertTrue(adapter.getModeMouseTracking());
            assertFalse(adapter.getModeCursorVisible());
            assertTrue(adapter.getModeCursorBlink());

            byte[] off = "\u001b[?1049l\u001b[?1000l\u001b[?25h\u001b[?12l"
                    .getBytes(StandardCharsets.UTF_8);
            adapter.write(off, off.length);
            assertFalse(adapter.getModeAlternateScreen());
            assertFalse(adapter.getModeMouseTracking());
            assertTrue(adapter.getModeCursorVisible());
            assertFalse(adapter.getModeCursorBlink());
        } finally {
            adapter.destroy();
        }
    }

    /**
     * 工单 43：JNI handle 是 Rust 注册表 token，而不是裸地址。失效 token、重复 destroy
     * 与销毁后的写/查/渲染必须全部安全返回。
     */
    @Test
    public void invalidAndRepeatedNativeHandlesAreHarmless() {
        long invalid = Long.MAX_VALUE;
        RenderCore.rendererDestroy(invalid);
        RenderCore.rendererDestroy(invalid);
        RenderCore.rendererWrite(invalid, new byte[] { 'x' }, 1);
        RenderCore.rendererResetPalette(invalid);
        RenderCore.rendererDetach(invalid);
        assertEquals("", RenderCore.rendererSelectionText(invalid));
        assertEquals("", RenderCore.rendererGetTitle(invalid));
        assertEquals(0, RenderCore.rendererGetScrollbackRows(invalid));
        assertFalse(RenderCore.rendererRender(invalid, 1, 1));
        assertFalse(RenderCore.rendererForceRender(invalid, 1, 1));
        assertNull(RenderCore.rendererGetWordBoundsAt(invalid, 0, 0));
    }

    @Test
    public void createFailureAndCleanupAreHarmless() {
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(80, 24, (cols, rows) -> 0);
        assertFalse(adapter.isValid());
        adapter.destroy();
        adapter.destroy();
        adapter.write(new byte[] { 'x' }, 1);
        adapter.reset();
        assertFalse(adapter.isValid());
    }

    @Test
    public void sessionAdapterSurvivesActivityLikeContextRecreation() throws Exception {
        Context application = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getTargetContext().getApplicationContext();
        Context oldActivityLikeContext = new ContextWrapper(application) {
            @Override
            public Context getApplicationContext() {
                return application;
            }
        };
        Context newActivityLikeContext = new ContextWrapper(application) {
            @Override
            public Context getApplicationContext() {
                return application;
            }
        };
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(oldActivityLikeContext, 80, 24);
        try {
            Field field = FableRenderCoreAdapter.class.getDeclaredField("mApplicationContext");
            field.setAccessible(true);
            assertEquals(application, field.get(adapter));
            assertFalse("会话适配器不得保留旧 Activity Context",
                    oldActivityLikeContext == field.get(adapter));
            assertFalse("会话适配器不得保留重建后的 Activity Context",
                    newActivityLikeContext == field.get(adapter));
            // Session 保留 adapter、Activity 替换后 reset 仍只依赖 application Context。
            adapter.reset();
            assertTrue(adapter.isValid());
        } finally {
            adapter.destroy();
        }
    }

    /**
     * 工单 43：write/query/reset/destroy 可从不同线程交错。测试同时覆盖 reset
     * 期间 destroy 不会发布新句柄（不“复活”已销毁的会话）。
     */
    @Test
    public void concurrentCallsResetAndDestroyCompleteWithoutResurrection() throws Exception {
        FableRenderCoreAdapter adapter = new FableRenderCoreAdapter(80, 24);
        assertTrue(adapter.isValid());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Surface surface = new Surface(new SurfaceTexture(0));

        try {
            Runnable writer = () -> runStress(start, done, failure, () -> {
                byte[] bytes = "load\r\n".getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 200; i++) {
                    adapter.write(bytes, bytes.length);
                    adapter.resize(80 + (i % 3), 24);
                    adapter.scroll(i % 2);
                }
            });
            Runnable query = () -> runStress(start, done, failure, () -> {
                int[] cell = new int[2];
                int[] cursor = new int[2];
                for (int i = 0; i < 200; i++) {
                    adapter.getCellSize(cell);
                    adapter.getCursorPosition(cursor);
                    adapter.getTitle();
                    adapter.getSelectionText();
                    adapter.getTranscriptText(true, true);
                }
            });
            Runnable resetDestroy = () -> runStress(start, done, failure, () -> {
                for (int i = 0; i < 8; i++) {
                    adapter.reset();
                }
                adapter.destroy();
                adapter.destroy();
            });
            Runnable attach = () -> runStress(start, done, failure, () -> {
                for (int i = 0; i < 100; i++) {
                    adapter.attach(surface, 1, 1);
                    adapter.detach();
                }
            });

            new Thread(writer, "renderer-stress-writer").start();
            new Thread(query, "renderer-stress-query").start();
            new Thread(resetDestroy, "renderer-stress-reset-destroy").start();
            new Thread(attach, "renderer-stress-attach").start();
            start.countDown();

            assertTrue("并发 JNI 调用不应永久阻塞", done.await(20, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertFalse("destroy 后 adapter 不得重新发布新句柄", adapter.isValid());

            // destroy 后继续调用仅是无操作，验证会话/Activity 回收后的迟到回调安全。
            adapter.write(new byte[] { 'x' }, 1);
            adapter.reset();
            adapter.detach();
            adapter.render(1, 1);
            assertFalse(adapter.isValid());
        } finally {
            surface.release();
        }
    }

    private static void runStress(CountDownLatch start, CountDownLatch done,
                                  AtomicReference<Throwable> failure, Runnable action) {
        try {
            assertTrue("stress start", start.await(5, TimeUnit.SECONDS));
            action.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            done.countDown();
        }
    }
}
