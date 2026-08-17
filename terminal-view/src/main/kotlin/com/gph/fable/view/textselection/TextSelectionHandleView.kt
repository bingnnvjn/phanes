package com.gph.fable.view.textselection

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.WindowManager
import android.widget.PopupWindow
import com.gph.fable.view.R
import com.gph.fable.view.TerminalView
import com.gph.fable.view.support.PopupWindowCompatGingerbread
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
open class TextSelectionHandleView(
    private val terminalView: TerminalView,
    private val cursorController: CursorController,
    private val initialOrientation: Int
) : View(terminalView.context) {
    companion object {
        const val LEFT = 0
        const val RIGHT = 2
    }

    private val leftDrawable: Drawable = if (Build.VERSION.SDK_INT >= 21) {
        checkNotNull(context.getDrawable(R.drawable.text_select_handle_left_material))
    } else resources.getDrawable(R.drawable.text_select_handle_left_material)
    private val rightDrawable: Drawable = if (Build.VERSION.SDK_INT >= 21) {
        checkNotNull(context.getDrawable(R.drawable.text_select_handle_right_material))
    } else resources.getDrawable(R.drawable.text_select_handle_right_material)
    private var drawable: Drawable = leftDrawable
    private var popup: PopupWindow? = null
    private var dragging = false
    private var pointX = 0
    private var pointY = 0
    private var hotspotX = 0f
    private var hotspotY = 0f
    private var touchOffsetY = 0f
    private var orientation = initialOrientation
    private var handleWidth = 0
    private var handleHeight = 0
    private var lastOrientationCheck = 0L
    private val tempCoords = IntArray(2)
    private val tempRect = Rect()
    private var touchToWindowOffsetX = 0f
    private var touchToWindowOffsetY = 0f
    private var lastParentX = 0
    private var lastParentY = 0

    init { setOrientation(initialOrientation) }

    open fun setOrientation(value: Int) {
        orientation = value
        drawable = if (value == RIGHT) rightDrawable else leftDrawable
        handleWidth = drawable.intrinsicWidth
        handleHeight = drawable.intrinsicHeight
        hotspotX = if (value == RIGHT) handleWidth / 4f else handleWidth * 3 / 4f
        hotspotY = 0f
        touchOffsetY = -handleHeight * .3f
        requestLayout()
        invalidate()
    }
    open fun changeOrientation(value: Int) { if (orientation != value) setOrientation(value) }
    open fun isDragging() = dragging
    open fun getHandleHeight() = handleHeight
    open fun getHandleWidth() = handleWidth
    open fun isShowing() = popup?.isShowing == true

    private fun initPopup() {
        popup = PopupWindow(context, null, android.R.attr.textSelectHandleWindowStyle).apply {
            isSplitTouchEnabled = true
            isClippingEnabled = false
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(null)
            animationStyle = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
                enterTransition = null
                exitTransition = null
            } else PopupWindowCompatGingerbread.setWindowLayoutType(this, WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL)
            contentView = this@TextSelectionHandleView
        }
    }

    open fun show() {
        if (!isPositionVisible() && !dragging) { hide(); return }
        removeFromParent()
        initPopup()
        val coords = tempCoords
        terminalView.getLocationInWindow(coords)
        popup?.showAtLocation(terminalView, 0, coords[0] + pointX, coords[1] + pointY)
        invalidate()
    }
    open fun hide() {
        dragging = false
        popup?.dismiss()
        removeFromParent()
        popup = null
        invalidate()
    }
    open fun removeFromParent() { (parent as? ViewGroup)?.removeView(this) }
    open fun isParentNull() = parent == null

    open fun positionAtCursor(cx: Int, cy: Int, forceOrientationCheck: Boolean) {
        moveTo(terminalView.getPointX(cx), terminalView.getPointY(cy + 1), forceOrientationCheck)
    }
    open fun moveTo(x: Int, y: Int, forceOrientationCheck: Boolean) {
        val oldHotspot = hotspotX
        checkChangedOrientation(x, forceOrientationCheck)
        pointX = (x - if (isShowing()) oldHotspot else hotspotX).toInt()
        pointY = y
        if (!isPositionVisible() && !dragging) { hide(); return }
        if (isShowing()) {
            terminalView.getLocationInWindow(tempCoords)
            popup?.update(tempCoords[0] + pointX, tempCoords[1] + pointY, width, height)
        } else show()
        if (dragging) {
            terminalView.getLocationInWindow(tempCoords)
            if (tempCoords[0] != lastParentX || tempCoords[1] != lastParentY) {
                touchToWindowOffsetX += tempCoords[0] - lastParentX
                touchToWindowOffsetY += tempCoords[1] - lastParentY
                lastParentX = tempCoords[0]; lastParentY = tempCoords[1]
            }
        }
    }

    private fun checkChangedOrientation(posX: Int, force: Boolean) {
        if (!dragging && !force) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOrientationCheck < 50) return
        lastOrientationCheck = now
        val parent = terminalView.parent ?: return
        tempRect.set(
            terminalView.left + terminalView.paddingLeft,
            terminalView.top + terminalView.paddingTop,
            terminalView.width - terminalView.paddingRight,
            terminalView.height - terminalView.paddingBottom
        )
        if (!parent.getChildVisibleRect(terminalView, tempRect, null)) return
        when {
            posX - handleWidth < tempRect.left -> changeOrientation(RIGHT)
            posX + handleWidth > tempRect.right -> changeOrientation(LEFT)
            else -> changeOrientation(initialOrientation)
        }
    }

    private fun isPositionVisible(): Boolean {
        if (dragging) return true
        val parent: ViewParent = terminalView.parent ?: return false
        tempRect.set(terminalView.paddingLeft, terminalView.paddingTop,
            terminalView.width - terminalView.paddingRight, terminalView.height - terminalView.paddingBottom)
        if (!parent.getChildVisibleRect(terminalView, tempRect, null)) return false
        terminalView.getLocationInWindow(tempCoords)
        val x = tempCoords[0] + pointX + hotspotX
        val y = tempCoords[1] + pointY + hotspotY
        return x >= tempRect.left && x <= tempRect.right && y >= tempRect.top && y <= tempRect.bottom
    }

    public override open fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(handleWidth, handleHeight)
    }
    override open fun onDraw(canvas: Canvas) {
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override open fun onTouchEvent(event: MotionEvent): Boolean {
        terminalView.updateFloatingToolbarVisibility(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchToWindowOffsetX = event.rawX - pointX
                touchToWindowOffsetY = event.rawY - pointY
                terminalView.getLocationInWindow(tempCoords)
                lastParentX = tempCoords[0]; lastParentY = tempCoords[1]
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.rawX - touchToWindowOffsetX + hotspotX
                val y = event.rawY - touchToWindowOffsetY + hotspotY + touchOffsetY
                cursorController.updatePosition(this, x.roundToInt(), y.roundToInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
