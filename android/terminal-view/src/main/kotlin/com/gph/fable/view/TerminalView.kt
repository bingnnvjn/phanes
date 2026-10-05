package com.gph.fable.view

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Scroller
import androidx.annotation.RequiresApi
import com.gph.fable.core.KeyHandler
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.adapter.CoreAdapter
import com.gph.fable.view.textselection.DefaultTextSelectionBarStyleProvider
import com.gph.fable.view.textselection.DefaultTextSelectionHandleStyleProvider
import com.gph.fable.view.textselection.TextSelectionBarStyleProvider
import com.gph.fable.view.textselection.TextSelectionCursorController
import com.gph.fable.view.textselection.TextSelectionHandleStyleProvider
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
open class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    companion object {
        @Volatile private var diagnosticListener: Consumer<String>? = null
        @Volatile private var keyLoggingEnabled = false
        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000
        const val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0

        @JvmStatic fun setDiagnosticListener(listener: Consumer<String>?) { diagnosticListener = listener }
        @JvmStatic fun logDiagnostic(message: String) { diagnosticListener?.accept(message) }
    }

    @JvmField var mTermSession: TerminalSession? = null
    @JvmField var mClient: TerminalViewClient? = null
    @JvmField var mCoreAdapter: CoreAdapter? = null
    @JvmField var mColumns = 0
    @JvmField var mRows = 0
    @JvmField var mTopRow = 0
    @JvmField var mTextSize = 0
    @JvmField var mCellWidthPx = 12f
    @JvmField var mCellHeightPx = 20f
    @JvmField var mScaleFactor = 1f

    private var typeface = Typeface.MONOSPACE
    private var autoScrollDisabled = false
    private var cursorBlinkRate = 0
    private var cursorBlinkVisible = true
    private var combiningAccent = 0
    private var mouseScrollStartX = -1
    private var mouseScrollStartY = -1
    private var mouseStartDownTime = -1L
    @RequiresApi(Build.VERSION_CODES.O)
    private var autoFillType = AUTOFILL_TYPE_NONE
    @RequiresApi(Build.VERSION_CODES.O)
    private var autoFillImportance = IMPORTANT_FOR_AUTOFILL_NO
    private var autoFillHints = emptyArray<String>()
    private var selectionController: TextSelectionCursorController? = null

    /** 选择浮条的样式来源；替换它即可整体换肤（见 [TextSelectionBarStyleProvider]）。 */
    var textSelectionBarStyleProvider: TextSelectionBarStyleProvider = DefaultTextSelectionBarStyleProvider

    /** 选择手柄的样式来源（见 [TextSelectionHandleStyleProvider]）。 */
    var textSelectionHandleStyleProvider: TextSelectionHandleStyleProvider = DefaultTextSelectionHandleStyleProvider
    private val scroller = Scroller(context)
    private val handler = Handler(Looper.getMainLooper())
    private var scrollRemainder = 0f
    private val accessibilityEnabled =
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isEnabled == true
    private val gestureRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {
        private var scrolled = false
        override fun onDown(x: Float, y: Float) = false
        override fun onUp(e: MotionEvent): Boolean {
            // A fractional scroll remainder belongs to the completed gesture,
            // including a quick mouse-tracking click that returns early below.
            scrollRemainder = 0f
            if (mTermSession != null && isMouseTrackingActive() &&
                !e.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolled
            ) {
                sendMouseEventCode(e, CoreAdapter.MOUSE_LEFT_BUTTON, true)
                sendMouseEventCode(e, CoreAdapter.MOUSE_LEFT_BUTTON, false)
                scrolled = false
                return true
            }
            scrolled = false
            return false
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isSelectingText()) { stopTextSelectionMode(); return true }
            requestFocus()
            mClient?.onSingleTapUp(e)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            return false
        }
        override fun onScroll(e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mTermSession == null) return true
            if (TerminalViewInteractionLogic.shouldReportMouseMove(isMouseTrackingActive(), e2.isFromSource(InputDevice.SOURCE_MOUSE))) {
                sendMouseEventCode(e2, CoreAdapter.MOUSE_LEFT_BUTTON_MOVED, true)
            } else {
                scrolled = true
                val delta = TerminalViewInteractionLogic.scrollRows(dy, mCellHeightPx, scrollRemainder)
                scrollRemainder = delta.remainderPx
                doScroll(e2, delta.rows)
            }
            return true
        }
        override fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (mTermSession == null || !scroller.isFinished) return true
            val mouseTrackingAtStart = isMouseTrackingActive()
            if (mouseTrackingAtStart) {
                scroller.fling(0, 0, 0, (-velocityY * .25f).toInt(), 0, 0, -mRows / 2, mRows / 2)
            } else {
                scroller.fling(0, mTopRow, 0, (-velocityY * .25f).toInt(), 0, 0, -getScrollbackRows(), 0)
            }
            post(object : Runnable {
                private var lastY = 0
                override fun run() {
                    if (mouseTrackingAtStart != isMouseTrackingActive()) {
                        scroller.abortAnimation()
                        return
                    }
                    if (scroller.isFinished) return
                    scroller.computeScrollOffset()
                    val y = scroller.currY
                    val diff = if (mouseTrackingAtStart) y - lastY else y - mTopRow
                    doScroll(e, diff)
                    lastY = y
                    post(this)
                }
            })
            return true
        }
        override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
            if (isSelectingText()) return true
            mScaleFactor = mClient?.onScale(mScaleFactor * scale) ?: (mScaleFactor * scale)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (mClient?.onLongPress(e) == true) return
            if (!isSelectingText()) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startTextSelectionMode(e)
            }
        }
    })

    init {
        isFocusableInTouchMode = true
        setWillNotDraw(false)
        mTextSize = 12
    }

    open fun setTerminalViewClient(client: TerminalViewClient?) { mClient = client }
    open fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) { keyLoggingEnabled = value }
    open fun isCoreAdapterActive() = mCoreAdapter != null
    open fun isMouseTrackingActive() = mTermSession?.isMouseTrackingActive == true
    open fun isAlternateBufferActive() = mTermSession?.isAlternateBufferActive == true
    open fun isCursorEnabled() = mTermSession?.isCursorEnabled != false
    open fun isCursorKeysApplicationMode() = mTermSession?.isCursorKeysApplicationMode == true
    open fun isKeypadApplicationMode() = mTermSession?.isKeypadApplicationMode == true
    open fun isAutoScrollDisabled() = autoScrollDisabled

    open fun attachSession(session: TerminalSession?): Boolean {
        if (mTermSession === session) return false
        if (isSelectingText()) {
            selectionController?.forceHide()
            mClient?.copyModeChanged(false)
        }
        mTermSession = session
        mTopRow = 0
        mColumns = 0
        mRows = 0
        combiningAccent = 0
        scrollRemainder = 0f
        if (session != null) {
            updateSize()
        }
        setVerticalScrollBarEnabled(session != null)
        invalidate()
        return true
    }

    override open fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val terminalSelected = mClient?.isTerminalViewSelected() ?: true
        outAttrs.inputType = if (!terminalSelected) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        } else if (mClient?.shouldEnforceCharBasedInput() == true) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_NULL
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            private fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()
                val translated = TerminalViewInteractionLogic.translateImeText(
                    text,
                    mClient?.readShiftKey() == true
                )
                translated.forEach {
                    inputCodePoint(
                        KEY_EVENT_SOURCE_SOFT_KEYBOARD,
                        it.codePoint,
                        it.ctrlDown,
                        false
                    )
                }
            }

            override fun finishComposingText(): Boolean {
                if (keyLoggingEnabled) mClient?.logInfo("TerminalView", "IME: finishComposingText()")
                super.finishComposingText()
                val content = editable ?: return true
                sendTextToTerminal(content)
                content.clear()
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (keyLoggingEnabled) {
                    mClient?.logInfo("TerminalView", "IME: commitText(\"$text\", $newCursorPosition)")
                }
                super.commitText(text, newCursorPosition)
                val content = editable ?: return true
                if (mTermSession == null) {
                    // Do not carry an IME buffer across a session attach.
                    content.clear()
                    return true
                }
                sendTextToTerminal(content)
                content.clear()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event) else onKeyUp(event.keyCode, event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (keyLoggingEnabled) {
                    mClient?.logInfo(
                        "TerminalView",
                        "IME: deleteSurroundingText($beforeLength, $afterLength)"
                    )
                }
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                repeat(beforeLength.coerceAtLeast(0)) { sendKeyEvent(deleteKey) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    override open fun onCheckIsTextEditor() = true
    @SuppressLint("ClickableViewAccessibility")
    override open fun onTouchEvent(event: MotionEvent): Boolean {
        val session = mTermSession ?: return true
        if (isSelectingText()) {
            gestureRecognizer.onTouchEvent(event)
            return true
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val item = clipboard?.primaryClip?.getItemAt(0)
                    val text = item?.coerceToText(context)?.toString()
                    if (!text.isNullOrEmpty()) session.paste(text)
                }
            } else if (isMouseTrackingActive()) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP ->
                        sendMouseEventCode(event, CoreAdapter.MOUSE_LEFT_BUTTON, event.actionMasked == MotionEvent.ACTION_DOWN)
                    MotionEvent.ACTION_MOVE ->
                        sendMouseEventCode(event, CoreAdapter.MOUSE_LEFT_BUTTON_MOVED, true)
                }
            }
        }
        gestureRecognizer.onTouchEvent(event)
        return true
    }
    override open fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mTermSession != null && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return false
    }

    override open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val session = mTermSession ?: return true
        if (keyLoggingEnabled) mClient?.logInfo("TerminalView", "onKeyDown(keyCode=$keyCode, event=$event)")
        if (isSelectingText()) {
            forceStopTextSelectionMode()
            // 没有 ActionMode 兜底后，BACK 仍只收起选择，不退出 Activity。
            if (keyCode == KeyEvent.KEYCODE_BACK) return true
        }
        if (mClient?.onKeyDown(keyCode, event, session) == true) {
            invalidate()
            return true
        }
        if (event.isSystem() && (!mClient?.shouldBackButtonBeMappedToEscape().orFalse() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            session.write(event.characters ?: "")
            return true
        }
        val controlDown = event.isCtrlPressed || mClient?.readControlKey().orFalse()
        val leftAltDown = (event.metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || mClient?.readAltKey().orFalse()
        val shiftDown = event.isShiftPressed || mClient?.readShiftKey().orFalse()
        val rightAlt = (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0
        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) return true
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (!rightAlt) bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        var effectiveMeta = event.metaState and bitsToClear.inv()
        if (shiftDown) effectiveMeta = effectiveMeta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (mClient?.readFnKey().orFalse()) effectiveMeta = effectiveMeta or KeyEvent.META_FUNCTION_ON
        var result = event.getUnicodeChar(effectiveMeta)
        if (result == 0) return false
        val oldAccent = combiningAccent
        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            if (combiningAccent != 0) inputCodePoint(event.deviceId, combiningAccent, controlDown, leftAltDown)
            combiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (combiningAccent != 0) {
                val combined = KeyCharacterMap.getDeadChar(combiningAccent, result)
                if (combined > 0) result = combined
                combiningAccent = 0
            }
            inputCodePoint(event.deviceId, result, controlDown, leftAltDown)
        }
        if (combiningAccent != oldAccent) invalidate()
        return true
    }

    override open fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyLoggingEnabled) mClient?.logInfo("TerminalView", "onKeyUp(keyCode=$keyCode, event=$event)")
        if (mTermSession == null && keyCode != KeyEvent.KEYCODE_BACK) return true
        if (mClient?.onKeyUp(keyCode, event) == true) {
            invalidate()
            return true
        }
        if (event.isSystem) return super.onKeyUp(keyCode, event)
        return true
    }

    override open fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyLoggingEnabled) mClient?.logInfo("TerminalView", "onKeyPreIme(keyCode=$keyCode, event=$event)")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill()
            if (isSelectingText()) {
                stopTextSelectionMode()
                return true
            }
            if (mClient?.shouldBackButtonBeMappedToEscape() == true) {
                return if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(keyCode, event) else onKeyUp(keyCode, event)
            }
        } else if (keyCode == KeyEvent.KEYCODE_SPACE &&
            event.isCtrlPressed && mClient?.shouldUseCtrlSpaceWorkaround() == true
        ) {
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    open fun inputCodePoint(eventSource: Int, codePoint: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        val session = mTermSession ?: return
        if (keyLoggingEnabled) {
            mClient?.logInfo(
                "TerminalView",
                "inputCodePoint(eventSource=$eventSource, codePoint=$codePoint, " +
                    "controlDownFromEvent=$controlDownFromEvent, leftAltDownFromEvent=$leftAltDownFromEvent)"
            )
        }
        setCursorBlinkPhase(true)
        val controlDown = controlDownFromEvent || mClient?.readControlKey().orFalse()
        val altDown = leftAltDownFromEvent || mClient?.readAltKey().orFalse()
        if (mClient?.onCodePoint(codePoint, controlDown, session) == true) return
        var value = TerminalViewInteractionLogic.translateControlCode(codePoint, controlDown)
        if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
            value = when (value) {
                0x02DC -> 0x007E
                0x02CB -> 0x0060
                0x02C6 -> 0x005E
                else -> value
            }
        }
        if (value > -1) session.writeCodePoint(altDown, value)
    }

    open fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        setCursorBlinkPhase(true)
        if (handleKeyCodeAction(keyCode, keyMod)) return true
        val code = KeyHandler.getCode(keyCode, keyMod, isCursorKeysApplicationMode(), isKeypadApplicationMode()) ?: return false
        mTermSession?.write(code)
        return true
    }
    open fun handleKeyCodeAction(keyCode: Int, keyMod: Int): Boolean {
        if (keyMod and KeyHandler.KEYMOD_SHIFT != 0 &&
            (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)
        ) {
            val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            doScroll(event, if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -mRows else mRows)
            event.recycle()
            return true
        }
        return false
    }

    open fun sendMouseEventCode(event: MotionEvent, button: Int, pressed: Boolean) {
        val session = mTermSession ?: return
        val point = getColumnAndRow(event, false)
        var x = point[0] + 1
        var y = point[1] + 1
        if (pressed && (button == CoreAdapter.MOUSE_WHEELDOWN_BUTTON || button == CoreAdapter.MOUSE_WHEELUP_BUTTON)) {
            if (mouseStartDownTime == event.downTime) {
                x = mouseScrollStartX
                y = mouseScrollStartY
            } else {
                mouseStartDownTime = event.downTime
                mouseScrollStartX = x
                mouseScrollStartY = y
            }
        }
        session.sendMouseEvent(button, x, y, pressed)
    }

    override open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updateSize() }
    open fun updateSize() {
        if (width <= 0 || height <= 0 || mTermSession == null) return
        val cw = max(1, mCellWidthPx.toInt())
        val ch = max(1, mCellHeightPx.toInt())
        val cols = max(4, width / cw)
        val rows = max(4, height / ch)
        if (cols != mColumns || rows != mRows) {
            mColumns = cols; mRows = rows
            mTermSession?.updateSize(cols, rows, cw, ch)
            mClient?.onEmulatorSet()
        }
    }
    override open fun onDraw(canvas: Canvas) { super.onDraw(canvas); renderTextSelection() }
    override open fun computeVerticalScrollRange() = max(1, mRows + getScrollbackRows())
    override open fun computeVerticalScrollExtent() = max(1, mRows)
    override open fun computeVerticalScrollOffset() = if (mTermSession == null) 1 else getScrollbackRows() + mTopRow
    open fun onScreenUpdated() { onScreenUpdated(false) }
    open fun onScreenUpdated(skipScrolling: Boolean) {
        val session = mTermSession ?: return
        session.pollUiEvents()
        val history = getScrollbackRows()
        if (mTopRow < -history) mTopRow = -history
        var skip = skipScrolling
        if (isSelectingText() || isAutoScrollDisabled()) {
            if (-mTopRow > history) {
                if (isSelectingText()) stopTextSelectionMode()
                if (isAutoScrollDisabled()) {
                    mTopRow = -history
                    skip = true
                }
            } else {
                skip = true
            }
        }
        if (!skip && mTopRow != 0) {
            if (mTopRow < -3) awakenScrollBars()
            mTopRow = 0
        }
        if (accessibilityEnabled) setContentDescription(getText())
        invalidate()
    }
    private fun getText(): CharSequence {
        val adapter = mCoreAdapter
        if (adapter == null || mTermSession == null || mRows <= 0) return ""
        return buildString {
            for (row in 0 until mRows) {
                if (row > 0) append('\n')
                append(adapter.getText(mTopRow + row, 0, mColumns))
            }
        }
    }
    open fun getCurrentSession() = mTermSession
    open fun getCursorX(x: Float) =
        if (mColumns == 0) 0 else (x / (width.toFloat() / mColumns)).toInt()
    open fun getCursorY(y: Float) =
        if (mRows == 0) 0 else (y / (height.toFloat() / mRows)).toInt() + mTopRow
    open fun getPointX(cx: Int) =
        (cx.coerceIn(0, max(0, mColumns)) * width.toFloat() / max(1, mColumns)).roundToInt()
    open fun getPointY(cy: Int) =
        ((cy - mTopRow) * height.toFloat() / max(1, mRows)).roundToInt()
    open fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray =
        intArrayOf(getCursorX(event.x), getCursorY(event.y).let { if (relativeToScroll) it else it - mTopRow })
    open fun getTopRow() = mTopRow
    open fun setTopRow(value: Int) {
        mTopRow = TerminalViewInteractionLogic.clampTopRow(value, getScrollbackRows())
        invalidate()
    }
    open fun getRows() = mRows
    open fun getColumns() = mColumns
    open fun getCellWidthPx() = mCellWidthPx
    open fun getCellHeightPx() = mCellHeightPx
    open fun getScrollbackRows() = mCoreAdapter?.getScrollbackRows() ?: 0
    open fun getCoreText(row: Int, startCol: Int, endCol: Int) = mCoreAdapter?.getText(row, startCol, endCol) ?: ""
    open fun getWordBoundsAt(column: Int, row: Int) = mCoreAdapter?.getWordBoundsAt(column, row)
    open fun toggleAutoScrollDisabled() { autoScrollDisabled = !autoScrollDisabled }
    open fun setTypeface(newTypeface: Typeface?) {
        typeface = newTypeface ?: Typeface.MONOSPACE
        updateSize()
        invalidate()
    }
    open fun setTextSize(textSize: Int) { mTextSize = textSize; updateSize() }
    override open fun isOpaque() = true

    private fun doScroll(event: MotionEvent?, rowsDown: Int) {
        if (rowsDown == 0) return
        val motion = event ?: MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        val up = rowsDown < 0
        repeat(kotlin.math.abs(rowsDown)) {
            when (TerminalViewInteractionLogic.scrollDestination(isMouseTrackingActive(), isAlternateBufferActive())) {
                TerminalViewInteractionLogic.ScrollDestination.MOUSE_WHEEL ->
                    sendMouseEventCode(
                        motion,
                        TerminalViewInteractionLogic.mouseWheelButton(
                            rowsDown,
                            CoreAdapter.MOUSE_WHEELUP_BUTTON,
                            CoreAdapter.MOUSE_WHEELDOWN_BUTTON
                        ),
                        true
                    )
                TerminalViewInteractionLogic.ScrollDestination.ALTERNATE_BUFFER_KEYS ->
                    handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
                TerminalViewInteractionLogic.ScrollDestination.SCROLLBACK -> {
                    setTopRow(mTopRow + if (up) -1 else 1)
                    if (!awakenScrollBars()) invalidate()
                }
            }
        }
        if (event == null) motion.recycle()
    }

    open fun getCoreSelectionText() = mCoreAdapter?.takeIf { it.supportsSelectionText() }?.selectionText
    open fun isSelectingText() = selectionController?.isActive() == true
    open fun getSelectedText() = getCoreSelectionText()?.takeIf { it.isNotEmpty() } ?: selectionController?.selectedText
    open fun getStoredSelectedText() = selectionController?.storedSelectedText
    open fun unsetStoredSelectedText() { selectionController?.unsetStoredSelectedText() }
    open fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) return
        getTextSelectionCursorController().show(event)
        mClient?.copyModeChanged(isSelectingText())
        invalidate()
    }
    open fun stopTextSelectionMode() {
        if (selectionController?.hide() == true) {
            mClient?.copyModeChanged(isSelectingText())
            invalidate()
        }
    }
    open fun renderTextSelection() { selectionController?.render() }
    open fun clearSelectionOverlays() { for (row in 0 until mRows) mCoreAdapter?.setSelection(row, 0, 0) }
    open fun getSelectionSelectors(out: IntArray) { selectionController?.getSelectors(out) ?: out.fill(-1) }
    open fun decrementYTextSelectionCursors(decrement: Int) { selectionController?.decrementY(decrement) }

    /**
     * Push the complete selection range to the core adapter immediately.
     * Rendering also calls this through FableInputTerminalView, but the
     * controller needs the same per-row state before an ActionMode action can
     * read the selected text.
     */
    open fun syncSelectionToCore() {
        val adapter = mCoreAdapter ?: return
        val selectors = IntArray(4)
        getSelectionSelectors(selectors)
        if (!isSelectingText()) {
            clearSelectionOverlays()
            return
        }
        val y1 = selectors[0] - mTopRow
        val y2 = selectors[1] - mTopRow
        val x1 = selectors[2]
        val x2 = selectors[3]
        for (row in 0 until mRows) {
            if (row in y1..y2 && TerminalViewInteractionLogic.hasSelection(y1, x1, y2, x2)) {
                val start = (if (row == y1) x1 else 0).coerceIn(0, mColumns)
                val end = (if (row == y2) x2 + 1 else mColumns).coerceIn(0, mColumns)
                adapter.setSelection(
                    row,
                    start,
                    end
                )
            } else {
                adapter.setSelection(row, 0, 0)
            }
        }
    }

    open fun getTextSelectionCursorController(): TextSelectionCursorController {
        val existing = selectionController
        if (existing != null) return existing
        val created =
            TextSelectionCursorController(this, textSelectionBarStyleProvider, textSelectionHandleStyleProvider)
        selectionController = created
        if (isAttachedToWindow) viewTreeObserver.addOnTouchModeChangeListener(created)
        return created
    }

    /** 立即结束选择，跳过 300ms 长按防抖（供浮条动作等明确操作使用）。 */
    open fun forceStopTextSelectionMode() {
        val controller = selectionController ?: return
        if (!controller.isActive()) return
        controller.forceHide()
        mClient?.copyModeChanged(false)
        invalidate()
    }

    /** 留存当前选中文本，供上下文菜单的「分享选中文本」使用。 */
    open fun captureSelectedTextForContextMenu() {
        selectionController?.storeSelectedText()
    }

    open fun onContextMenuClosed(menu: Menu?) { unsetStoredSelectedText() }

    @RequiresApi(Build.VERSION_CODES.O)
    override open fun autofill(value: AutofillValue?) {
        if (value?.isText == true) mTermSession?.write(value.textValue?.toString() ?: "")
        resetAutoFill()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    override open fun getAutofillType() = autoFillType
    @RequiresApi(Build.VERSION_CODES.O)
    override open fun getAutofillHints(): Array<String> = autoFillHints
    @RequiresApi(Build.VERSION_CODES.O)
    override open fun getAutofillValue(): AutofillValue? = AutofillValue.forText("")
    @RequiresApi(Build.VERSION_CODES.O)
    override open fun getImportantForAutofill() = autoFillImportance
    @RequiresApi(Build.VERSION_CODES.O)
    private fun resetAutoFill() {
        autoFillType = AUTOFILL_TYPE_NONE
        autoFillImportance = IMPORTANT_FOR_AUTOFILL_NO
        autoFillHints = emptyArray()
    }
    open fun getAutoFillManagerService(): AutofillManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            context.getSystemService(AutofillManager::class.java)
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage("TerminalView", "Failed to get AutofillManager service", e)
            null
        }
    }
    open fun isAutoFillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            getAutoFillManagerService()?.isEnabled == true
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage("TerminalView", "Failed to check if Autofill is enabled", e)
            false
        }
    }
    open fun requestAutoFillUsername() = requestAutoFill(arrayOf("username"))
    open fun requestAutoFillPassword() = requestAutoFill(arrayOf("password"))
    @Synchronized
    open fun requestAutoFill(autoFillHints: Array<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (autoFillHints.isEmpty()) return
        try {
            val manager = getAutoFillManagerService()
            if (manager?.isEnabled == true) {
                autoFillType = AUTOFILL_TYPE_TEXT
                autoFillImportance = IMPORTANT_FOR_AUTOFILL_YES
                this.autoFillHints = autoFillHints.copyOf()
                manager.requestAutofill(this)
            }
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage("TerminalView", "Failed to request Autofill", e)
        }
    }
    @Synchronized
    open fun cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || autoFillType == AUTOFILL_TYPE_NONE) return
        try {
            getAutoFillManagerService()?.takeIf { it.isEnabled }?.cancel()
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage("TerminalView", "Failed to cancel Autofill request", e)
        } finally {
            resetAutoFill()
        }
    }

    open fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        if (blinkRate != 0 && blinkRate !in TERMINAL_CURSOR_BLINK_RATE_MIN..TERMINAL_CURSOR_BLINK_RATE_MAX) {
            cursorBlinkRate = 0
            stopTerminalCursorBlinker()
            return false
        }
        cursorBlinkRate = blinkRate
        if (blinkRate == 0) stopTerminalCursorBlinker()
        return true
    }
    open fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        stopTerminalCursorBlinker()
        if (!start || mTermSession == null || cursorBlinkRate !in TERMINAL_CURSOR_BLINK_RATE_MIN..TERMINAL_CURSOR_BLINK_RATE_MAX ||
            (startOnlyIfCursorEnabled && !isCursorEnabled())
        ) return
        cursorBlinkVisible = true
        setCursorBlinkPhase(true)
        handler.postDelayed(cursorBlinkRunnable, cursorBlinkRate.toLong())
    }

    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorBlinkVisible = !cursorBlinkVisible
            setCursorBlinkPhase(cursorBlinkVisible)
            invalidate()
            handler.postDelayed(this, cursorBlinkRate.toLong())
        }
    }
    private fun stopTerminalCursorBlinker() {
        handler.removeCallbacks(cursorBlinkRunnable)
        setCursorBlinkPhase(true)
    }
    private fun setCursorBlinkPhase(visible: Boolean) {
        cursorBlinkVisible = visible
        mCoreAdapter?.setCursorBlinkState(visible)
    }

    override open fun onAttachedToWindow() {
        super.onAttachedToWindow()
        selectionController?.let { viewTreeObserver.addOnTouchModeChangeListener(it) }
    }
    override open fun onDetachedFromWindow() {
        if (isSelectingText()) {
            selectionController?.forceHide()
            mClient?.copyModeChanged(false)
            invalidate()
        }
        stopTerminalCursorBlinker()
        selectionController?.let {
            viewTreeObserver.removeOnTouchModeChangeListener(it)
            it.onDetached()
        }
        super.onDetachedFromWindow()
    }

    private fun Boolean?.orFalse() = this == true
}
