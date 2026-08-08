package com.gph.fable.shared.view;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 工单 05：targetSdk 35 强制 edge-to-edge 的系统栏 insets 适配工具。
 *
 * 原则（用户拍板）：
 * - 状态栏/手势条避让全部用系统 insets，不写死尺寸；
 * - 底部不铺色块遮罩：背景延伸到手势条后面，可滚动内容用 {@code clipToPadding=false}，
 *   内容能滑到手势条下方，遮挡内容的只有手势条本身；
 * - 全部走系统 API，不自己写动画/遮罩。
 */
public final class SystemBarInsets {

    private SystemBarInsets() {}

    /**
     * 顶部/左右避让系统栏（状态栏 + 刘海），底部保留视图既有 padding（交给可滚动内容自行处理）。
     */
    public static void applyTopSystemBarInsets(@NonNull View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, v.getPaddingBottom());
            return insets;
        });
    }

    /**
     * 底部避让导航栏/手势条。baseBottom 为调用时刻视图既有底部 padding（px），
     * 每次 insets 变化时叠加手势条高度，保证不累计。
     *
     * @param view 目标视图（可滚动视图传 {@code clipToPadding=false}，内容可滑入手势条区域）。
     */
    public static void applyBottomNavigationBarInset(@NonNull View view, boolean clipToPadding) {
        final int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), baseBottom + nav.bottom);
            if (v instanceof ViewGroup) {
                ((ViewGroup) v).setClipToPadding(clipToPadding);
            }
            return insets;
        });
    }

    /**
     * 四周全部避让系统栏（顶部/左右/底部都用 padding）。适用于无滚动需求的整屏视图。
     */
    public static void applyAllSystemBarInsets(@NonNull View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}
