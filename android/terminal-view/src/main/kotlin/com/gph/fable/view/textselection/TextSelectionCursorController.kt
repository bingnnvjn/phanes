package com.gph.fable.view.textselection

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import com.gph.fable.view.TerminalView
import kotlin.math.roundToInt

/**
 * 文本选择控制器：手柄 + 自绘浮条（工单 39）。
 *
 * 不再使用系统 ActionMode：其悬浮形态是本 ROM 上「选择空白」的根因
 * （工单 04 第十二轮），其非悬浮形态又只能给出顶部系统菜单，拿不到目标观感。
 * 选择生命周期、浮条与手柄都由本控制器自管；浮条外观见 [TextSelectionBarStyleProvider]。
 */
open class TextSelectionCursorController(
    private val terminalView: TerminalView,
    styleProvider: TextSelectionBarStyleProvider = DefaultTextSelectionBarStyleProvider,
    handleStyleProvider: TextSelectionHandleStyleProvider = DefaultTextSelectionHandleStyleProvider
) : CursorController {

    private val handleStyle = handleStyleProvider.resolve(terminalView.context)
    private val startHandle =
        TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT, handleStyle)
    private val endHandle =
        TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT, handleStyle)
    private val actionBar = TextSelectionActionBar(terminalView, styleProvider)
    private val actionRunner = TextSelectionActionRunner(
        selectedText = { terminalView.getSelectedText() },
        copyToClipboard = { terminalView.mTermSession?.onCopyTextToClipboard(it) },
        stopSelection = { terminalView.forceStopTextSelectionMode() },
        pasteFromClipboard = { terminalView.mTermSession?.onPasteTextFromClipboard() }
    )

    private var isSelecting = false
    private var showStartTime = 0L
    private var storedText: String? = null
    private var selX1 = -1
    private var selX2 = -1
    private var selY1 = -1
    private var selY2 = -1

    open val selectedText: String?
        get() = terminalView.getCoreSelectionText()?.takeIf { it.isNotEmpty() }

    open val storedSelectedText: String?
        get() = storedText

    init {
        actionBar.onAction = { actionRunner.run(it) }
    }

    override open fun show(event: MotionEvent) {
        setInitialTextSelectionPosition(event)
        startHandle.positionAtCursor(selX1, selY1, true)
        endHandle.positionAtCursor(selX2 + 1, selY2, true)
        showStartTime = System.currentTimeMillis()
        isSelecting = true
        updateCoreSelection()
        showActionBar()
        TerminalView.logDiagnostic("selection:started")
    }

    override open fun hide(): Boolean {
        if (!isSelecting) return false
        if (System.currentTimeMillis() - showStartTime < 300) return false
        return hideImmediately()
    }

    fun forceHide() {
        if (isSelecting) hideImmediately()
    }

    private fun hideImmediately(): Boolean {
        startHandle.hide()
        endHandle.hide()
        actionBar.hide()
        selX1 = -1; selX2 = -1; selY1 = -1; selY2 = -1
        isSelecting = false
        terminalView.clearSelectionOverlays()
        return true
    }

    override open fun render() {
        if (!isSelecting) return
        startHandle.positionAtCursor(selX1, selY1, false)
        endHandle.positionAtCursor(selX2 + 1, selY2, false)
        actionBar.moveTo(selectionAnchor())
    }

    /** 手柄拖动期间收起浮条，松手后按当前选区重新显示。 */
    override open fun onHandleDragStart() {
        actionBar.hide()
    }

    override open fun onHandleDragEnd() {
        if (isSelecting) showActionBar()
    }

    /** 打开上下文菜单前留存当前选中文本（供「分享选中文本」入口使用）。 */
    open fun storeSelectedText() {
        storedText = terminalView.getSelectedText()
    }

    open fun setInitialTextSelectionPosition(event: MotionEvent) {
        val point = terminalView.getColumnAndRow(event, true)
        selX1 = point[0]; selX2 = point[0]
        selY1 = point[1]; selY2 = point[1]
        terminalView.getWordBoundsAt(selX1, selY1)?.let {
            selX1 = it[0]
            selX2 = maxOf(it[0], it[1] - 1)
        }
    }

    /** 选区在终端视图坐标系里的像素矩形（供浮条定位）。 */
    private fun selectionAnchor(): Rect {
        var x1 = (selX1 * terminalView.getCellWidthPx()).roundToInt()
        var x2 = (selX2 * terminalView.getCellWidthPx()).roundToInt()
        if (x1 > x2) {
            val tmp = x1
            x1 = x2
            x2 = tmp
        }
        val y1 = ((selY1 - terminalView.getTopRow()) * terminalView.getCellHeightPx()).roundToInt()
        val y2 = ((selY2 + 1 - terminalView.getTopRow()) * terminalView.getCellHeightPx()).roundToInt()
        return Rect(x1, y1, x2, y2)
    }

    private fun showActionBar() {
        actionBar.show(selectionAnchor(), hasClipboardText())
    }

    private fun hasClipboardText(): Boolean {
        val clipboard =
            terminalView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return clipboard?.hasPrimaryClip() == true
    }

    override open fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int) {
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
        terminalView.syncSelectionToCore()
    }

    override open fun onTouchEvent(event: MotionEvent) = isSelecting
    override open fun onDetached() { forceHide() }
    override open fun isActive() = isSelecting
    override open fun onTouchModeChanged(isInTouchMode: Boolean) { if (!isInTouchMode) terminalView.stopTextSelectionMode() }

    open fun getSelectors(out: IntArray) {
        if (out.size >= 4) { out[0] = selY1; out[1] = selY2; out[2] = selX1; out[3] = selX2 }
    }
    open fun unsetStoredSelectedText() { storedText = null }
    open fun decrementY(decrement: Int) { if (isSelecting) { selY1 -= decrement; selY2 -= decrement } }

    open fun decrementYTextSelectionCursors(decrement: Int) {
        decrementY(decrement)
    }

    open fun isSelectionStartDragged() = startHandle.isDragging()
    open fun isSelectionEndDragged() = endHandle.isDragging()
}
