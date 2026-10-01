package com.gph.fable.core.adapter;

import android.view.Surface;

/**
 * CoreAdapter（换引擎的缝，ADR-0003）。
 *
 * 主终端 UI 与会话接线只依赖本接口，不依赖具体核心实现：
 * 旧路径（TerminalEmulator）与新路径（fable-render）均可实现本缝。
 *
 * 字节/resize 由会话层调用；滚动/选择/字号/配色板/attach/detach/render
 * 由 UI 层调用；能力清单让 UI 按核心能力降级。
 */
public interface CoreAdapter {

    /** 把 PTY 输出字节喂入核心（UTF-8，可含转义序列）。 */
    void write(byte[] data, int len);

    /** 核心行列 resize（PTY winsize 由会话层保证一致）。 */
    void resize(int columns, int rows);

    /** 视口滚动，delta 为行数：负值向上（回看历史），正值向下。 */
    void scroll(int delta);

    /**
     * 设置某行选择区 [startCol, endCol)（endCol 排他）；startCol == endCol 清除该行。
     * 行坐标约定：视口相对——0 = 当前视口顶行，向上回看为负（与 TerminalBuffer
     * 外部坐标一致，旧路径直接透传；FableInputTerminalView 同步时换算 row - mTopRow）。
     */
    void setSelection(int row, int startCol, int endCol);

    /** 返回当前所有选择区文本，跨行以 '\n' 拼接；无选择返回空串。 */
    String getSelectionText();

    /** 设置字号（px，核心会裁剪到支持范围）。 */
    void setFontSize(float sizePx);

    /** 当前字号的单元格像素尺寸，out[0]=宽 px，out[1]=高 px。 */
    void getCellSize(int[] out);

    /** push 配色板（ARGB），前景/背景/选择色/光标色。 */
    void setPalette(int fgArgb, int bgArgb, int selectionArgb, int cursorArgb);

    /** push ANSI 16 色（ARGB，index 0-15；colors.properties color0-15 / 内置明暗）。 */
    void setAnsiPalette(int[] ansiArgb);

    /** 恢复核心解析配色。 */
    void resetPalette();

    /** 把核心渲染器附着到 Surface（宽高为像素）。 */
    void attach(Surface surface, int widthPx, int heightPx);

    /** 从 Surface 分离渲染器。 */
    void detach();

    /** 请求渲染一帧（宽高为像素；核心可自行去重）。 */
    void render(int widthPx, int heightPx);

    /** 强制重绘一帧（清除渲染器内容签名；surface 重建后首帧可能未上屏）。 */
    void forceRender(int widthPx, int heightPx);

    /** 重置核心状态（清屏/清滚动缓冲）。 */
    void reset();

    /** 销毁核心与渲染器；之后本实例不可再用。 */
    void destroy();

    /**
     * 工单 26：查询核心光标视口位置（0 基列/行，核心 2027 列模型）。
     * 返回 true 且 out[0]/out[1] 有效时，终端查询应答（如 CPR）以它为准；
     * 默认返回 false（旧路径用自身列模型应答）。
     */
    default boolean getCursorPosition(int[] out) {
        return false;
    }

    /** 工单 29：当前核心标题（OSC 0/2 设置；未设置返回空串）。 */
    default String getTitle() {
        return "";
    }

    /** 工单 29：读取并清除"标题已变更"标记（true=自上次消费后标题变过）。 */
    default boolean consumeTitleChanged() {
        return false;
    }

    /** 工单 29：读取并清除 bell 标记（true=自上次消费后响过 bell）。 */
    default boolean consumeBell() {
        return false;
    }

    /** 工单 29：当前是否在 alternate screen（DECSET 1047/1049）。 */
    default boolean getModeAlternateScreen() {
        return false;
    }

    /** 工单 29：当前是否有任一 mouse tracking 模式激活（X10/1000/1002/1003）。 */
    default boolean getModeMouseTracking() {
        return false;
    }

    /** 工单 29：当前光标是否可见（DECSET 25）。旧路径无隐藏光标能力，默认可见。 */
    default boolean getModeCursorVisible() {
        return true;
    }

    /** 工单 29：当前光标是否闪烁（DECSET 12）。 */
    default boolean getModeCursorBlink() {
        return false;
    }

    /** 工单 30：光标键是否 application mode（DECCKM，DECSET ?1）。 */
    default boolean getModeCursorKeysApplication() {
        return false;
    }

    /** 工单 30：小键盘是否 application mode（DECKPAM，DECSET ?66）。 */
    default boolean getModeKeypadApplication() {
        return false;
    }

    /** 工单 30：bracketed paste（DECSET 2004）是否激活。 */
    default boolean getModeBracketedPaste() {
        return false;
    }

    /**
     * 工单 30：把当前光标闪烁相位推给渲染层（true = 可见相位）。
     * 旧路径默认忽略（旧模拟器自绘由 {@code TerminalEmulator#setCursorBlinkState} 承担）；
     * 新路径（fable-render）用它与核心光标可见性 AND，实现与旧路径一致的闪烁。
     */
    default void setCursorBlinkState(boolean cursorVisible) {
    }

    // ---------- 工单 31：内容/几何/输入缝能力（旧模拟器删除后由核心承担） ----------

    /** 鼠标事件按钮码（与旧 TerminalEmulator MOUSE_* 常量一致，X10 编码）。 */
    int MOUSE_LEFT_BUTTON = 0;
    int MOUSE_LEFT_BUTTON_MOVED = 32;
    int MOUSE_WHEELUP_BUTTON = 64;
    int MOUSE_WHEELDOWN_BUTTON = 65;

    /**
     * 工单 31：当前可向上回看的历史行数（视口之外；0 = 无历史）。
     * 供滚动钳制 / 滚轴度量 / fling 边界使用（旧路径读 TerminalBuffer transcript）。
     */
    default int getScrollbackRows() {
        return 0;
    }

    /**
     * 工单 31：读取指定外部行的列区间文本 [startCol, endCol)（端排他）。
     * 行坐标约定与选择一致：0 = 活动屏顶行，负 = 向上历史（视口无关的绝对行）。
     * 每格文本按列序拼接：空格格返回 " "，宽字符占位格返回 ""。
     * 越界/无核心返回空串。供词边界展开与 accessibility 使用。
     */
    default String getText(int row, int startCol, int endCol) {
        return "";
    }

    /**
     * 工单 31：返回 (column, externalRow) 处单词的列边界 {start, end}（同行展开，
     * 语义与旧 TerminalBuffer 选词一致：空格/空格为边界）。
     * 无单词（落在空格/空/越界）返回 null。供长按选择词边界展开。
     */
    default int[] getWordBoundsAt(int column, int externalRow) {
        return null;
    }

    /**
     * 工单 31：返回 (column, externalRow) 处的单词文本。软换行连接后的整词
     * （语义与旧 {@code TerminalBuffer#getWordAtLocation} 一致）；无单词返回空串。
     * 供 URL 点击取词。
     */
    default String getWordAt(int column, int externalRow) {
        return "";
    }

    /**
     * 工单 31：当前 mouse 协议是否 SGR（DECSET 1006）。旧路径编码用
     * SGR 与否决定输出格式（X10 8-bit 或 SGR）。
     */
    default boolean getModeMouseSgr() {
        return false;
    }

    /**
     * 工单 31：当前 mouse 是否 button-event 模式（DECSET 1002，含 any-event 1003）。
     * 非该模式下左键拖动移动不发送。
     */
    default boolean getModeMouseButtonEvent() {
        return false;
    }

    /**
     * 工单 31：完整转录文本（活动屏 + 历史）。
     * linesJoined=true 时软换行连接为整行（旧 getTranscriptTextWithFullLinesJoined）；
     * false 时每行独立以 '\n' 连接（旧 getTranscriptTextWithoutJoinedLines）。
     * trim=true 去首尾空白（旧调用方统一 trim）。无核心返回空串。
     */
    default String getTranscriptText(boolean linesJoined, boolean trim) {
        return "";
    }

    boolean supportsSelectionText();

    boolean supportsFontSize();

    boolean supportsPalette();

    boolean supportsScrollback();
}
