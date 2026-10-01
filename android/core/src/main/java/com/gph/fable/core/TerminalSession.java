package com.gph.fable.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.gph.fable.core.adapter.CoreAdapter;
import com.gph.fable.core.session.FableSession;
import com.gph.fable.core.session.FableSessionCallbacks;
import com.gph.fable.core.session.FableSessionFactory;
import com.gph.fable.core.session.FableSessionSpec;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * 工单 26：PTY/进程/生命周期经会话层抽象缝（{@link FableSession}）委托给
 * Rust（libfable-session）实现（Java 会话层已随工单 27 下线删除）。
 * 工单 31：旧 TerminalEmulator 已删除；本类只负责字节与 CoreAdapter 的接线，
 * 不再创建模拟器，也不再持有任何旧模拟器引用。
 * <p>
 * NOTE: The terminal session may outlive the view, so be careful with callbacks!
 */
public final class TerminalSession {

    public final String mHandle = UUID.randomUUID().toString();

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /**
     * CoreAdapter 缝（ADR-0003）：主终端 UI/会话接线只依赖该接口。
     * 为 null 时（会话尚未绑定渲染器）输出进入环形缓冲，绑定后回放。
     */
    private CoreAdapter mCoreAdapter;

    /**
     * 缝接入前的 PTY 输出字节环形缓冲（上限 {@link #BYTE_HISTORY_CAPACITY}）：
     * 会话进程在 CoreAdapter 建立之前就可能已输出（冷启动 prompt、Activity 重建后
     * 重挂已有会话），这些字节需在 setCoreAdapter 时回放，否则核心视图在收到新输出
     * 前为空白（"打开即终端"不满足）。只在 mCoreAdapter == null 时收集；
     * setCoreAdapter 回放后清空。
     */
    private static final int BYTE_HISTORY_CAPACITY = 1024 * 1024;
    private byte[] mByteHistory = new byte[0];
    private int mByteHistoryStart;
    private int mByteHistorySize;

    /** 会话层抽象缝（工单 26）：Rust 后端句柄；null = 尚未初始化。 */
    private FableSession mFableSession;

    /** 会话层工厂（工单 26 缝）：由 FableService 注入 Rust 实现（唯一实现）。 */
    private final FableSessionFactory mSessionFactory;

    /** 工单 31：最近一次 updateSize 的行列（鼠标事件编码钳制用）。 */
    private int mColumns;
    private int mRows;

    private static final String LOG_TAG = "TerminalSession";

    /** 会话层回调：字节 → CoreAdapter；退出 → 清理 + 通知；事件 → 诊断。 */
    private final FableSessionCallbacks mSessionCallbacks = new FableSessionCallbacks() {
        @Override
        public void onOutput(byte[] data, int len) {
            emitBytes(data, len);
        }

        @Override
        public void onExit(int exitCode) {
            handleSessionExit(exitCode);
        }

        @Override
        public void onDiagnostics(String event, long sessionId, long timestampMs,
                                  byte[] data, int exitCode, String message, String[] extra) {
            // ADR-0008 决策 7：Kotlin 第一版只接诊断/日志。
            Logger.logVerbose(mClient, LOG_TAG, "FableSessionEvent event=" + event
                + " session=" + sessionId + " ts=" + timestampMs
                + (message != null ? " msg=" + message : ""));
        }

    };

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    /** Buffer to write translate code points into utf8 before writing to the session. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /**
     * 工单 26：可注入会话层工厂（Java/Rust 切换点）。
     * Java 会话层已下线（工单 27）：RustFableSessionFactory 是唯一实现，
     * 由 FableService 显式注入（core 模块无 app 依赖）。
     */
    public TerminalSession(String shellPath, String cwd, String[] args, String[] env,
                           TerminalSessionClient client,
                           FableSessionFactory sessionFactory) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mClient = client;
        this.mSessionFactory = sessionFactory;
    }

    public FableSessionFactory getSessionFactory() {
        return mSessionFactory;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
    }

    /**
     * 设置本会话的核心缝实现（fable-render）。
     * 字节交付点（会话层回调 onOutput）与 resize 都会经缝转发；
     * PTY/进程/环境/生命周期逻辑在会话层实现内。
     */
    public void setCoreAdapter(CoreAdapter coreAdapter) {
        mCoreAdapter = coreAdapter;
        if (coreAdapter != null && mByteHistorySize > 0) {
            byte[] replay = drainByteHistory();
            coreAdapter.write(replay, replay.length);
        } else if (coreAdapter == null) {
            // 会话带核心运行时解除缝（进程退出清理），丢弃未回放缓冲。
            mByteHistoryStart = 0;
            mByteHistorySize = 0;
        }
    }

    public CoreAdapter getCoreAdapter() {
        return mCoreAdapter;
    }

    /** Inform the attached pty of the new size and initialize/resize the session backend. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mColumns = columns;
        mRows = rows;
        if (mFableSession == null) {
            initializeSession(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mFableSession.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        if (mCoreAdapter != null) mCoreAdapter.resize(columns, rows);
    }

    /**
     * The terminal title as set through escape sequences or null if none set.
     * 工单 30/31：标题以核心缝为准（libghostty-vt 解析 OSC 0/2）。
     */
    public String getTitle() {
        return (mCoreAdapter == null) ? null : mCoreAdapter.getTitle();
    }

    /**
     * 会话后端（PTY/进程）由会话层工厂创建（工单 26 缝）。
     * 旧 TerminalEmulator 已随工单 31 删除，本方法只建会话后端。
     */
    public void initializeSession(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        FableSessionSpec spec = new FableSessionSpec(mShellPath, mCwd, mArgs, mEnv,
            columns, rows, cellWidthPixels, cellHeightPixels);
        mFableSession = mSessionFactory.create(spec, mSessionCallbacks);
        if (mFableSession == null) {
            // 后端创建失败（如 Rust sessionCreate 返回 0）：写错误提示并结束会话。
            Logger.logError(mClient, LOG_TAG, "Failed to create session backend for shell=" + mShellPath);
            byte[] fail = "\r\n[Session creation failed]".getBytes(StandardCharsets.UTF_8);
            emitBytes(fail, fail.length);
            mClient.onSessionFinished(this);
            return;
        }
        mClient.setTerminalShellPid(this, mFableSession.getPid());
    }

    /** Write data to the shell process. */
    public void write(byte[] data, int offset, int count) {
        if (mFableSession != null) mFableSession.write(data, offset, count);
    }

    /** Write a String to the shell process using UTF-8 encoding. */
    public void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset terminal state via the core adapter. */
    public void reset() {
        if (mCoreAdapter != null) mCoreAdapter.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (mFableSession != null) mFableSession.close();
    }

    /** 进程退出（主线程投递）：清理核心缝、写退出描述、通知客户端。 */
    private void handleSessionExit(int exitCode) {
        // 会话进程已退出：核心渲染器不再需要，销毁并解除缝引用
        // （fable-render 的渲染线程 Quit+join；容器侧再销毁是幂等的）。
        if (mCoreAdapter != null) {
            mCoreAdapter.destroy();
            mCoreAdapter = null;
        }

        String exitDescription = "\r\n[Process completed";
        if (exitCode > 0) {
            // Non-zero process exit.
            exitDescription += " (code " + exitCode + ")";
        } else if (exitCode < 0) {
            // Negated signal.
            exitDescription += " (signal " + (-exitCode) + ")";
        }
        exitDescription += " - press Enter]";

        byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
        emitBytes(bytesToWrite, bytesToWrite.length);

        mClient.onSessionFinished(TerminalSession.this);
    }

    /** 字节统一交付点（主线程）：核心未接 → 环形缓冲；已接 → 只喂核心缝。 */
    private void emitBytes(byte[] data, int len) {
        if (mCoreAdapter == null) {
            appendByteHistory(data, len);
        } else {
            mCoreAdapter.write(data, len);
        }
        notifyScreenUpdate();
    }

    /**
     * 工单 30/31：轮询核心缝的 title/bell 消费标记并投递客户端回调（UI 定时调用）。
     * consume 语义 = 查询即清除，mailbox 保序保证不丢事件；无缝为空操作。
     */
    public void pollUiEvents() {
        if (mCoreAdapter == null) return;
        if (mCoreAdapter.consumeTitleChanged()) mClient.onTitleChanged(this);
        if (mCoreAdapter.consumeBell()) mClient.onBell(this);
    }

    // ---------- 工单 30/31：模式状态统一查询（全部走 CoreAdapter） ----------

    /** 任一 mouse tracking 模式（X10/1000/1002/1003）。 */
    public boolean isMouseTrackingActive() {
        return mCoreAdapter != null && mCoreAdapter.getModeMouseTracking();
    }

    /** alternate screen（DECSET 1047/1049）。 */
    public boolean isAlternateBufferActive() {
        return mCoreAdapter != null && mCoreAdapter.getModeAlternateScreen();
    }

    /** 光标可见（DECTCEM，DECSET 25）。 */
    public boolean isCursorEnabled() {
        return mCoreAdapter != null && mCoreAdapter.getModeCursorVisible();
    }

    /** 光标键 application mode（DECCKM，DECSET ?1）。 */
    public boolean isCursorKeysApplicationMode() {
        return mCoreAdapter != null && mCoreAdapter.getModeCursorKeysApplication();
    }

    /** 小键盘 application mode（DECKPAM，DECSET ?66）。 */
    public boolean isKeypadApplicationMode() {
        return mCoreAdapter != null && mCoreAdapter.getModeKeypadApplication();
    }

    public synchronized boolean isRunning() {
        return mFableSession != null && mFableSession.isRunning();
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mFableSession == null ? 0 : mFableSession.getExitStatus();
    }

    /** 长按选择菜单"复制"：把文本交给客户端（旧 TerminalOutput 回调，保留同名方法）。 */
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    /** 长按选择菜单"粘贴"：请求客户端粘贴（旧 TerminalOutput 回调，保留同名方法）。 */
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    /**
     * 工单 30/31：粘贴文本。bracketed paste 模式以 CoreAdapter 状态为准
     * （核心解析 DECSET 2004）；无缝时只写清洗文本（无 bracketed 包裹）。
     */
    public void paste(String text) {
        String sanitized = sanitizePasteText(text);
        boolean bracketed = mCoreAdapter != null && mCoreAdapter.getModeBracketedPaste();
        if (bracketed) write("\u001b[200~");
        write(sanitized);
        if (bracketed) write("\u001b[201~");
    }

    /**
     * 工单 31：发送鼠标事件到核心（X10/SGR 编码，协议模式以核心状态为准）。
     * 语义与旧 TerminalEmulator#sendMouseEvent 一致。
     */
    public void sendMouseEvent(int button, int column, int row, boolean pressed) {
        if (mCoreAdapter == null) return;
        byte[] encoded = encodeMouseEvent(button, column, row, pressed);
        if (encoded != null && encoded.length > 0) write(encoded, 0, encoded.length);
    }

    private byte[] encodeMouseEvent(int button, int column, int row, boolean pressed) {
        if (column < 1) column = 1;
        if (mColumns > 0 && column > mColumns) column = mColumns;
        if (row < 1) row = 1;
        if (mRows > 0 && row > mRows) row = mRows;

        if (button == CoreAdapter.MOUSE_LEFT_BUTTON_MOVED && !mCoreAdapter.getModeMouseButtonEvent()) {
            // 非 button-event 模式不发送拖动。
            return null;
        }
        if (mCoreAdapter.getModeMouseSgr()) {
            return String.format("\033[<%d;%d;%d" + (pressed ? 'M' : 'm'), button, column, row)
                .getBytes(StandardCharsets.UTF_8);
        }
        int encodedButton = pressed ? button : 3; // 3 = 全部释放。
        // Clip to 8-bit data limits。
        boolean outOfBounds = column > 255 - 32 || row > 255 - 32;
        if (outOfBounds) return null;
        return new byte[] {
            '\033', '[', 'M',
            (byte) (32 + encodedButton),
            (byte) (32 + column),
            (byte) (32 + row)
        };
    }

    /**
     * 粘贴文本清洗（旧 TerminalEmulator#sanitizePasteText 迁移）：
     * 先移除 ESC 与 C1 控制字符 [0x80,0x9F]，再把 \n / CRLF 归一为 \r。
     */
    static String sanitizePasteText(String text) {
        text = text.replaceAll("(\u001B|[\u0080-\u009F])", "");
        return text.replaceAll("\r?\n", "\r");
    }

    public int getPid() {
        return mFableSession == null ? 0 : mFableSession.getPid();
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        return mFableSession == null ? null : mFableSession.getCwd();
    }

    /** 追加 PTY 输出到环形缓冲（仅缝接入前收集；主线程调用）。 */
    private void appendByteHistory(byte[] data, int len) {
        if (mByteHistory.length == 0) mByteHistory = new byte[BYTE_HISTORY_CAPACITY];
        if (len <= 0) return;
        for (int i = 0; i < len; i++) {
            mByteHistory[mByteHistoryStart] = data[i];
            mByteHistoryStart = (mByteHistoryStart + 1) % mByteHistory.length;
            if (mByteHistorySize < mByteHistory.length) mByteHistorySize++;
        }
    }

    /** 取出并清空环形缓冲（保持字节顺序；主线程调用）。 */
    private byte[] drainByteHistory() {
        if (mByteHistorySize == 0) return new byte[0];
        byte[] out = new byte[mByteHistorySize];
        int start = (mByteHistoryStart - mByteHistorySize + mByteHistory.length) % mByteHistory.length;
        for (int i = 0; i < mByteHistorySize; i++) {
            out[i] = mByteHistory[(start + i) % mByteHistory.length];
        }
        mByteHistoryStart = 0;
        mByteHistorySize = 0;
        return out;
    }

}
