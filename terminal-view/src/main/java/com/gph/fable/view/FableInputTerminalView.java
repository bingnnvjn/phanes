package com.gph.fable.view;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.gph.fable.terminal.TerminalSession;
import com.gph.fable.terminal.adapter.CoreAdapter;

/**
 * 主终端输入视图（工单 15）：继续承担 IME/硬件键/手势/长按选择的既有逻辑，
 * 但不再绘制终端正文——正文由 fable-render（SurfaceView + CoreAdapter）绘制。
 *
 * 与 {@link CoreAdapter} 的同步点：
 * - onDraw：滚动偏移同步（mTopRow → adapter.scroll）、选择 overlay 同步、请求渲染；
 * - setTextSize：字号同步到核心（getCellSize 决定行列重排）；
 * - getSelectedText：复制/分享优先走缝的选中文本 API（工单 14）。
 */
public final class FableInputTerminalView extends TerminalView {

    private CoreAdapter mCoreAdapter;
    private int mLastSyncedTopRow;
    private long mSelectionSyncSignature = Long.MIN_VALUE;

    public FableInputTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 绑定/解绑当前会话的核心缝实现。绑定时把当前字号推给核心，
     * 并按核心单元格尺寸重排（旧路径下为 null，保持 TerminalView 原行为）。
     */
    public void setCoreAdapter(CoreAdapter coreAdapter) {
        mCoreAdapter = coreAdapter;
        mLastSyncedTopRow = mTopRow;
        mSelectionSyncSignature = Long.MIN_VALUE;
        if (coreAdapter != null) {
            if (coreAdapter.supportsFontSize() && mRenderer != null) {
                coreAdapter.setFontSize(mRenderer.mTextSize);
            }
            clearSelectionOverlays();
            updateSize();
        }
        invalidate();
    }

    public CoreAdapter getCoreAdapter() {
        return mCoreAdapter;
    }

    @Override
    public boolean attachSession(TerminalSession session) {
        mLastSyncedTopRow = 0;
        mSelectionSyncSignature = Long.MIN_VALUE;
        return super.attachSession(session);
    }

    @Override
    public void setTextSize(int textSize) {
        super.setTextSize(textSize);
        if (mCoreAdapter != null && mCoreAdapter.supportsFontSize() && mRenderer != null) {
            // 直接生效保持缩放跟手（曾用 50ms 合并窗口导致不跟手，已回退）。
            mCoreAdapter.setFontSize(mRenderer.mTextSize);
            updateSize();
        }
    }

    @Override
    public void updateSize() {
        if (mCoreAdapter == null || !mCoreAdapter.supportsFontSize() || mRenderer == null) {
            super.updateSize();
            return;
        }

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return;

        int[] cell = new int[2];
        mCoreAdapter.getCellSize(cell);
        int cellWidth = Math.max(1, cell[0]);
        int cellHeight = Math.max(1, cell[1]);
        int newColumns = Math.max(4, viewWidth / cellWidth);
        int newRows = Math.max(4, viewHeight / cellHeight);

        if (mEmulator == null || newColumns != mEmulator.mColumns || newRows != mEmulator.mRows) {
            mTermSession.updateSize(newColumns, newRows, cellWidth, cellHeight);
            mEmulator = mTermSession.getEmulator();
            if (mClient != null) mClient.onEmulatorSet();
            updateTerminalCursorBlinkerForEmulator();

            mTopRow = 0;
            scrollTo(0, 0);
            invalidate();
        }
    }

    @Override
    public String getCoreSelectionText() {
        if (mCoreAdapter != null && mCoreAdapter.supportsSelectionText()) {
            return mCoreAdapter.getSelectionText();
        }
        return null;
    }

    @Override
    public void stopTextSelectionMode() {
        super.stopTextSelectionMode();
        if (mCoreAdapter != null) {
            mSelectionSyncSignature = Long.MIN_VALUE;
            clearSelectionOverlays();
        }
    }

    @Override
    public boolean isOpaque() {
        // 新路径下正文由 SurfaceView 绘制，本视图透明（只画选择手柄）。
        return mCoreAdapter == null && super.isOpaque();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mCoreAdapter == null) {
            super.onDraw(canvas);
            return;
        }
        if (mEmulator == null) {
            canvas.drawColor(0xFF000000);
            return;
        }

        // 选择手柄仍由本视图绘制（位于 SurfaceView 之上）。
        renderTextSelection();
        syncAdapterState();
    }

    // ---------- 坐标换算：与 fable-render 同一套"拉伸网格"对齐 ----------
    // 渲染器按 surface 宽高 / 行列数均分格子（cell_w = W/cols, row_h = H/rows）；
    // 旧路径 TerminalView 用 mRenderer 字体度量换算，两套网格逐行漂移，
    // 导致选择手柄/触摸行与蓝色高亮条越往下差越多（fable-v1/15 真机反馈）。
    // 新路径统一用拉伸网格；覆盖后不再经过旧路径 getCursorY 的 -40 换算，
    // 旧路径（adapter 为 null）保持原行为不变。

    private float colWidthPx() {
        return (mEmulator == null || mEmulator.mColumns <= 0)
                ? 0f
                : getWidth() / (float) mEmulator.mColumns;
    }

    private float rowHeightPx() {
        return (mEmulator == null || mEmulator.mRows <= 0)
                ? 0f
                : getHeight() / (float) mEmulator.mRows;
    }

    private boolean usingRenderGrid() {
        return mCoreAdapter != null && mEmulator != null;
    }

    private int screenColumnAt(float x) {
        float colW = colWidthPx();
        return colW <= 0f ? 0 : (int) (x / colW);
    }

    private int screenRowAt(float y) {
        float rowH = rowHeightPx();
        return rowH <= 0f ? 0 : (int) (y / rowH);
    }

    private int pixelXAt(int column) {
        return Math.round(column * colWidthPx());
    }

    private int pixelYAt(int row) {
        return Math.round((row - mTopRow) * rowHeightPx());
    }

    @Override
    public int getCursorX(float x) {
        return usingRenderGrid() ? screenColumnAt(x) : super.getCursorX(x);
    }

    @Override
    public int getCursorY(float y) {
        return usingRenderGrid() ? screenRowAt(y) + mTopRow : super.getCursorY(y);
    }

    @Override
    public int getPointX(int cx) {
        if (!usingRenderGrid()) return super.getPointX(cx);
        if (mEmulator.mColumns > 0 && cx > mEmulator.mColumns) cx = mEmulator.mColumns;
        return pixelXAt(cx);
    }

    @Override
    public int getPointY(int cy) {
        return usingRenderGrid() ? pixelYAt(cy) : super.getPointY(cy);
    }

    @Override
    public int[] getColumnAndRow(MotionEvent event, boolean relativeToScroll) {
        if (!usingRenderGrid()) {
            return super.getColumnAndRow(event, relativeToScroll);
        }
        int column = screenColumnAt(event.getX());
        int row = screenRowAt(event.getY());
        if (relativeToScroll) row += mTopRow;
        return new int[] { column, row };
    }

    private void syncAdapterState() {
        if (mCoreAdapter == null || mEmulator == null) return;

        if (mCoreAdapter.supportsScrollback() && mTopRow != mLastSyncedTopRow) {
            mCoreAdapter.scroll(mTopRow - mLastSyncedTopRow);
            mLastSyncedTopRow = mTopRow;
        }

        syncSelectionOverlays();

        int width = getWidth();
        int height = getHeight();
        if (width > 0 && height > 0) {
            mCoreAdapter.render(width, height);
        }
    }

    private void syncSelectionOverlays() {
        int[] selectors = new int[4];
        getSelectionSelectors(selectors);
        boolean active = isSelectingText();

        long signature = active ? 1L : 0L;
        for (int value : selectors) signature = signature * 31 + value;
        if (signature == mSelectionSyncSignature) return;
        mSelectionSyncSignature = signature;

        // 缝约定：选择行是视口相对坐标（0 = 当前视口顶行，向上回看为负）。
        // 旧路径 TerminalBuffer 外部坐标同样以 0 = 屏幕顶行为基准，因此
        // 屏幕行 → 视口行 = row - mTopRow（mTopRow <= 0，回看时取正）。
        int viewportRows = mEmulator.mRows;
        int y1 = active ? selectors[0] - mTopRow : -1;
        int y2 = active ? selectors[1] - mTopRow : -1;
        int x1 = active ? selectors[2] : 0;
        int x2 = active ? selectors[3] : 0;
        for (int row = 0; row < viewportRows; row++) {
            if (active && row >= y1 && row <= y2 && x2 > x1) {
                int startCol = (row == y1) ? Math.max(0, x1) : 0;
                int endCol = (row == y2) ? Math.min(mEmulator.mColumns, x2 + 1) : mEmulator.mColumns;
                mCoreAdapter.setSelection(row, startCol, endCol);
            } else {
                mCoreAdapter.setSelection(row, 0, 0);
            }
        }
    }

    private void clearSelectionOverlays() {
        if (mCoreAdapter == null || mEmulator == null) return;
        for (int row = 0; row < mEmulator.mRows; row++) {
            mCoreAdapter.setSelection(row, 0, 0);
        }
    }
}
