package com.gph.fable.app

import android.view.Surface

/**
 * 工单 10 验证切片：Rust wgpu 渲染器 + PTY 的 JNI 桥（libfable_render.so）。
 * 探针专用，非产品代码；主终端与会话层不动。
 */
object RenderCore {
    init {
        System.loadLibrary("fable-render")
    }

    @JvmStatic external fun rendererCreate(cols: Int, rows: Int): Long

    @JvmStatic external fun rendererDestroy(handle: Long)

    @JvmStatic external fun rendererWrite(handle: Long, data: ByteArray, len: Int)

    @JvmStatic external fun rendererResize(handle: Long, cols: Int, rows: Int)

    @JvmStatic external fun rendererScroll(handle: Long, delta: Int)

    @JvmStatic external fun rendererSetSelection(handle: Long, row: Int, startCol: Int, endCol: Int)

    /** 工单 14：返回当前所有选择区（overlay）的文本，跨行以 '\n' 拼接。 */
    @JvmStatic external fun rendererSelectionText(handle: Long): String

    /** 工单 14：设置字号（px/em，4..128），触发字形图集重建。 */
    @JvmStatic external fun rendererSetFontSize(handle: Long, sizePx: Float)

    /** 工单 22：设置 Apple/Noto 字体文件路径（APK assets 拷贝后的 filesDir 路径；
     * Rust 侧 mmap + sha256 校验，失败自动降级 Noto）。 */
    @JvmStatic external fun rendererSetFontPaths(handle: Long, applePath: String, notoPath: String)

    /** 工单 14：当前字号的单元格像素尺寸，写入 out[0]=宽 out[1]=高。 */
    @JvmStatic external fun rendererGetCellSize(handle: Long, out: IntArray)

    /** 工单 26：同步查询核心光标视口位置，写入 out[0]=列 out[1]=行（0 基）；
     *  返回 1=有光标，0=无光标（out 置 -1）。列模型与核心 DECSET 2027 一致，
     *  CPR 应答用它，避免与旧 Java 模拟器按码位算宽的双轨错位。 */
    @JvmStatic external fun rendererGetCursor(handle: Long, out: IntArray): Int

    /** 工单 29：当前核心标题（OSC 0/2 设置；未设置返回空串）。 */
    @JvmStatic external fun rendererGetTitle(handle: Long): String

    /** 工单 29：读取并清除"标题已变更"标记（true=自上次消费后变过）。 */
    @JvmStatic external fun rendererConsumeTitleChanged(handle: Long): Boolean

    /** 工单 29：读取并清除 bell 标记（true=自上次消费后响过 bell）。 */
    @JvmStatic external fun rendererConsumeBell(handle: Long): Boolean

    /** 工单 29：当前是否在 alternate screen（DECSET 1047/1049）。 */
    @JvmStatic external fun rendererGetModeAltScreen(handle: Long): Boolean

    /** 工单 29：当前是否有任一 mouse tracking 模式激活（X10/1000/1002/1003）。 */
    @JvmStatic external fun rendererGetModeMouseTracking(handle: Long): Boolean

    /** 工单 29：当前光标是否可见（DECSET 25）。 */
    @JvmStatic external fun rendererGetModeCursorVisible(handle: Long): Boolean

    /** 工单 29：当前光标是否闪烁（DECSET 12）。 */
    @JvmStatic external fun rendererGetModeCursorBlink(handle: Long): Boolean

    /** 工单 30：光标键是否 application mode（DECCKM，DECSET ?1）。 */
    @JvmStatic external fun rendererGetModeCursorKeysApplication(handle: Long): Boolean

    /** 工单 30：小键盘是否 application mode（DECKPAM，DECSET ?66）。 */
    @JvmStatic external fun rendererGetModeKeypadApplication(handle: Long): Boolean

    /** 工单 30：bracketed paste（DECSET 2004）是否激活。 */
    @JvmStatic external fun rendererGetModeBracketedPaste(handle: Long): Boolean

    /** 工单 30：推送光标闪烁相位（true = 可见相位；渲染层与核心光标可见性 AND）。 */
    @JvmStatic external fun rendererSetCursorBlinkState(handle: Long, cursorVisible: Boolean)

    /** 工单 31：当前 mouse 是否 SGR 格式（DECSET 1006）。 */
    @JvmStatic external fun rendererGetModeMouseSgr(handle: Long): Boolean

    /** 工单 31：当前 mouse 是否 button-event（1002）或 any-event（1003）。 */
    @JvmStatic external fun rendererGetModeMouseButtonEvent(handle: Long): Boolean

    /** 工单 31：当前可向上回看的历史行数（视口之外）。 */
    @JvmStatic external fun rendererGetScrollbackRows(handle: Long): Int

    /** 工单 31：外部行列区间文本（0 = 活动屏顶，负 = 历史；端排他）。 */
    @JvmStatic external fun rendererGetText(handle: Long, row: Int, startCol: Int, endCol: Int): String

    /** 工单 31：单词列边界 {start,end}（无词返回 null）。 */
    @JvmStatic external fun rendererGetWordBoundsAt(handle: Long, column: Int, row: Int): IntArray?

    /** 工单 31：取词（软换行整行语义；无词返回空串）。 */
    @JvmStatic external fun rendererGetWordAt(handle: Long, column: Int, row: Int): String

    /** 工单 31：完整转录文本（活动屏 + 历史）。 */
    @JvmStatic external fun rendererGetTranscriptText(handle: Long, linesJoined: Boolean, trim: Boolean): String

    /** 工单 14：push 配色板（ARGB），前景/背景/选择色/光标色。 */
    @JvmStatic external fun rendererSetPalette(handle: Long, fgArgb: Int, bgArgb: Int, selectionArgb: Int, cursorArgb: Int)

    /** 工单 04：push 配色板含 ANSI 16 色（ansiArgb 长度 <16 时按内置默认补全）。 */
    @JvmStatic external fun rendererSetPalette16(handle: Long, fgArgb: Int, bgArgb: Int, selectionArgb: Int, cursorArgb: Int, ansiArgb: IntArray?)

    /** 工单 14：恢复核心解析配色。 */
    @JvmStatic external fun rendererResetPalette(handle: Long)

    @JvmStatic external fun rendererAttach(handle: Long, surface: Surface, widthPx: Int, heightPx: Int)

    @JvmStatic external fun rendererDetach(handle: Long)

    @JvmStatic external fun rendererRender(handle: Long, widthPx: Int, heightPx: Int): Boolean

    /** 强制重绘一帧（清除内容签名；surface 重建后首帧可能未上屏）。 */
    @JvmStatic external fun rendererForceRender(handle: Long, widthPx: Int, heightPx: Int): Boolean

    /** 最近一次渲染失败/跳帧原因（选择空白诊断）。 */
    @JvmStatic external fun rendererLastError(handle: Long): String

    @JvmStatic external fun rendererTestPattern(handle: Long, widthPx: Int, heightPx: Int): Boolean

    @JvmStatic external fun rendererInfo(handle: Long): String

    @JvmStatic external fun ptySpawn(shell: String, cols: Int, rows: Int): Long

    @JvmStatic external fun ptyRead(handle: Long, buf: ByteArray): Int

    @JvmStatic external fun ptyWrite(handle: Long, data: ByteArray, len: Int): Int

    @JvmStatic external fun ptyResize(handle: Long, cols: Int, rows: Int)

    @JvmStatic external fun ptyClose(handle: Long)
}
