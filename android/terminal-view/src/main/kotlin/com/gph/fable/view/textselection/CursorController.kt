package com.gph.fable.view.textselection

import android.view.MotionEvent
import android.view.ViewTreeObserver

interface CursorController : ViewTreeObserver.OnTouchModeChangeListener {
    fun show(event: MotionEvent)
    fun hide(): Boolean
    fun render()
    fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int)
    fun onTouchEvent(event: MotionEvent): Boolean
    fun onDetached()
    fun isActive(): Boolean
    /** 手柄开始/结束拖动；控制器据此收起或重显浮条。 */
    fun onHandleDragStart()
    fun onHandleDragEnd()
}
