package com.gph.fable.app.terminal.io

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.gph.fable.app.FableActivity

/**
 * Keeps the extra-keys view visible while the soft keyboard changes the usable
 * fullscreen area.
 */
class FullScreenWorkAround private constructor(activity: FableActivity) {
    private val childOfContent: View
    private var usableHeightPrevious = 0
    private val viewGroupLayoutParams: ViewGroup.LayoutParams
    private val navBarHeight: Int

    init {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        childOfContent = content.getChildAt(0)
        viewGroupLayoutParams = childOfContent.layoutParams
        navBarHeight = activity.navBarHeight
        childOfContent.viewTreeObserver.addOnGlobalLayoutListener {
            possiblyResizeChildOfContent()
        }
    }

    private fun possiblyResizeChildOfContent() {
        val usableHeightNow = computeUsableHeight()
        if (usableHeightNow == usableHeightPrevious) return

        val usableHeightSansKeyboard = childOfContent.rootView.height
        val heightDifference = usableHeightSansKeyboard - usableHeightNow
        viewGroupLayoutParams.height =
            if (heightDifference > usableHeightSansKeyboard / 4) {
                usableHeightSansKeyboard - heightDifference + navBarHeight
            } else {
                usableHeightSansKeyboard
            }
        childOfContent.requestLayout()
        usableHeightPrevious = usableHeightNow
    }

    private fun computeUsableHeight(): Int {
        val rect = Rect()
        childOfContent.getWindowVisibleDisplayFrame(rect)
        return rect.bottom - rect.top
    }

    companion object {
        @JvmStatic
        fun apply(activity: FableActivity) {
            FullScreenWorkAround(activity)
        }
    }
}
