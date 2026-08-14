package com.gph.fable.view.support

import android.util.Log
import android.widget.PopupWindow
import java.lang.reflect.Method

object PopupWindowCompatGingerbread {
    private var setMethod: Method? = null
    private var getMethod: Method? = null
    private var setAttempted = false
    private var getAttempted = false

    @JvmStatic fun setWindowLayoutType(window: PopupWindow, layoutType: Int) {
        if (!setAttempted) {
            setAttempted = true
            setMethod = runCatching {
                PopupWindow::class.java.getDeclaredMethod("setWindowLayoutType", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
            }.getOrNull()
        }
        runCatching { setMethod?.invoke(window, layoutType) }
    }

    @JvmStatic fun getWindowLayoutType(window: PopupWindow): Int {
        if (!getAttempted) {
            getAttempted = true
            getMethod = runCatching {
                PopupWindow::class.java.getDeclaredMethod("getWindowLayoutType")
                    .also { it.isAccessible = true }
            }.getOrNull()
        }
        return runCatching { (getMethod?.invoke(window) as? Int) ?: 0 }.getOrDefault(0)
    }
}
