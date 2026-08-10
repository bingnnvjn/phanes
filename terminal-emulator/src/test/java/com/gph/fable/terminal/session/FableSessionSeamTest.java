package com.gph.fable.terminal.session;

import android.view.Surface;

import com.gph.fable.terminal.TerminalSession;
import com.gph.fable.terminal.TerminalSessionClient;
import com.gph.fable.terminal.adapter.CoreAdapter;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 会话层抽象缝（工单 26）JVM 契约测试：
 *
 * TerminalSession 只依赖 {@link FableSessionFactory} / {@link FableSession} 接口，
 * 不直接依赖具体 PTY 实现；Java 与 Rust 两实现平级。JVM 内无法加载
 * libfable-session.so / Termux 原生 JNI，因此后端用 FakeSession 驱动断言
 * TerminalSession 的接线行为；真实 Java/Rust 后端由构建产物 + 真机验收覆盖。
 */
public class FableSessionSeamTest {

    private static final int CELL_W = 13;
    private static final int CELL_H = 15;

    /** 记录核心缝调用（代替 fable-render；JVM 内无法加载 .so）。 */
    private static class RecordingCoreAdapter implements CoreAdapter {
        final ByteArrayOutputStream writes = new ByteArrayOutputStream();
        final List<int[]> resizes = new ArrayList<>();
        boolean destroyed;

        @Override
        public void write(byte[] data, int len) {
            writes.write(data, 0, len);
        }

        @Override
        public void resize(int columns, int rows) {
            resizes.add(new int[] { columns, rows });
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
            if (out.length >= 2) {
                out[0] = CELL_W;
                out[1] = CELL_H;
            }
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
        public void attach(Surface surface, int widthPx, int heightPx) {
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
            destroyed = true;
        }

        @Override
        public boolean supportsSelectionText() {
            return true;
        }

        @Override
        public boolean supportsFontSize() {
            return true;
        }

        @Override
        public boolean supportsPalette() {
            return true;
        }

        @Override
        public boolean supportsScrollback() {
            return true;
        }
    }

    /** 记录会话客户端回调。 */
    private static class RecordingClient implements TerminalSessionClient {
        TerminalSession finishedSession;
        int shellPid;

        @Override
        public void onTextChanged(TerminalSession changedSession) {
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            this.finishedSession = finishedSession;
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
            shellPid = pid;
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
        }

        @Override
        public void logWarn(String tag, String message) {
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
        }
    }

    /** 测试用会话后端：可在测试线程直接投递输出/退出回调。 */
    private static class FakeSession implements FableSession {
        final FableSessionCallbacks callbacks;
        final FableSessionSpec spec;
        final List<byte[]> writes = new ArrayList<>();
        final List<int[]> resizes = new ArrayList<>();
        boolean closed;
        boolean running = true;
        int exitStatus;

        FakeSession(FableSessionSpec spec, FableSessionCallbacks callbacks) {
            this.spec = spec;
            this.callbacks = callbacks;
        }

        void emitOutput(String s) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            callbacks.onOutput(data, data.length);
        }

        void emitExit(int code) {
            running = false;
            exitStatus = code;
            callbacks.onExit(code);
        }

        @Override
        public void write(byte[] data, int offset, int len) {
            writes.add(Arrays.copyOfRange(data, offset, offset + len));
        }

        @Override
        public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
            resizes.add(new int[] { columns, rows, cellWidthPixels, cellHeightPixels });
        }

        @Override
        public void close() {
            closed = true;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getExitStatus() {
            return exitStatus;
        }

        @Override
        public int getPid() {
            return 4242;
        }

        @Override
        public String getCwd() {
            return spec.getCwd();
        }

        @Override
        public String getEngineName() {
            return "fake";
        }
    }

    /** 测试用工厂：记录最后一次 spec 与创建的会话。 */
    private static class FakeFactory implements FableSessionFactory {
        FableSessionSpec lastSpec;
        FakeSession lastSession;

        @Override
        public FableSession create(FableSessionSpec spec, FableSessionCallbacks callbacks) {
            lastSpec = spec;
            lastSession = new FakeSession(spec, callbacks);
            return lastSession;
        }

        @Override
        public String getEngineName() {
            return "fake";
        }
    }

    private static final String SHELL = "/data/data/com.gph.fable/files/usr/bin/bash";
    private static final String CWD = "/data/data/com.gph.fable/files/home";

    private RecordingClient mClient;
    private RecordingCoreAdapter mCore;
    private FakeFactory mFactory;
    private TerminalSession mSession;

    private TerminalSession newSession(FakeFactory factory) {
        mClient = new RecordingClient();
        mCore = new RecordingCoreAdapter();
        TerminalSession session = new TerminalSession(SHELL, CWD,
            new String[] { "-bash" }, new String[] { "HOME=" + CWD }, 100, mClient, factory);
        session.setCoreAdapter(mCore);
        return session;
    }

    @Test
    public void outputRoutesToEmulatorAndCoreAdapter() {
        mSession = newSession(mFactory = new FakeFactory());
        mSession.updateSize(10, 5, CELL_W, CELL_H);

        mFactory.lastSession.emitOutput("hello\r\nworld\r\n");

        // 字节同时到达旧路径 emulator 与新路径核心缝。
        assertEquals("hello", emulatorSelection(0, 0, 5, 0));
        assertEquals("world", emulatorSelection(0, 1, 5, 1));
        assertEquals("hello\r\nworld\r\n", mCore.writes.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void inputWritesToBackendSession() {
        mSession = newSession(mFactory = new FakeFactory());
        mSession.updateSize(10, 5, CELL_W, CELL_H);

        byte[] input = "ls -la\r".getBytes(StandardCharsets.UTF_8);
        mSession.write(input, 0, input.length);

        assertEquals(1, mFactory.lastSession.writes.size());
        assertArrayEquals(input, mFactory.lastSession.writes.get(0));
    }

    @Test
    public void resizePropagatesToBackendAndCore() {
        mSession = newSession(mFactory = new FakeFactory());
        mSession.updateSize(10, 5, CELL_W, CELL_H);

        mSession.updateSize(20, 8, 13, 15);

        assertArrayEquals(new int[] { 20, 8, 13, 15 }, mFactory.lastSession.resizes.get(0));
        // 首次 updateSize 初始化时也下发一次核心 resize，第二次才是显式缩放。
        assertEquals(2, mCore.resizes.size());
        assertArrayEquals(new int[] { 20, 8 }, mCore.resizes.get(1));
    }

    @Test
    public void exitNotifiesClientWritesCompletionAndDestroysCore() {
        mSession = newSession(mFactory = new FakeFactory());
        mSession.updateSize(10, 5, CELL_W, CELL_H);

        mFactory.lastSession.emitExit(3);

        assertEquals(mSession, mClient.finishedSession);
        assertTrue(mCore.destroyed);
        assertFalse(mSession.isRunning());
        assertEquals(3, mSession.getExitStatus());
        // 退出描述只进旧路径 emulator（核心已销毁，与工单 15 行为一致）。
        assertTrue(emulatorSelection(0, 0, 10, 4).contains("[Process completed (code 3)"));
        assertFalse(mCore.writes.toString(StandardCharsets.UTF_8).contains("Process completed"));
    }

    @Test
    public void factoryReceivesFullSpec() {
        mSession = newSession(mFactory = new FakeFactory());
        mSession.updateSize(10, 5, CELL_W, CELL_H);

        FableSessionSpec spec = mFactory.lastSpec;
        assertEquals(SHELL, spec.getShell());
        assertEquals(CWD, spec.getCwd());
        assertArrayEquals(new String[] { "-bash" }, spec.getArgs());
        assertArrayEquals(new String[] { "HOME=" + CWD }, spec.getEnv());
        assertEquals(10, spec.getColumns());
        assertEquals(5, spec.getRows());
        assertEquals(CELL_W, spec.getCellWidthPixels());
        assertEquals(CELL_H, spec.getCellHeightPixels());
    }

    @Test
    public void defaultFactoryIsJavaWhenNoneProvided() {
        mSession = new TerminalSession(SHELL, CWD,
            new String[] { "-bash" }, new String[] { "HOME=" + CWD }, 100, new RecordingClient(), null);
        assertEquals("java", mSession.getSessionFactory().getEngineName());
        assertEquals("java", JavaFableSessionFactory.INSTANCE.getEngineName());
    }

    @Test
    public void sessionCreatedBeforeBackendSpawnedHasNoPid() {
        mSession = newSession(mFactory = new FakeFactory());
        assertFalse(mSession.isRunning());
        assertNull(mSession.getCwd());
    }

    private String emulatorSelection(int startCol, int startRow, int endCol, int endRow) {
        return mSession.getEmulator().getScreen().getSelectedText(startCol, startRow, endCol, endRow);
    }
}
