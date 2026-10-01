package com.gph.fable.shared.view

import android.view.View
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 工单 05：targetSdk 35 强制 edge-to-edge 的系统栏 insets 适配工具。
 */
object SystemBarInsets {

    /**
     * 顶部/左右避让系统栏（状态栏 + 刘海），底部保留视图既有 padding（交给可滚动内容自行处理）。
     */
    @JvmStatic
    fun applyTopSystemBarInsets(@NonNull rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, v.paddingBottom)
            insets
        }
    }

    /**
     * 底部避让导航栏/手势条。baseBottom 为调用时刻视图既有底部 padding（px），
     * 每次 insets 变化时叠加手势条高度，保证不累计。
     */
    @JvmStatic
    fun applyBottomNavigationBarInset(@NonNull view: View, clipToPadding: Boolean) {
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + nav.bottom)
            if (v is ViewGroup) {
                v.clipToPadding = clipToPadding
            }
            insets
        }
    }

    /**
     * 四周全部避让系统栏（顶部/左右/底部都用 padding）。适用于无滚动需求的整屏视图。
     */
    @JvmStatic
    fun applyAllSystemBarInsets(@NonNull rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
