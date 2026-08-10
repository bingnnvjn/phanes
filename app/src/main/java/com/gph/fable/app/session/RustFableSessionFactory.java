package com.gph.fable.app.session;

import android.util.Log;

import com.gph.fable.app.SessionHandle;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.terminal.session.FableSession;
import com.gph.fable.terminal.session.FableSessionCallbacks;
import com.gph.fable.terminal.session.FableSessionFactory;
import com.gph.fable.terminal.session.FableSessionSpec;

/**
 * 会话层抽象缝的 Rust 工厂（工单 26）：创建 {@link RustPtySession}
 * （libfable-session.so，fable-v1/25 产物）。
 *
 * 诊断日志订阅（sessionSetLogCallback）为进程级全局回调，注册一次即可；
 * 事件流日志经各会话 FableSessionCallbacks#onDiagnostics 投递（TerminalSession 落日志）。
 */
public final class RustFableSessionFactory implements FableSessionFactory {

    public static final RustFableSessionFactory INSTANCE = new RustFableSessionFactory();

    private static final String LOG_TAG = "FableSessionRust";
    private static boolean sLogCallbackRegistered;

    /** libfable-session 日志级别（工单 25 定稿：0=info 1=warn 2=error，2026-08-11 核实）。 */
    private static final int LOG_LEVEL_WARN = 1;
    private static final int LOG_LEVEL_ERROR = 2;

    private RustFableSessionFactory() {
    }

    @Override
    public FableSession create(FableSessionSpec spec, FableSessionCallbacks callbacks) {
        if (spec == null || callbacks == null) return null;
        try {
            registerLogCallbackOnce();
            RustPtySession session = new RustPtySession(spec, callbacks);
            if (!session.isValid()) {
                // sessionCreate 已把 lastError 记日志。
                return null;
            }
            return session;
        } catch (Throwable t) {
            // libfable-session.so 缺失/加载失败等极端情况：降级为创建失败，
            // TerminalSession 统一写 "[Session creation failed]" 结束会话。
            Logger.logError(LOG_TAG, "Rust session create failed: " + t);
            return null;
        }
    }

    @Override
    public String getEngineName() {
        return "rust";
    }

    /** 进程级日志订阅只注册一次；level 0/1/2 = info/warn/error。 */
    private static synchronized void registerLogCallbackOnce() {
        if (sLogCallbackRegistered) return;
        sLogCallbackRegistered = true;
        try {
            SessionHandle.sessionSetLogCallback((sessionId, level, tag, message, ts) -> {
                int priority = level == LOG_LEVEL_WARN ? Log.WARN
                    : level == LOG_LEVEL_ERROR ? Log.ERROR : Log.INFO;
                Logger.logMessage(priority, LOG_TAG, "session=" + sessionId + " tag=" + tag + " msg=" + message);
            });
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "sessionSetLogCallback failed: " + t);
            sLogCallbackRegistered = false;
        }
    }
}
