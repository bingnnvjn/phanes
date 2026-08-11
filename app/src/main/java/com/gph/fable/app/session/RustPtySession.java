package com.gph.fable.app.session;

import android.os.Handler;
import android.os.Looper;

import com.gph.fable.app.SessionEventCallback;
import com.gph.fable.app.SessionHandle;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.terminal.session.FableSession;
import com.gph.fable.terminal.session.FableSessionCallbacks;
import com.gph.fable.terminal.session.FableSessionSpec;

/**
 * 会话层抽象缝的 Rust 实现（工单 26）：包装 fable-v1/25 的
 * {@link SessionHandle}（libfable-session.so，portable-pty）。
 *
 * - 事件回调来自 Rust 每会话分发线程，先 marshal 到主线程再投递
 *   {@link FableSessionCallbacks}（与 Java 实现同主线程契约）；
 * - 事件流六事件第一版只做诊断/日志（ADR-0008 决策 7，2026-08-11 核实）：
 *   output_chunk → onOutput；exit_code → 记录退出码；session_closed → onExit；
 * - pid 未从 JNI 暴露（工单 25 边界定稿 8 符号，2026-08-11 核实），getPid() 返回 0；
 *   getCwd() 返回创建时 cwd（Java 实现读 /proc/<pid>/cwd 实时值，差异见工单 Comments）。
 * - args[0] 是 argv0 名（登录 shell "-bash"，与 Java createSubprocess 同语义），
 *   原样传给 sessionCreate；Rust 侧经 portable-pty argv0 补丁实现（2026-08-11）。
 */
public final class RustPtySession implements FableSession {

    private static final String LOG_TAG = "RustPtySession";

    /** libfable-session 事件名（工单 25 六事件 schema，2026-08-11 核实）。 */
    private static final String EVENT_OUTPUT_CHUNK = "output_chunk";
    private static final String EVENT_EXIT_CODE = "exit_code";
    private static final String EVENT_SESSION_CLOSED = "session_closed";

    private final FableSessionCallbacks mCallbacks;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final long mHandle;
    private final String mCwd;

    private volatile boolean mRunning = true;
    private volatile boolean mExited;
    private volatile boolean mClosed;
    /** 会话结束后是否已释放 Rust 侧句柄（sessionClose 幂等标记）。 */
    private volatile boolean mHandleReleased;
    private volatile int mExitStatus;

    RustPtySession(FableSessionSpec spec, FableSessionCallbacks callbacks) {
        mCallbacks = callbacks;
        mCwd = spec.getCwd();
        mHandle = SessionHandle.sessionCreate(spec.getShell(), spec.getArgs(), spec.getEnv(),
            spec.getCwd(), spec.getColumns(), spec.getRows(), mEventCallback);
        if (mHandle == 0) {
            mRunning = false;
            mExited = true;
            Logger.logError(LOG_TAG, "sessionCreate failed: " + SessionHandle.sessionLastError());
        }
    }

    boolean isValid() {
        return mHandle != 0;
    }

    private final SessionEventCallback mEventCallback = (sessionId, event, ts, data, exitCode, message, extra) ->
        mMainHandler.post(() -> dispatch(sessionId, event, ts, data, exitCode, message, extra));

    private void dispatch(long sessionId, String event, long ts,
                          byte[] data, int exitCode, String message, String[] extra) {
        switch (event) {
            case EVENT_OUTPUT_CHUNK:
                // 与 Java 语义一致：直到 session_closed 前的输出都投递
                // （kill 后残余输出也回显，如 logout）。
                if (data != null && data.length > 0) {
                    mCallbacks.onOutput(data, data.length);
                }
                break;
            case EVENT_EXIT_CODE:
                mExitStatus = exitCode;
                break;
            case EVENT_SESSION_CLOSED:
                if (!mExited) {
                    mExited = true;
                    mRunning = false;
                    mCallbacks.onExit(mExitStatus);
                }
                // 会话已结束：释放 Rust 侧会话记录（幂等；kill 流程的
                // sessionClose 已在 close() 先行，此处仅自然退出时执行）。
                releaseHandle();
                break;
            default:
                break;
        }
        mCallbacks.onDiagnostics(event, sessionId, ts, data, exitCode, message, extra);
    }

    @Override
    public void write(byte[] data, int offset, int len) {
        if (mHandle == 0 || mClosed || mExited) return;
        if (offset == 0 && len == data.length) {
            writeChecked(data, len);
        } else {
            byte[] slice = new byte[len];
            System.arraycopy(data, offset, slice, 0, len);
            writeChecked(slice, len);
        }
    }

    private void writeChecked(byte[] data, int len) {
        int written = SessionHandle.sessionWrite(mHandle, data, len);
        if (written < 0) {
            // 写失败不再静默（工单 26 审查项：错误零反馈）。
            Logger.logWarn(LOG_TAG, "sessionWrite failed: " + SessionHandle.sessionLastError());
        }
    }

    @Override
    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mHandle != 0 && !mClosed && !mExited) {
            SessionHandle.sessionResize(mHandle, columns, rows);
        }
    }

    @Override
    public void close() {
        if (mClosed) return;
        mClosed = true;
        if (mHandle != 0 && !mExited) {
            // sessionClose 杀死子进程并关闭会话；exit_code → session_closed
            // 事件仍会投递，onExit 在 session_closed 处触发（与 Java SIGKILL 语义对齐）。
            SessionHandle.sessionClose(mHandle);
            mHandleReleased = true;
        }
    }

    /** 释放 Rust 侧会话记录（会话结束后调用；幂等）。 */
    private void releaseHandle() {
        if (mHandle == 0 || mHandleReleased) return;
        mHandleReleased = true;
        SessionHandle.sessionClose(mHandle);
    }

    @Override
    public boolean isRunning() {
        return mRunning;
    }

    @Override
    public int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public int getPid() {
        // 工单 25 JNI 边界未暴露 pid；v1 不扩展（见工单 26 Comments）。
        return 0;
    }

    @Override
    public String getCwd() {
        return mCwd;
    }

    @Override
    public String getEngineName() {
        return "rust";
    }
}
