package com.gph.fable.terminal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.gph.fable.terminal.adapter.CoreAdapter;
import com.gph.fable.terminal.session.FableSession;
import com.gph.fable.terminal.session.FableSessionCallbacks;
import com.gph.fable.terminal.session.FableSessionFactory;
import com.gph.fable.terminal.session.FableSessionSpec;
import com.gph.fable.terminal.session.JavaFableSessionFactory;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * 工单 26：PTY/进程/生命周期经会话层抽象缝（{@link FableSession}）委托给
 * Java（现状）或 Rust（libfable-session）实现；本类只负责字节与
 * emulator / CoreAdapter 的接线，不再直接持有 PTY fd 与读写线程。
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /**
     * CoreAdapter 缝（ADR-0003）：主终端 UI/会话接线只依赖该接口。
     * 为 null 时保持旧路径（只喂 TerminalEmulator）。
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

    /** 会话层抽象缝（工单 26）：Java/Rust 后端句柄；null = 尚未初始化。 */
    private FableSession mFableSession;

    /** 会话层工厂：默认 Java 实现；切换开关由 TermuxService 注入实际工厂。 */
    private final FableSessionFactory mSessionFactory;

    private static final String LOG_TAG = "TerminalSession";

    /** 会话层回调：字节 → emulator + CoreAdapter；退出 → 清理 + 通知；事件 → 诊断。 */
    private final FableSessionCallbacks mSessionCallbacks = new FableSessionCallbacks() {
        @Override
        public void onOutput(byte[] data, int len) {
            if (mEmulator == null) return;
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
    private final Integer mTranscriptRows;
    /** Buffer to write translate code points into utf8 before writing to the session. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this(shellPath, cwd, args, env, transcriptRows, client, null);
    }

    /**
     * 工单 26：可注入会话层工厂（Java/Rust 切换点）。
     * null = 默认 Java 实现——此为缝的防呆回退（terminal-emulator 无 app 依赖，
     * 无法引用 Rust 工厂）；生产路径由 TermuxService 按 FableSessionSwitch
     * （设置页开关，构建期默认 Rust）解析后显式注入。
     */
    public TerminalSession(String shellPath, String cwd, String[] args, String[] env,
                           Integer transcriptRows, TerminalSessionClient client,
                           FableSessionFactory sessionFactory) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mSessionFactory = sessionFactory == null ? JavaFableSessionFactory.INSTANCE : sessionFactory;
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

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /**
     * 设置本会话的核心缝实现（fable-render 或旧路径适配器）。
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

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            if (mFableSession != null) {
                mFableSession.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            }
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        if (mCoreAdapter != null) mCoreAdapter.resize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     * 会话后端（PTY/进程）由会话层工厂创建（工单 26 缝）。
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

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
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mFableSession != null) mFableSession.write(data, offset, count);
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

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
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

    /** 字节统一交付点（主线程）：核心未接 → 环形缓冲；否则双写 emulator + 核心缝。 */
    private void emitBytes(byte[] data, int len) {
        if (mCoreAdapter == null) appendByteHistory(data, len);
        mEmulator.append(data, len);
        if (mCoreAdapter != null) mCoreAdapter.write(data, len);
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mFableSession != null && mFableSession.isRunning();
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mFableSession == null ? 0 : mFableSession.getExitStatus();
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
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
