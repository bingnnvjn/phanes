package com.gph.fable.view.textselection

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.gph.fable.view.R
import com.gph.fable.view.TerminalView

class TextSelectionCursorController(private val terminalView: TerminalView) : CursorController {
    companion object {
        const val ACTION_COPY = 1
        const val ACTION_PASTE = 2
        const val ACTION_MORE = 3
    }

    private val startHandle = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT)
    private val endHandle = TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT)
    private var isSelecting = false
    private var showStartTime = 0L
    private var storedText: String? = null
    private var selX1 = -1
    private var selX2 = -1
    private var selY1 = -1
    private var selY2 = -1
    var actionMode: ActionMode? = null
        private set
    private var privateActionMode: ActionMode? = null

    val selectedText: String?
        get() = terminalView.getCoreSelectionText()?.takeIf { it.isNotEmpty() }

    val storedSelectedText: String?
        get() = storedText

    override fun show(event: MotionEvent) {
        setInitialTextSelectionPosition(event)
        startHandle.positionAtCursor(selX1, selY1, true)
        endHandle.positionAtCursor(selX2 + 1, selY2, true)
        setActionModeCallbacks()
        showStartTime = System.currentTimeMillis()
        isSelecting = true
        updateCoreSelection()
        TerminalView.logDiagnostic("selection:actionModeStarted")
    }

    override fun hide(): Boolean {
        if (!isSelecting) return false
        if (System.currentTimeMillis() - showStartTime < 300) return false
        startHandle.hide()
        endHandle.hide()
        privateActionMode?.finish()
        privateActionMode = null
        actionMode = null
        selX1 = -1; selX2 = -1; selY1 = -1; selY2 = -1
        isSelecting = false
        terminalView.clearSelectionOverlays()
        return true
    }

    override fun render() {
        if (!isSelecting) return
        startHandle.positionAtCursor(selX1, selY1, false)
        endHandle.positionAtCursor(selX2 + 1, selY2, false)
        privateActionMode?.invalidate()
    }

    private fun setInitialTextSelectionPosition(event: MotionEvent) {
        val point = terminalView.getColumnAndRow(event, true)
        selX1 = point[0]; selX2 = point[0]
        selY1 = point[1]; selY2 = point[1]
        terminalView.getWordBoundsAt(selX1, selY1)?.let {
            selX1 = it[0]
            selX2 = maxOf(it[0], it[1] - 1)
        }
    }

    private fun setActionModeCallbacks() {
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val show = MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT
                val clipboard = terminalView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text)
                    .setEnabled(clipboard?.hasPrimaryClip() == true).setShowAsAction(show)
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, R.string.text_selection_more)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (!isSelecting) return true
                when (item.itemId) {
                    ACTION_COPY -> {
                        terminalView.mTermSession?.onCopyTextToClipboard(terminalView.getSelectedText() ?: "")
                        terminalView.stopTextSelectionMode()
                    }
                    ACTION_PASTE -> {
                        terminalView.stopTextSelectionMode()
                        terminalView.mTermSession?.onPasteTextFromClipboard()
                    }
                    ACTION_MORE -> {
                        storedText = terminalView.getSelectedText()
                        terminalView.stopTextSelectionMode()
                        terminalView.showContextMenu()
                    }
                }
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        privateActionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            terminalView.startActionMode(object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu) = callback.onCreateActionMode(mode, menu)
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = callback.onActionItemClicked(mode, item)
                override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)
                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    val x1 = (selX1 * terminalView.getCellWidthPx()).toInt()
                    val x2 = (selX2 * terminalView.getCellWidthPx()).toInt()
                    val y1 = ((selY1 - 1 - terminalView.getTopRow()) * terminalView.getCellHeightPx()).toInt()
                    val y2 = ((selY2 + 1 - terminalView.getTopRow()) * terminalView.getCellHeightPx()).toInt()
                    outRect.set(minOf(x1, x2), maxOf(0, y1), maxOf(x1, x2), minOf(terminalView.height, y2))
                }
            }, ActionMode.TYPE_PRIMARY)
        } else terminalView.startActionMode(callback)
        actionMode = privateActionMode
    }

    override fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 0)
        val point = terminalView.getColumnAndRow(event, true)
        event.recycle()
        val scrollback = terminalView.getScrollbackRows()
        if (handle === startHandle) {
            selX1 = maxOf(0, point[0])
            selY1 = point[1].coerceIn(-scrollback, terminalView.getRows() - 1)
            if (selY1 > selY2) selY1 = selY2
            if (selY1 == selY2 && selX1 > selX2) selX1 = selX2
            adjustViewport(selY1)
            selX1 = validCursorX(selY1, selX1)
        } else {
            selX2 = maxOf(0, point[0])
            selY2 = point[1].coerceIn(-scrollback, terminalView.getRows() - 1)
            if (selY1 > selY2) selY2 = selY1
            if (selY1 == selY2 && selX1 > selX2) selX2 = selX1
            adjustViewport(selY2)
            selX2 = validCursorX(selY2, selX2)
        }
        updateCoreSelection()
        terminalView.invalidate()
    }

    private fun adjustViewport(y: Int) {
        if (terminalView.isAlternateBufferActive()) return
        val scrollback = terminalView.getScrollbackRows()
        var top = terminalView.getTopRow()
        if (y <= top) top = maxOf(-scrollback, top - 1)
        else if (y >= top + terminalView.getRows()) top = minOf(0, top + 1)
        terminalView.setTopRow(top)
    }

    private fun validCursorX(row: Int, col: Int): Int {
        if (col <= 0) return col
        if (terminalView.getCoreText(row, col, col + 1).isNotEmpty()) return col
        return if (terminalView.getCoreText(row, col - 1, col).isNotEmpty()) col + 1 else col
    }

    private fun updateCoreSelection() {
        val range = com.gph.fable.view.TerminalViewInteractionLogic.normalizeSelection(selY1, selX1, selY2, selX2)
        terminalView.mCoreAdapter?.setSelection(
            range.startRow - terminalView.getTopRow(),
            range.startCol,
            range.endCol + 1
        )
    }

    override fun onTouchEvent(event: MotionEvent) = isSelecting
    override fun onDetached() { hide() }
    override fun isActive() = isSelecting
    override fun onTouchModeChanged(isInTouchMode: Boolean) { if (!isInTouchMode) terminalView.stopTextSelectionMode() }

    fun getSelectors(out: IntArray) {
        if (out.size >= 4) { out[0] = selY1; out[1] = selY2; out[2] = selX1; out[3] = selX2 }
    }
    fun unsetStoredSelectedText() { storedText = null }
    fun decrementY(decrement: Int) { if (isSelecting) { selY1 -= decrement; selY2 -= decrement } }
}
