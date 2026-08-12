package com.gph.fable.app;

import android.view.Surface;

/**
 * 工单 10 验证切片：Rust wgpu 渲染器 + PTY 的 JNI 桥（libfable_render.so）。
 * 探针专用，非产品代码；主终端与会话层不动。
 */
public final class RenderCore {
    static {
        System.loadLibrary("fable-render");
    }

    private RenderCore() {
    }

    public static native long rendererCreate(int cols, int rows);

    public static native void rendererDestroy(long handle);

    public static native void rendererWrite(long handle, byte[] data, int len);

    public static native void rendererResize(long handle, int cols, int rows);

    public static native void rendererScroll(long handle, int delta);

    public static native void rendererSetSelection(long handle, int row, int startCol, int endCol);

    /** 工单 14：返回当前所有选择区（overlay）的文本，跨行以 '\n' 拼接。 */
    public static native String rendererSelectionText(long handle);

    /** 工单 14：设置字号（px/em，4..128），触发字形图集重建。 */
    public static native void rendererSetFontSize(long handle, float sizePx);

    /** 工单 22：设置 Apple/Noto 字体文件路径（APK assets 拷贝后的 filesDir 路径；
     * Rust 侧 mmap + sha256 校验，失败自动降级 Noto）。 */
    public static native void rendererSetFontPaths(long handle, String applePath, String notoPath);

    /** 工单 14：当前字号的单元格像素尺寸，写入 out[0]=宽 out[1]=高。 */
    public static native void rendererGetCellSize(long handle, int[] out);

    /** 工单 26：同步查询核心光标视口位置，写入 out[0]=列 out[1]=行（0 基）；
     *  返回 1=有光标，0=无光标（out 置 -1）。列模型与核心 DECSET 2027 一致，
     *  CPR 应答用它，避免与旧 Java 模拟器按码位算宽的双轨错位。 */
    public static native int rendererGetCursor(long handle, int[] out);

    /** 工单 29：当前核心标题（OSC 0/2 设置；未设置返回空串）。 */
    public static native String rendererGetTitle(long handle);

    /** 工单 29：读取并清除"标题已变更"标记（true=自上次消费后变过）。 */
    public static native boolean rendererConsumeTitleChanged(long handle);

    /** 工单 29：读取并清除 bell 标记（true=自上次消费后响过 bell）。 */
    public static native boolean rendererConsumeBell(long handle);

    /** 工单 29：当前是否在 alternate screen（DECSET 1047/1049）。 */
    public static native boolean rendererGetModeAltScreen(long handle);

    /** 工单 29：当前是否有任一 mouse tracking 模式激活（X10/1000/1002/1003）。 */
    public static native boolean rendererGetModeMouseTracking(long handle);

    /** 工单 29：当前光标是否可见（DECSET 25）。 */
    public static native boolean rendererGetModeCursorVisible(long handle);

    /** 工单 29：当前光标是否闪烁（DECSET 12）。 */
    public static native boolean rendererGetModeCursorBlink(long handle);

    /** 工单 14：push 配色板（ARGB），前景/背景/选择色/光标色。 */
    public static native void rendererSetPalette(long handle, int fgArgb, int bgArgb, int selectionArgb, int cursorArgb);

    /** 工单 04：push 配色板含 ANSI 16 色（ansiArgb 长度 <16 时按内置默认补全）。 */
    public static native void rendererSetPalette16(long handle, int fgArgb, int bgArgb, int selectionArgb, int cursorArgb, int[] ansiArgb);

    /** 工单 14：恢复核心解析配色。 */
    public static native void rendererResetPalette(long handle);

    public static native void rendererAttach(long handle, Surface surface, int widthPx, int heightPx);

    public static native void rendererDetach(long handle);

    public static native boolean rendererRender(long handle, int widthPx, int heightPx);

    /** 强制重绘一帧（清除内容签名；surface 重建后首帧可能未上屏）。 */
    public static native boolean rendererForceRender(long handle, int widthPx, int heightPx);

    /** 最近一次渲染失败/跳帧原因（选择空白诊断）。 */
    public static native String rendererLastError(long handle);

    public static native boolean rendererTestPattern(long handle, int widthPx, int heightPx);

    public static native String rendererInfo(long handle);

    public static native long ptySpawn(String shell, int cols, int rows);

    public static native int ptyRead(long handle, byte[] buf);

    public static native int ptyWrite(long handle, byte[] data, int len);

    public static native void ptyResize(long handle, int cols, int rows);

    public static native void ptyClose(long handle);
}
