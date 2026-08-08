package com.gph.fable.terminal.adapter;

import android.view.Surface;

import com.gph.fable.terminal.TerminalEmulator;

import java.util.Map;
import java.util.TreeMap;

/**
 * CoreAdapter 的旧路径实现：包一层 {@link TerminalEmulator}。
 *
 * 主终端当前走新路径（fable-render）；本实现保留为回退参考，
 * 同时作为缝 2 JVM 单测的契约实现（字节→状态 / resize 重排 / 滚动）。
 *
 * 说明：旧路径渲染（Canvas）不属于本适配器，attach/detach/render 为空操作；
 * 字号/配色板能力由 TerminalView 侧的渲染器承载，本适配器声明不支持。
 */
public final class TerminalEmulatorCoreAdapter implements CoreAdapter {

    private final TerminalEmulator mEmulator;
    private int mCellWidthPixels;
    private int mCellHeightPixels;
    private int mTopRow;

    /** row -> {startCol(inclusive), endCol(inclusive)}，与渲染器 overlay 语义对齐（endCol 排他由调用方换算）。 */
    private final TreeMap<Integer, int[]> mSelection = new TreeMap<>();

    public TerminalEmulatorCoreAdapter(TerminalEmulator emulator, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = emulator;
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
    }

    @Override
    public void write(byte[] data, int len) {
        mEmulator.append(data, len);
    }

    @Override
    public void resize(int columns, int rows) {
        if (mEmulator.mRows == rows && mEmulator.mColumns == columns) {
            return;
        }
        mEmulator.resize(columns, rows, mCellWidthPixels, mCellHeightPixels);
        if (mTopRow < -mEmulator.getScreen().getActiveTranscriptRows()) {
            mTopRow = -mEmulator.getScreen().getActiveTranscriptRows();
        }
    }

    @Override
    public void scroll(int delta) {
        int maxTopRow = -mEmulator.getScreen().getActiveTranscriptRows();
        mTopRow = Math.min(0, Math.max(maxTopRow, mTopRow + delta));
    }

    @Override
    public void setSelection(int row, int startCol, int endCol) {
        // 缝约定：row 为视口相对坐标（0 = 当前视口顶行）；直接存视口行，
        // getSelectionText 读取时换算为 TerminalBuffer 外部行。
        if (endCol > startCol) {
            mSelection.put(row, new int[] { startCol, endCol - 1 });
        } else {
            mSelection.remove(row);
        }
    }

    @Override
    public String getSelectionText() {
        if (mSelection.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, int[]> entry : mSelection.entrySet()) {
            int viewportRow = entry.getKey();
            if (viewportRow < 0 || viewportRow >= mEmulator.mRows) continue;
            // 视口行 → TerminalBuffer 外部行（0 = 屏幕顶；回看为负）。
            int externalRow = viewportRow + mTopRow;
            int transcriptTop = -mEmulator.getScreen().getActiveTranscriptRows();
            if (externalRow < transcriptTop) externalRow = transcriptTop;
            int[] range = entry.getValue();
            if (!first) {
                out.append('\n');
            }
            first = false;
            out.append(mEmulator.getScreen().getSelectedText(range[0], externalRow, range[1], externalRow));
        }
        return out.toString();
    }

    @Override
    public void setFontSize(float sizePx) {
        // 旧路径字号由 TerminalView/TerminalRenderer 承载，本适配器不处理。
    }

    @Override
    public void getCellSize(int[] out) {
        if (out.length >= 2) {
            out[0] = mCellWidthPixels;
            out[1] = mCellHeightPixels;
        }
    }

    @Override
    public void setPalette(int fgArgb, int bgArgb, int selectionArgb, int cursorArgb) {
        // 旧路径配色由 TerminalEmulator 解析 + TerminalRenderer 绘制，本适配器不处理。
    }

    @Override
    public void setAnsiPalette(int[] ansiArgb) {
        // 旧路径同 setPalette：配色由 checkForFontAndColors 经 TerminalColors.COLOR_SCHEME 处理。
    }

    @Override
    public void resetPalette() {
        // 见 setPalette。
    }

    @Override
    public void attach(Surface surface, int widthPx, int heightPx) {
        // 旧路径渲染由 TerminalView.onDraw() 承担，无需 Surface。
    }

    @Override
    public void detach() {
        // 见 attach。
    }

    @Override
    public void render(int widthPx, int heightPx) {
        // 见 attach。
    }

    @Override
    public void forceRender(int widthPx, int heightPx) {
        // 旧路径无独立渲染器，见 render()。
    }

    @Override
    public void reset() {
        mEmulator.reset();
        mTopRow = 0;
        mSelection.clear();
    }

    @Override
    public void destroy() {
        mSelection.clear();
        mTopRow = 0;
    }

    @Override
    public boolean supportsSelectionText() {
        return true;
    }

    @Override
    public boolean supportsFontSize() {
        return false;
    }

    @Override
    public boolean supportsPalette() {
        return false;
    }

    @Override
    public boolean supportsScrollback() {
        return true;
    }

    /** 测试/诊断用：当前视口顶部行（0 或负值）。 */
    public int getTopRow() {
        return mTopRow;
    }
}
