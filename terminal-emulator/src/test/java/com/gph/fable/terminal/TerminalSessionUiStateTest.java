package com.gph.fable.terminal;

import android.view.Surface;

import com.gph.fable.terminal.adapter.CoreAdapter;
import com.gph.fable.terminal.session.FableSession;
import com.gph.fable.terminal.session.FableSessionCallbacks;
import com.gph.fable.terminal.session.FableSessionFactory;
import com.gph.fable.terminal.session.FableSessionSpec;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 工单 30/31：TerminalSession 的 UI 状态接线契约（JVM）。
 *
 * CoreAdapter 已接下：
 * - getTitle() 委托核心缝；
 * - title/bell 事件经 pollUiEvents() 消费核心标记投递；
 * - paste() 的 bracketed paste 模式以核心缝状态为准。
 * 旧模拟器路径已随工单 31 删除。
 */
public class TerminalSessionUiStateTest {

    private static final int CELL_W = 13;
    private static final int CELL_H = 15;

    /** 记录 title/bell/colors 回调次数 + 会话后端写出字节。 */
    private static class RecordingClient implements TerminalSessionClient {
        int titleChangedCount;
        int bellCount;
        int colorsChangedCount;

        @Override
        public void onTextChanged(TerminalSession changedSession) {
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
            titleChangedCount++;
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
            bellCount++;
        }

        @Override
        public void onColorsChanged(TerminalSession changedSession) {
            colorsChangedCount++;
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
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

    /** 模拟 libghostty-vt 的 title/bell/bracketed-paste 解析（写即置位）。 */
    private static final class UiStateCoreAdapter implements CoreAdapter {
        private String mTitle = "";
        private boolean mTitleChanged;
        private boolean mBell;
        private boolean mBracketedPaste;

        @Override
        public void write(byte[] data, int len) {
            feed(new String(data, 0, len, StandardCharsets.UTF_8));
        }

        private void feed(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\u001b' && i + 1 < s.length() && s.charAt(i + 1) == ']') {
                    int term = s.indexOf('\u0007', i);
                    if (term < 0) return;
                    String osc = s.substring(i + 2, term);
                    int semi = osc.indexOf(';');
                    if (semi > 0) {
                        String cmd = osc.substring(0, semi);
                        if (cmd.equals("0") || cmd.equals("2")) {
                            mTitle = osc.substring(semi + 1);
                            mTitleChanged = true;
                        }
                    }
                    i = term;
                } else if (c == '\u0007') {
                    mBell = true;
                } else if (c == '\u001b' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                    int t = s.indexOf('h', i);
                    int l = s.indexOf('l', i);
                    boolean set;
                    int end;
                    if (t == -1 && l == -1) return;
                    if (t == -1) {
                        end = l;
                        set = false;
                    } else if (l == -1 || t < l) {
                        end = t;
                        set = true;
                    } else {
                        end = l;
                        set = false;
                    }
                    String body = s.substring(i + 2, end);
                    if (body.equals("?2004")) mBracketedPaste = set;
                    i = end;
                }
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
        public boolean getModeBracketedPaste() {
            return mBracketedPaste;
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
    }

    /** 会话后端：记录写出的字节。 */
    private static final class RecordingSession implements FableSession {
        final List<byte[]> writes = new ArrayList<>();

        @Override
        public void write(byte[] data, int offset, int len) {
            writes.add(Arrays.copyOfRange(data, offset, offset + len));
        }

        @Override
        public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public int getExitStatus() {
            return 0;
        }

        @Override
        public int getPid() {
            return 1;
        }

        @Override
        public String getCwd() {
            return "/";
        }

        @Override
        public String getEngineName() {
            return "fake";
        }
    }

    private static final class RecordingFactory implements FableSessionFactory {
        final RecordingSession session = new RecordingSession();

        @Override
        public FableSession create(FableSessionSpec spec, FableSessionCallbacks callbacks) {
            return session;
        }

        @Override
        public String getEngineName() {
            return "fake";
        }
    }

    private TerminalSession newSession(RecordingClient client, RecordingFactory factory) {
        return new TerminalSession("/bin/sh", "/", null, null, 1000, client, factory);
    }

    @Test
    public void getTitleDelegatesToCoreAdapter() {
        RecordingClient client = new RecordingClient();
        TerminalSession session = newSession(client, new RecordingFactory());
        UiStateCoreAdapter adapter = new UiStateCoreAdapter();
        adapter.write("\u001b]0;core-title\u0007".getBytes(StandardCharsets.UTF_8),
                "\u001b]0;core-title\u0007".length());
        assertNull("无缝时标题为空", session.getTitle());

        session.setCoreAdapter(adapter);
        assertEquals("标题应委托核心缝", "core-title", session.getTitle());
    }

    @Test
    public void titleBellDeliveredViaPollWhenCoreAdapterActive() {
        RecordingClient client = new RecordingClient();
        TerminalSession session = newSession(client, new RecordingFactory());
        UiStateCoreAdapter adapter = new UiStateCoreAdapter();
        session.setCoreAdapter(adapter);

        // 核心侧解析出 OSC 0 标题 + BEL（模拟渲染线程已处理写命令）。
        adapter.write("\u001b]0;poll-title\u0007".getBytes(StandardCharsets.UTF_8),
                "\u001b]0;poll-title\u0007".length());
        adapter.write(new byte[] { 0x07 }, 1);

        // UI 轮询消费核心标记：事件投递、标记清除。
        session.pollUiEvents();
        assertEquals(1, client.titleChangedCount);
        assertEquals(1, client.bellCount);

        session.pollUiEvents();
        assertEquals("消费后不再重复投递", 1, client.titleChangedCount);
        assertEquals(1, client.bellCount);
    }

    @Test
    public void pasteUsesBracketedModeFromCoreAdapter() {
        RecordingClient client = new RecordingClient();
        RecordingFactory factory = new RecordingFactory();
        TerminalSession session = newSession(client, factory);
        session.updateSize(20, 5, CELL_W, CELL_H);
        UiStateCoreAdapter adapter = new UiStateCoreAdapter();
        session.setCoreAdapter(adapter);

        // 未开启 bracketed paste：仅清洗文本（换行 → CR）。
        session.paste("line1\nline2");
        assertEquals(1, factory.session.writes.size());
        assertEquals("line1\rline2",
                new String(factory.session.writes.get(0), StandardCharsets.UTF_8));

        // 开启 bracketed paste：包裹 ESC[200~/201~。
        byte[] bracketedOn = "\u001b[?2004h".getBytes(StandardCharsets.UTF_8);
        adapter.write(bracketedOn, bracketedOn.length);
        factory.session.writes.clear();
        session.paste("x");
        assertEquals(3, factory.session.writes.size());
        assertEquals("\u001b[200~",
                new String(factory.session.writes.get(0), StandardCharsets.UTF_8));
        assertEquals("x", new String(factory.session.writes.get(1), StandardCharsets.UTF_8));
        assertEquals("\u001b[201~",
                new String(factory.session.writes.get(2), StandardCharsets.UTF_8));
    }

}
