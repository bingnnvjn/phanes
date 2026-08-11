package com.gph.fable.terminal.session;

/**
 * 会话层句柄接口（工单 26 缝，ADR-0008 会话层抽象缝）：
 * PTY + 进程 + 生命周期的最小契约；唯一实现 = Rust（fable-v1/25 产物，
 * Java 会话层已随工单 27 下线删除）。输出/退出经 {@link FableSessionCallbacks} 投递。
 */
public interface FableSession {

    /** 写入用户输入字节到 PTY；data[offset, offset+len)。 */
    void write(byte[] data, int offset, int len);

    /** PTY winsize resize（像素尺寸由 Kotlin 侧计算传入；Rust 忽略）。 */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /**
     * 终止进程并释放会话资源（幂等）。Rust 实现走 sessionClose
     * （SIGKILL，资源清理在退出事件路径）。
     * 调用后仍会收到 {@link FableSessionCallbacks#onExit(int)}。
     */
    void close();

    boolean isRunning();

    int getExitStatus();

    /** 进程 pid；Rust 第一版返回 0（未暴露 pid，见工单 26 Comments）。 */
    int getPid();

    /** 当前工作目录；Rust 第一版返回创建时 cwd（Java 下线后无 /proc 实时读）。 */
    String getCwd();

    /** 会话层引擎名："rust"（诊断用；区别于 TerminalSession#mSessionName 用户标识）。 */
    String getEngineName();
}
