package com.gph.fable.app;

/**
 * 工单 25 诊断日志订阅（Kotlin 第一版只接诊断/日志，ADR-0008 决策 7）。
 * level：0=info 1=warn 2=error。
 */
public interface SessionLogCallback {
    void onLog(long sessionId, int level, String tag, String message, long timestampMs);
}
