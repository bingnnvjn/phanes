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
                // close() 后的残余输出不再投递；session_closed 仍需处理（onExit）。
                if (!mClosed && data != null && data.length > 0) {
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
            SessionHandle.sessionWrite(mHandle, data, len);
        } else {
            byte[] slice = new byte[len];
            System.arraycopy(data, offset, slice, 0, len);
            SessionHandle.sessionWrite(mHandle, slice, len);
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
        }
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
