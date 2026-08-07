package com.gph.fable.app;

/**
 * 工单 08 验证切片：libghostty-vt JNI 桥的 Java 侧（探针专用，非产品代码）。
 */
public final class GhosttySpike {
    static {
        System.loadLibrary("ghostty-spike");
    }

    private GhosttySpike() {
    }

    /* libghostty-vt 终端核心 */
    public static native long terminalCreate(int cols, int rows, long maxScrollback);

    public static native void terminalWrite(long term, byte[] data, int len);

    public static native void terminalResize(long term, int cols, int rows);

    public static native void terminalScroll(long term, int delta);

    public static native byte[] terminalFormatPlain(long term);

    public static native void terminalFree(long term);

    /* 探针 PTY（spawn $PREFIX/bin/bash --login） */
    public static native long ptySpawn(String shell, int cols, int rows);

    public static native int ptyRead(long session, byte[] buf);

    public static native int ptyWrite(long session, byte[] data, int len);

    public static native void ptyResize(long session, int cols, int rows);

    public static native void ptyClose(long session);

    public static native String buildInfo();

    public static native String lastError();
}
