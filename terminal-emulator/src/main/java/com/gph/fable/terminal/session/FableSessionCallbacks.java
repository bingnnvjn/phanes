package com.gph.fable.terminal.session;

/**
 * 会话层事件回调（工单 26 缝）。所有回调都在主线程投递：
 * Java 实现经内部 main-thread Handler；Rust 实现把 libfable-session 分发线程
 * 的事件 marshal 到主线程后再调用。
 */
public interface FableSessionCallbacks {

    /** PTY 输出字节（UTF-8，可含转义序列）；len 为有效长度。 */
    void onOutput(byte[] data, int len);

    /** 进程退出（exitCode：0=正常，>0 退出码，<0 取反的信号）。 */
    void onExit(int exitCode);

    /** 事件流诊断（ADR-0008 六事件最小集，第一版只做诊断/日志）。 */
    void onDiagnostics(String event, long sessionId, long timestampMs,
                       byte[] data, int exitCode, String message, String[] extra);
}
