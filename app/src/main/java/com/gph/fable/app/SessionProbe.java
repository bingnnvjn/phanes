package com.gph.fable.app;

/**
 * 工单 24 验证切片：portable-pty JNI 桥的 Java 侧（探针专用，非产品代码）。
 * 环境快照由 Kotlin 构造传入（PREFIX/HOME/PATH/TERM/TMPDIR），Rust 侧不重建
 * termux-shared 环境逻辑。
 */
public final class SessionProbe {
    static {
        System.loadLibrary("fable-session");
    }

    private SessionProbe() {
    }

    /** 起一个 bash --login 会话；env 为 "KEY=VALUE" 数组，cwd 为工作目录。 */
    public static native long ptySpawn(String shell, String[] env, String cwd, int cols, int rows);

    /** 阻塞读；返回字节数，0=EOF（会话结束），-1=错误（lastError）。 */
    public static native int ptyRead(long handle, byte[] buf);

    /** 写入；返回写入字节数，-1=错误。 */
    public static native int ptyWrite(long handle, byte[] data, int len);

    public static native void ptyResize(long handle, int cols, int rows);

    public static native void ptyClose(long handle);

    public static native String buildInfo();

    public static native String lastError();
}
