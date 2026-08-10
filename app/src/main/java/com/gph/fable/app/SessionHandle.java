package com.gph.fable.app;

/**
 * 工单 25 JNI 边界（Rust libfable-session.so，生产级会话层）：
 * SessionHandle 创建/读写/resize/close + 事件回调 + 诊断日志订阅。
 *
 * 环境快照由本侧构造传入（"KEY=VALUE" 数组 + cwd），Rust 不重建
 * AndroidShellEnvironment（ADR-0008 决策 8）。事件回调与 sessionRead 是
 * 同一输出流的两种消费方式（回调收 output_chunk 事件；read 收只读侧缓冲副本），
 * 第一版 Kotlin 只接诊断/日志订阅。
 */
public final class SessionHandle {
    static {
        System.loadLibrary("fable-session");
    }

    private SessionHandle() {
    }

    /** 创建会话；callback 可为 null（输出走 sessionRead）。返回句柄，0=失败（sessionLastError）。 */
    public static native long sessionCreate(String shell, String[] args, String[] env,
                                            String cwd, int cols, int rows,
                                            SessionEventCallback callback);

    /** 写入；返回写入字节数，-1=错误。 */
    public static native int sessionWrite(long handle, byte[] data, int len);

    /** 非阻塞读只读侧输出缓冲；返回字节数，0=暂无输出，-1=错误（会话已关返回 0）。 */
    public static native int sessionRead(long handle, byte[] buf);

    public static native void sessionResize(long handle, int cols, int rows);

    public static native void sessionClose(long handle);

    /** 事后注册/替换事件回调；null 取消（分发线程退出）。 */
    public static native void sessionSetEventCallback(long handle, SessionEventCallback callback);

    /** 诊断日志订阅；null 取消。 */
    public static native void sessionSetLogCallback(SessionLogCallback callback);

    public static native String sessionLastError();
}
