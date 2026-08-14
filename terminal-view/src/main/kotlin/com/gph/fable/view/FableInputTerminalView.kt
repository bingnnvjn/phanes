package com.gph.fable.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
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

    fun setCoreAdapter(adapter: CoreAdapter?) {
        mCoreAdapter = adapter
        lastSyncedTopRow = mTopRow
        selectionSignature = Long.MIN_VALUE
        if (adapter != null) {
            if (adapter.supportsFontSize()) adapter.setFontSize(mTextSize.toFloat())
            clearSelectionOverlays()
            updateSize()
        }
        mTermSession?.setCoreAdapter(adapter)
        invalidate()
    }
    fun getCoreAdapter() = mCoreAdapter

    override fun attachSession(session: TerminalSession?): Boolean {
        lastSyncedTopRow = 0
        selectionSignature = Long.MIN_VALUE
        return super.attachSession(session)
    }

    override fun setTextSize(textSize: Int) {
        super.setTextSize(textSize)
        mCoreAdapter?.takeIf { it.supportsFontSize() }?.let { it.setFontSize(mTextSize.toFloat()); updateSize() }
    }

    override fun updateSize() {
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
        if (adapter.supportsScrollback() && mTopRow != lastSyncedTopRow) {
            adapter.scroll(mTopRow - lastSyncedTopRow)
            lastSyncedTopRow = mTopRow
        }
        val selectors = IntArray(4)
        getSelectionSelectors(selectors)
        val active = isSelectingText()
        var signature = if (active) 1L else 0L
        selectors.forEach { signature = signature * 31 + it }
        if (signature != selectionSignature) {
            selectionSignature = signature
            if (active) {
                val y1 = selectors[0] - mTopRow
                val y2 = selectors[1] - mTopRow
                for (row in 0 until mRows) {
                    if (row in y1..y2) {
                        val start = if (row == y1) selectors[2] else 0
                        val end = if (row == y2) selectors[3] + 1 else mColumns
                        adapter.setSelection(row, start.coerceAtLeast(0), end.coerceIn(0, mColumns))
                    } else adapter.setSelection(row, 0, 0)
                }
            } else clearSelectionOverlays()
        }
        if (width > 0 && height > 0) adapter.render(width, height)
    }

    override fun isOpaque() = false
}
