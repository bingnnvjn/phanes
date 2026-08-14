package com.gph.fable.view

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

internal class GestureAndScaleRecognizer(context: Context, val listener: Listener) {
    interface Listener {
        fun onSingleTapUp(e: MotionEvent): Boolean
        fun onDoubleTap(e: MotionEvent): Boolean
        fun onScroll(e2: MotionEvent, dx: Float, dy: Float): Boolean
        fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean
        fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean
        fun onDown(x: Float, y: Float): Boolean
        fun onUp(e: MotionEvent): Boolean
        fun onLongPress(e: MotionEvent)
    }

    private var afterLongPress = false
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = listener.onDown(e.x, e.y)
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float) =
            listener.onScroll(e2, dx, dy)
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float) =
            listener.onFling(e2, vx, vy)
        override fun onLongPress(e: MotionEvent) {
            listener.onLongPress(e)
            afterLongPress = true
        }
    }).also {
        it.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent) = listener.onSingleTapUp(e)
            override fun onDoubleTap(e: MotionEvent) = listener.onDoubleTap(e)
            override fun onDoubleTapEvent(e: MotionEvent) = true
        })
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector) =
            listener.onScale(detector.focusX, detector.focusY, detector.scaleFactor)
    }).also { it.isQuickScaleEnabled = false }

    fun onTouchEvent(event: MotionEvent) {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> afterLongPress = false
            MotionEvent.ACTION_UP -> if (!afterLongPress) listener.onUp(event)
        }
    }

    fun isInProgress() = scaleDetector.isInProgress
}
