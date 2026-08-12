package com.gph.fable.app.terminal.adapter;

import android.content.Context;
import android.view.Surface;

import com.gph.fable.app.FontAssets;
import com.gph.fable.app.RenderCore;
import com.gph.fable.app.terminal.FableDiagnostics;
import com.gph.fable.core.adapter.CoreAdapter;

/**
 * CoreAdapter 的新路径实现：fable-render（libghostty-vt 核心 + Rust wgpu 渲染器，
 * 工单 08-14 产物）。所有调用经 RenderCore JNI 投递到每会话独立渲染线程 mailbox
 * （工单 12：非阻塞 + 保序；selection_text / cell_size 为同步查询）。
 *
 * destroy() 之后所有调用安全忽略；会话层在 destroy 前先解除
 * {@code TerminalSession#setCoreAdapter(null)}，避免主线程排队消息打到已释放句柄。
 */
public final class FableRenderCoreAdapter implements CoreAdapter {

    private static final String LOG_TAG = "FableRenderCoreAdapter";

    private final Object mLock = new Object();

    private long mHandle;
    private int mCols;
    private int mRows;
    private Surface mSurface;
    private int mWidthPx;
    private int mHeightPx;
    private boolean mAttached;
    private float mFontSizePx;
    private boolean mPaletteActive;
    private int mFgArgb;
    private int mBgArgb;
    private int mSelectionArgb;
    private int mCursorArgb;
    private int[] mAnsiArgb;
    private Context mContext;

    public FableRenderCoreAdapter(int cols, int rows) {
        this(null, cols, rows);
    }

    public FableRenderCoreAdapter(Context context, int cols, int rows) {
        mContext = context;
        mCols = Math.max(1, cols);
        mRows = Math.max(1, rows);
        mHandle = RenderCore.rendererCreate(mCols, mRows);
        // 工单 22：assets 字体拷贝 + JNI 传路径（Rust 侧 mmap + sha256 校验）。
        FontAssets.install(context, mHandle);
        // 工单 26：rendererCreate 后**立即**开启 DECSET 2027（grapheme clustering），
        // 先于任何历史字节回放——否则回放内容按每码位算宽（合成 emoji 4-6 列），
        // 与 2027 的 2 列模型混排，造成"一行半/第二行覆盖"。
        byte[] graphemeOn = "\u001b[?2027h".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RenderCore.rendererWrite(mHandle, graphemeOn, graphemeOn.length);
    }

    public boolean isValid() {
        synchronized (mLock) {
            return mHandle != 0;
        }
    }

    @Override
    public void write(byte[] data, int len) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle != 0) RenderCore.rendererWrite(handle, data, len);
    }

    @Override
    public void resize(int columns, int rows) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mCols = Math.max(1, columns);
            mRows = Math.max(1, rows);
        }
        if (handle != 0) RenderCore.rendererResize(handle, mCols, mRows);
    }

    @Override
    public void scroll(int delta) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle != 0) RenderCore.rendererScroll(handle, delta);
    }

    @Override
    public void setSelection(int row, int startCol, int endCol) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle != 0) {
            RenderCore.rendererSetSelection(handle, Math.max(0, row), Math.max(0, startCol), Math.max(0, endCol));
        }
    }

    @Override
    public String getSelectionText() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? "" : RenderCore.rendererSelectionText(handle);
    }

    @Override
    public void setFontSize(float sizePx) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mFontSizePx = sizePx;
        }
        if (handle != 0) RenderCore.rendererSetFontSize(handle, sizePx);
    }

    @Override
    public void getCellSize(int[] out) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle == 0) {
            if (out.length >= 2) {
                out[0] = 0;
                out[1] = 0;
            }
            return;
        }
        RenderCore.rendererGetCellSize(handle, out);
    }

    @Override
    public void setPalette(int fgArgb, int bgArgb, int selectionArgb, int cursorArgb) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mPaletteActive = true;
            mFgArgb = fgArgb;
            mBgArgb = bgArgb;
            mSelectionArgb = selectionArgb;
            mCursorArgb = cursorArgb;
        }
        if (handle != 0) {
            pushPalette(handle);
        }
    }

    @Override
    public void setAnsiPalette(int[] ansiArgb) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mAnsiArgb = ansiArgb == null ? null : ansiArgb.clone();
        }
        // 只有已 push 过主配色板时才算完整配色（避免把 ANSI 单独发给未初始化状态）。
        if (handle != 0 && mPaletteActive) {
            pushPalette(handle);
        }
    }

    private void pushPalette(long handle) {
        int fgArgb;
        int bgArgb;
        int selectionArgb;
        int cursorArgb;
        int[] ansiArgb;
        synchronized (mLock) {
            fgArgb = mFgArgb;
            bgArgb = mBgArgb;
            selectionArgb = mSelectionArgb;
            cursorArgb = mCursorArgb;
            ansiArgb = mAnsiArgb;
        }
        RenderCore.rendererSetPalette16(handle, fgArgb, bgArgb, selectionArgb, cursorArgb, ansiArgb);
    }

    @Override
    public void resetPalette() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mPaletteActive = false;
        }
        if (handle != 0) RenderCore.rendererResetPalette(handle);
    }

    @Override
    public void attach(Surface surface, int widthPx, int heightPx) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mSurface = surface;
            mWidthPx = widthPx;
            mHeightPx = heightPx;
            mAttached = true;
        }
        if (handle != 0) {
            RenderCore.rendererAttach(handle, surface, widthPx, heightPx);
            FableDiagnostics.append("adapter.attach w=" + widthPx + " h=" + heightPx);
        }
    }

    @Override
    public void detach() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mAttached = false;
        }
        if (handle != 0) {
            RenderCore.rendererDetach(handle);
            FableDiagnostics.append("adapter.detach");
        }
    }

    @Override
    public void render(int widthPx, int heightPx) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mWidthPx = widthPx;
            mHeightPx = heightPx;
        }
        if (handle != 0) {
            boolean rendered = RenderCore.rendererRender(handle, widthPx, heightPx);
            if (!rendered) {
                // 渲染线程拒绝本帧（空快照/异常帧/未附着）：写诊断文件供回传。
                FableDiagnostics.append("render=false reason=" + RenderCore.rendererLastError(handle));
            }
        }
    }

    @Override
    public void forceRender(int widthPx, int heightPx) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle != 0) {
            boolean rendered = RenderCore.rendererForceRender(handle, widthPx, heightPx);
            if (!rendered) {
                FableDiagnostics.append("forceRender=false reason=" + RenderCore.rendererLastError(handle));
            }
        }
    }

    @Override
    public void reset() {
        long oldHandle;
        int cols;
        int rows;
        float fontSizePx;
        boolean paletteActive;
        int fgArgb;
        int bgArgb;
        int selectionArgb;
        int cursorArgb;
        int[] ansiArgb;
        Surface surface;
        int widthPx;
        int heightPx;
        boolean attached;
        synchronized (mLock) {
            oldHandle = mHandle;
            cols = mCols;
            rows = mRows;
            fontSizePx = mFontSizePx;
            paletteActive = mPaletteActive;
            fgArgb = mFgArgb;
            bgArgb = mBgArgb;
            selectionArgb = mSelectionArgb;
            cursorArgb = mCursorArgb;
            ansiArgb = mAnsiArgb;
            surface = mSurface;
            widthPx = mWidthPx;
            heightPx = mHeightPx;
            attached = mAttached;
        }
        if (oldHandle == 0) return;

        // 新核心没有单命令 reset API：重建渲染器句柄（旧线程 Quit+join），
        // 再恢复字号/配色板/Surface 附着。
        long newHandle = RenderCore.rendererCreate(cols, rows);
        if (newHandle == 0) return;
        FontAssets.install(mContext, newHandle);
        // 工单 22 审查修复：reset 重建渲染器（新核心状态）后重发
        // DECSET 2027（grapheme clustering），否则字素聚合静默关闭、
        // 组合 emoji 长空白复发。
        byte[] graphemeOn = "\u001b[?2027h".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RenderCore.rendererWrite(newHandle, graphemeOn, graphemeOn.length);
        if (fontSizePx > 0f) RenderCore.rendererSetFontSize(newHandle, fontSizePx);
        if (paletteActive) {
            RenderCore.rendererSetPalette16(newHandle, fgArgb, bgArgb, selectionArgb, cursorArgb, ansiArgb);
        }
        synchronized (mLock) {
            mHandle = newHandle;
        }
        RenderCore.rendererDestroy(oldHandle);
        if (attached && surface != null && surface.isValid() && widthPx > 0 && heightPx > 0) {
            RenderCore.rendererAttach(newHandle, surface, widthPx, heightPx);
        }
    }

    @Override
    public boolean getCursorPosition(int[] out) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle == 0 || out == null || out.length < 2) return false;
        int present = RenderCore.rendererGetCursor(handle, out);
        return present == 1 && out[0] >= 0 && out[1] >= 0;
    }

    @Override
    public String getTitle() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? "" : RenderCore.rendererGetTitle(handle);
    }

    @Override
    public boolean consumeTitleChanged() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererConsumeTitleChanged(handle);
    }

    @Override
    public boolean consumeBell() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererConsumeBell(handle);
    }

    @Override
    public boolean getModeAlternateScreen() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeAltScreen(handle);
    }

    @Override
    public boolean getModeMouseTracking() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeMouseTracking(handle);
    }

    @Override
    public boolean getModeCursorVisible() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeCursorVisible(handle);
    }

    @Override
    public boolean getModeCursorBlink() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeCursorBlink(handle);
    }

    @Override
    public boolean getModeCursorKeysApplication() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeCursorKeysApplication(handle);
    }

    @Override
    public boolean getModeKeypadApplication() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeKeypadApplication(handle);
    }

    @Override
    public boolean getModeBracketedPaste() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeBracketedPaste(handle);
    }

    @Override
    public void setCursorBlinkState(boolean cursorVisible) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        if (handle != 0) RenderCore.rendererSetCursorBlinkState(handle, cursorVisible);
    }

    @Override
    public int getScrollbackRows() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? 0 : RenderCore.rendererGetScrollbackRows(handle);
    }

    @Override
    public String getText(int row, int startCol, int endCol) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? "" : RenderCore.rendererGetText(handle, row, startCol, endCol);
    }

    @Override
    public int[] getWordBoundsAt(int column, int externalRow) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? null : RenderCore.rendererGetWordBoundsAt(handle, column, externalRow);
    }

    @Override
    public String getWordAt(int column, int externalRow) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? "" : RenderCore.rendererGetWordAt(handle, column, externalRow);
    }

    @Override
    public boolean getModeMouseSgr() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeMouseSgr(handle);
    }

    @Override
    public boolean getModeMouseButtonEvent() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle != 0 && RenderCore.rendererGetModeMouseButtonEvent(handle);
    }

    @Override
    public String getTranscriptText(boolean linesJoined, boolean trim) {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
        }
        return handle == 0 ? "" : RenderCore.rendererGetTranscriptText(handle, linesJoined, trim);
    }

    @Override
    public void destroy() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mHandle = 0;
            mSurface = null;
            mAttached = false;
        }
        if (handle != 0) {
            RenderCore.rendererDetach(handle);
            RenderCore.rendererDestroy(handle);
        }
    }

    @Override
    public boolean supportsSelectionText() {
        return true;
    }

    @Override
    public boolean supportsFontSize() {
        return true;
    }

    @Override
    public boolean supportsPalette() {
        return true;
    }

    @Override
    public boolean supportsScrollback() {
        return true;
    }
}
