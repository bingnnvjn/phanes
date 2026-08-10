package com.gph.fable.terminal.session;

/**
 * 会话层句柄接口（工单 26 缝，ADR-0008 会话层抽象缝）：
 * PTY + 进程 + 生命周期的最小契约，Java（现状）与 Rust（fable-v1/25 产物）
 * 双实现平级。输出/退出经 {@link FableSessionCallbacks} 投递。
 */
public interface FableSession {

    /** 写入用户输入字节到 PTY；data[offset, offset+len)。 */
    void write(byte[] data, int offset, int len);

    /** PTY winsize resize（像素尺寸仅供 Java 实现 TIOCSWINSZ，Rust 忽略）。 */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /**
     * 终止进程并释放会话资源（幂等）。Java 实现等同原 finishIfRunning
     * （SIGKILL，资源清理在退出事件路径）；Rust 实现走 sessionClose。
     * 调用后仍会收到 {@link FableSessionCallbacks#onExit(int)}。
     */
    void close();

    boolean isRunning();

    int getExitStatus();

    /** 进程 pid；Java 实现真实 pid，Rust 第一版返回 0（未暴露 pid，见工单 26 Comments）。 */
    int getPid();

    /** 当前工作目录；Java 读 /proc/<pid>/cwd，Rust 第一版返回创建时 cwd。 */
    String getCwd();

    /** 会话层引擎名："java" / "rust"（诊断用；区别于 TerminalSession#mSessionName 用户标识）。 */
    String getEngineName();
}
