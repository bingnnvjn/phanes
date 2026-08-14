package com.gph.fable.view

import android.annotation.SuppressLint
import android.content.ClipData
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
import com.gph.fable.view.textselection.TextSelectionCursorController
import java.nio.charset.StandardCharsets
import java.util.function.Consumer
import kotlin.math.max

@SuppressLint("ViewConstructor")
open class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    companion object {
        private var diagnosticListener: Consumer<String>? = null
        private var keyLoggingEnabled = false
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
    private val scroller = Scroller(context)
    private val handler = Handler(Looper.getMainLooper())
    private var scrollRemainder = 0f
    private var lastScrollY = 0
    private val gestureRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {
        private var scrolled = false
        override fun onDown(x: Float, y: Float) = false
        override fun onUp(e: MotionEvent): Boolean {
            if (mTermSession != null && isMouseTrackingActive() &&
                !e.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolled
            ) {
                sendMouseEventCode(e, CoreAdapter.MOUSE_LEFT_BUTTON, true)
                sendMouseEventCode(e, CoreAdapter.MOUSE_LEFT_BUTTON, false)
                return true
            }
            scrollRemainder = 0f
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

    fun setTerminalViewClient(client: TerminalViewClient?) { mClient = client }
    fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) { keyLoggingEnabled = value }
    fun isCoreAdapterActive() = mCoreAdapter != null
    fun isMouseTrackingActive() = mTermSession?.isMouseTrackingActive == true
    fun isAlternateBufferActive() = mTermSession?.isAlternateBufferActive == true
    fun isCursorEnabled() = mTermSession?.isCursorEnabled != false
    fun isCursorKeysApplicationMode() = mTermSession?.isCursorKeysApplicationMode == true
    fun isKeypadApplicationMode() = mTermSession?.isKeypadApplicationMode == true
    fun isAutoScrollDisabled() = autoScrollDisabled

    open fun attachSession(session: TerminalSession?): Boolean {
        if (mTermSession === session) return false
        mTermSession?.updateTerminalSessionClient(null)
        mTermSession = session
        mTopRow = 0
        selectionController?.hide()
        if (session != null) {
            session.setCoreAdapter(mCoreAdapter)
            updateSize()
            mClient?.onEmulatorSet()
        }
        invalidate()
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.forEach { inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, it.code, false, false) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event) else onKeyUp(event.keyCode, event)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                mTermSession?.write(byteArrayOf(0x7f), 0, 1); return true
            }
        }
    }

    override fun onCheckIsTextEditor() = true
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val session = mTermSession ?: return true
        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event)
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
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mTermSession != null && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val session = mTermSession ?: return true
        if (isSelectingText()) stopTextSelectionMode()
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

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (mTermSession == null && keyCode != KeyEvent.KEYCODE_BACK) return true
        if (mClient?.onKeyUp(keyCode, event) == true) {
            invalidate()
            return true
        }
        if (event.isSystem) return super.onKeyUp(keyCode, event)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cancelRequestAutoFill()
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

    fun inputCodePoint(eventSource: Int, codePoint: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        val session = mTermSession ?: return
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

    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        setCursorBlinkPhase(true)
        if (handleKeyCodeAction(keyCode, keyMod)) return true
        val code = KeyHandler.getCode(keyCode, keyMod, isCursorKeysApplicationMode(), isKeypadApplicationMode()) ?: return false
        mTermSession?.write(code)
        return true
    }
    fun handleKeyCodeAction(keyCode: Int, keyMod: Int): Boolean {
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

    fun sendMouseEventCode(event: MotionEvent, button: Int, pressed: Boolean) {
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updateSize() }
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
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); renderTextSelection() }
    override fun computeVerticalScrollRange() = max(1, mRows + getScrollbackRows())
    override fun computeVerticalScrollExtent() = max(1, mRows)
    override fun computeVerticalScrollOffset() = if (mTermSession == null) 1 else getScrollbackRows() + mTopRow
    fun onScreenUpdated() { onScreenUpdated(false) }
    fun onScreenUpdated(skipScrolling: Boolean) {
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
        invalidate()
    }
    fun getCurrentSession() = mTermSession
    open fun getCursorX(x: Float) = if (mColumns == 0) 0 else (x / (width.toFloat() / mColumns)).toInt()
    open fun getCursorY(y: Float) = if (mRows == 0) 0 else (y / (height.toFloat() / mRows)).toInt() + mTopRow
    open fun getPointX(cx: Int) = ((cx.coerceIn(0, max(0, mColumns))) * width.toFloat() / max(1, mColumns)).toInt()
    open fun getPointY(cy: Int) = ((cy - mTopRow) * height.toFloat() / max(1, mRows)).toInt()
    open fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray =
        intArrayOf(getCursorX(event.x), getCursorY(event.y).let { if (relativeToScroll) it else it - mTopRow })
    fun getTopRow() = mTopRow
    fun setTopRow(value: Int) {
        mTopRow = TerminalViewInteractionLogic.clampTopRow(value, getScrollbackRows())
        invalidate()
    }
    fun getRows() = mRows
    fun getColumns() = mColumns
    fun getCellWidthPx() = mCellWidthPx
    fun getCellHeightPx() = mCellHeightPx
    fun getScrollbackRows() = mCoreAdapter?.getScrollbackRows() ?: 0
    fun getCoreText(row: Int, startCol: Int, endCol: Int) = mCoreAdapter?.getText(row, startCol, endCol) ?: ""
    fun getWordBoundsAt(column: Int, row: Int) = mCoreAdapter?.getWordBoundsAt(column, row)
    fun toggleAutoScrollDisabled() { autoScrollDisabled = !autoScrollDisabled }
    fun setTypeface(newTypeface: Typeface?) { typeface = newTypeface ?: Typeface.MONOSPACE; invalidate() }
    open fun setTextSize(textSize: Int) { mTextSize = textSize; updateSize() }
    override fun isOpaque() = true

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

    fun getCoreSelectionText() = mCoreAdapter?.takeIf { it.supportsSelectionText() }?.selectionText
    fun isSelectingText() = selectionController?.isActive() == true
    fun getSelectedText() = getCoreSelectionText()?.takeIf { it.isNotEmpty() } ?: selectionController?.selectedText
    fun getStoredSelectedText() = selectionController?.storedSelectedText
    fun unsetStoredSelectedText() { selectionController?.unsetStoredSelectedText() }
    fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) return
        getTextSelectionCursorController().show(event)
        mClient?.copyModeChanged(isSelectingText())
        invalidate()
    }
    fun stopTextSelectionMode() {
        if (selectionController?.hide() == true) {
            mClient?.copyModeChanged(isSelectingText())
            invalidate()
        }
    }
    fun renderTextSelection() { selectionController?.render() }
    fun clearSelectionOverlays() { for (row in 0 until mRows) mCoreAdapter?.setSelection(row, 0, 0) }
    fun getSelectionSelectors(out: IntArray) { selectionController?.getSelectors(out) ?: out.fill(-1) }
    fun decrementYTextSelectionCursors(decrement: Int) { selectionController?.decrementY(decrement) }

    fun getTextSelectionCursorController(): TextSelectionCursorController {
        val existing = selectionController
        if (existing != null) return existing
        val created = TextSelectionCursorController(this)
        selectionController = created
        if (isAttachedToWindow) viewTreeObserver.addOnTouchModeChangeListener(created)
        return created
    }
    private val showFloatingToolbar = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) selectionController?.actionMode?.hide(0)
    }
    fun updateFloatingToolbarVisibility(event: MotionEvent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || selectionController?.actionMode == null || event == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> postDelayed(showFloatingToolbar, ViewConfiguration.getDoubleTapTimeout().toLong())
        }
    }
    fun hideFloatingToolbar() {
        removeCallbacks(showFloatingToolbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) selectionController?.actionMode?.hide(-1)
    }
    fun onContextMenuClosed(menu: Menu?) { unsetStoredSelectedText() }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue?) {
        if (value?.isText == true) mTermSession?.write(value.textValue?.toString() ?: "")
        resetAutoFill()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillType() = autoFillType
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> = autoFillHints
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillValue(): AutofillValue? = AutofillValue.forText("")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun getImportantForAutofill() = autoFillImportance
    @RequiresApi(Build.VERSION_CODES.O)
    private fun resetAutoFill() {
        autoFillType = AUTOFILL_TYPE_NONE
        autoFillImportance = IMPORTANT_FOR_AUTOFILL_NO
        autoFillHints = emptyArray()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun getAutoFillManagerService() = context.getSystemService(AutofillManager::class.java)
    @RequiresApi(Build.VERSION_CODES.O)
    fun isAutoFillEnabled() = getAutoFillManagerService()?.isEnabled == true
    @RequiresApi(Build.VERSION_CODES.O)
    fun requestAutoFillUsername() = requestAutoFill(arrayOf("username"))
    @RequiresApi(Build.VERSION_CODES.O)
    fun requestAutoFillPassword() = requestAutoFill(arrayOf("password"))
    @RequiresApi(Build.VERSION_CODES.O)
    @Synchronized
    fun requestAutoFill(autoFillHints: Array<String>) {
        if (autoFillHints.isEmpty()) return
        val manager = getAutoFillManagerService()
        if (manager?.isEnabled == true) {
            autoFillType = AUTOFILL_TYPE_TEXT
            autoFillImportance = IMPORTANT_FOR_AUTOFILL_YES
            this.autoFillHints = autoFillHints.copyOf()
            manager.requestAutofill(this)
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun cancelRequestAutoFill() = resetAutoFill()

    fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        if (blinkRate != 0 && blinkRate !in TERMINAL_CURSOR_BLINK_RATE_MIN..TERMINAL_CURSOR_BLINK_RATE_MAX) {
            cursorBlinkRate = 0
            stopTerminalCursorBlinker()
            return false
        }
        cursorBlinkRate = blinkRate
        if (blinkRate == 0) stopTerminalCursorBlinker()
        return true
    }
    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        selectionController?.let { viewTreeObserver.addOnTouchModeChangeListener(it) }
    }
    override fun onDetachedFromWindow() {
        selectionController?.let {
            viewTreeObserver.removeOnTouchModeChangeListener(it)
            it.onDetached()
        }
        super.onDetachedFromWindow()
    }

    private fun Boolean?.orFalse() = this == true
}
