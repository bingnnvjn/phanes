package com.gph.fable.app.terminal.adapter;

import android.view.Surface;

import com.gph.fable.app.RenderCore;
import com.gph.fable.shared.logger.Logger;
import com.gph.fable.terminal.adapter.CoreAdapter;

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

    public FableRenderCoreAdapter(int cols, int rows) {
        mCols = Math.max(1, cols);
        mRows = Math.max(1, rows);
        mHandle = RenderCore.rendererCreate(mCols, mRows);
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
        if (handle != 0) RenderCore.rendererAttach(handle, surface, widthPx, heightPx);
    }

    @Override
    public void detach() {
        long handle;
        synchronized (mLock) {
            handle = mHandle;
            mAttached = false;
        }
        if (handle != 0) RenderCore.rendererDetach(handle);
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
                // 渲染线程拒绝本帧（空快照/异常帧/未附着），logcat 用于定位选择空白。
                Logger.logError(LOG_TAG, "render skipped/failed (reason in FableRender tag)");
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
