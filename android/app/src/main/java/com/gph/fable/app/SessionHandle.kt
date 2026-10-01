package com.gph.fable.app

/**
 * 工单 25 JNI 边界（Rust libfable-session.so，生产级会话层）：
 * SessionHandle 创建/读写/resize/close + 事件回调 + 诊断日志订阅。
 *
 * 环境快照由本侧构造传入（"KEY=VALUE" 数组 + cwd），Rust 不重建
 * AndroidShellEnvironment（ADR-0008 决策 8）。事件回调与 sessionRead 是
 * 同一输出流的两种消费方式（回调收 output_chunk 事件；read 收只读侧缓冲副本），
 * 第一版 Kotlin 只接诊断/日志订阅。
 */
object SessionHandle {
    init {
        System.loadLibrary("fable-session")
    }

    /**
     * 创建会话；callback 可为 null（输出走 sessionRead）。返回句柄，0=失败（sessionLastError）。
     * args[0] 是 argv0 名（登录 shell 为 "-bash"，与 Java createSubprocess / execvp 同语义；
     * 程序路径由 shell 参数决定，argv0 与真实参数分离，Rust 侧经 portable-pty argv0 补丁实现）。
     */
    @JvmStatic external fun sessionCreate(shell: String, args: Array<String>, env: Array<String>,
                                            cwd: String, cols: Int, rows: Int,
                                            callback: SessionEventCallback?): Long

    /** 写入；返回写入字节数，-1=错误。 */
    @JvmStatic external fun sessionWrite(handle: Long, data: ByteArray, len: Int): Int

    /** 非阻塞读只读侧输出缓冲；返回字节数，0=暂无输出，-1=错误（会话已关返回 0）。 */
    @JvmStatic external fun sessionRead(handle: Long, buf: ByteArray): Int

    @JvmStatic external fun sessionResize(handle: Long, cols: Int, rows: Int)

    @JvmStatic external fun sessionClose(handle: Long)

    /** 事后注册/替换事件回调；null 取消（分发线程退出）。 */
    @JvmStatic external fun sessionSetEventCallback(handle: Long, callback: SessionEventCallback?)

    /** 诊断日志订阅；null 取消。 */
    @JvmStatic external fun sessionSetLogCallback(callback: SessionLogCallback?)

    @JvmStatic external fun sessionLastError(): String
}
