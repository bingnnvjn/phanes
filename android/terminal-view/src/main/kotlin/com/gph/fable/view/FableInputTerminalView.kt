package com.gph.fable.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.adapter.CoreAdapter
import kotlin.math.max

class FableInputTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TerminalView(context, attrs) {
    private var lastSyncedTopRow = 0
    private var selectionSignature = Long.MIN_VALUE
    private var suppressSizeUpdateDuringAttach = false

    fun setCoreAdapter(adapter: CoreAdapter?) {
        val previousAdapter = mCoreAdapter
        if (previousAdapter != null) {
            // Clear ranges on the adapter that actually owned them before
            // replacing the view reference or resetting the cached row count.
            for (row in 0 until mRows) previousAdapter.setSelection(row, 0, 0)
        }
        mCoreAdapter = adapter
        lastSyncedTopRow = mTopRow
        selectionSignature = Long.MIN_VALUE
        if (adapter != null) {
            // CoreAdapter ownership lives on TerminalSession.  The view may be
            // detached while the session survives, so null only clears the
            // view reference and must not detach the session adapter.
            mTermSession?.setCoreAdapter(adapter)
            if (adapter.supportsFontSize()) adapter.setFontSize(mTextSize.toFloat())
            mColumns = 0
            mRows = 0
            updateSize()
        } else {
            mColumns = 0
            mRows = 0
            mTopRow = 0
            lastSyncedTopRow = 0
            scrollTo(0, 0)
        }
        invalidate()
    }
    fun getCoreAdapter() = mCoreAdapter

    override fun attachSession(session: TerminalSession?): Boolean {
        lastSyncedTopRow = 0
        selectionSignature = Long.MIN_VALUE
        suppressSizeUpdateDuringAttach = true
        return try {
            super.attachSession(session)
        } finally {
            suppressSizeUpdateDuringAttach = false
        }
    }

    override fun setTextSize(textSize: Int) {
        mTextSize = textSize
        val adapter = mCoreAdapter
        if (adapter?.supportsFontSize() == true) {
            adapter.setFontSize(mTextSize.toFloat())
            mColumns = 0
            mRows = 0
        }
        updateSize()
    }

    override fun updateSize() {
        if (suppressSizeUpdateDuringAttach) return
        val adapter = mCoreAdapter
        if (adapter == null || !adapter.supportsFontSize()) { super.updateSize(); return }
        if (width == 0 || height == 0 || mTermSession == null) return
        val cell = IntArray(2)
        adapter.getCellSize(cell)
        val cw = max(1, cell[0]); val ch = max(1, cell[1])
        val cols = max(4, width / cw); val rows = max(4, height / ch)
        mCellWidthPx = cw.toFloat(); mCellHeightPx = ch.toFloat()
        if (cols != mColumns || rows != mRows) {
            mTermSession?.updateSize(cols, rows, cw, ch)
            mColumns = cols; mRows = rows; mTopRow = 0; scrollTo(0, 0)
            mClient?.onEmulatorSet(); invalidate()
        }
    }

    override fun getCoreSelectionText() =
        mCoreAdapter?.takeIf { it.supportsSelectionText() }?.selectionText

    override fun stopTextSelectionMode() {
        val wasSelecting = isSelectingText()
        super.stopTextSelectionMode()
        // TerminalView deliberately debounces hide() for 300 ms after a long
        // press.  Keep the core range alive while handles/ActionMode remain
        // active; clear it only after selection actually ended.
        if (wasSelecting && !isSelectingText()) {
            selectionSignature = Long.MIN_VALUE
            clearSelectionOverlays()
            invalidate()
        }
    }

    override fun syncSelectionToCore() {
        syncAdapterScroll()
        super.syncSelectionToCore()
    }

    override fun onDraw(canvas: Canvas) {
        if (mCoreAdapter == null) { super.onDraw(canvas); return }
        if (mTermSession == null) { canvas.drawColor(0xff000000.toInt()); return }
        renderTextSelection()
        syncAdapterState()
    }

    private fun colWidth() = if (mColumns == 0) 0f else width.toFloat() / mColumns
    private fun rowHeight() = if (mRows == 0) 0f else height.toFloat() / mRows
    private fun usingGrid() = mCoreAdapter != null && mColumns > 0
    private fun screenCol(x: Float) = if (colWidth() <= 0) 0 else (x / colWidth()).toInt()
    private fun screenRow(y: Float) = if (rowHeight() <= 0) 0 else (y / rowHeight()).toInt()
    private fun pixelX(col: Int) = (col * colWidth()).toInt()
    private fun pixelY(row: Int) = ((row - mTopRow) * rowHeight()).toInt()

    override fun getCursorX(x: Float) = if (usingGrid()) screenCol(x) else super.getCursorX(x)
    override fun getCursorY(y: Float) = if (usingGrid()) screenRow(y) + mTopRow else super.getCursorY(y)
    override fun getPointX(cx: Int) = if (!usingGrid()) super.getPointX(cx) else pixelX(cx.coerceIn(0, mColumns))
    override fun getPointY(cy: Int) = if (usingGrid()) pixelY(cy) else super.getPointY(cy)
    override fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        if (!usingGrid()) return super.getColumnAndRow(event, relativeToScroll)
        val row = screenRow(event.y) + if (relativeToScroll) mTopRow else 0
        return intArrayOf(screenCol(event.x), row)
    }

    private fun syncAdapterState() {
        val adapter = mCoreAdapter ?: return
        syncAdapterScroll()
        val selectors = IntArray(4)
        getSelectionSelectors(selectors)
        val active = isSelectingText()
        // Selection rows are viewport-relative in CoreAdapter.  A scroll while
        // selection is active changes that coordinate frame even when the
        // handles themselves did not move, so include the viewport in the
        // deduplication key and re-push the overlay ranges after scrolling.
        var signature = if (active) 1L else 0L
        signature = signature * 31 + mTopRow
        selectors.forEach { signature = signature * 31 + it }
        if (signature != selectionSignature) {
            selectionSignature = signature
            syncSelectionToCore()
        }
        if (width > 0 && height > 0) adapter.render(width, height)
    }

    private fun syncAdapterScroll() {
        val adapter = mCoreAdapter ?: return
        if (adapter.supportsScrollback() && mTopRow != lastSyncedTopRow) {
            adapter.scroll(mTopRow - lastSyncedTopRow)
            lastSyncedTopRow = mTopRow
        }
    }

    override fun isOpaque() = false
}
