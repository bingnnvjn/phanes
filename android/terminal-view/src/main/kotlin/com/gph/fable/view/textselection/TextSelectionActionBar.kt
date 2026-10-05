package com.gph.fable.view.textselection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.gph.fable.view.R
import com.gph.fable.view.TerminalView
import com.gph.fable.view.support.PopupWindowCompatGingerbread

/**
 * 自绘选择浮条：PopupWindow + 两个文字按钮（复制/粘贴）。
 *
 * 窗口类型沿用选择手柄的 `TYPE_APPLICATION_SUB_PANEL`，不引入 ActionMode——
 * 工单 04 已定位，悬浮 ActionMode（`TYPE_FLOATING`）收尾会销毁 SurfaceView 的
 * surface 且不重建，是本 ROM 上「选择空白」的根因。
 *
 * 外观全部来自 [TextSelectionBarStyleProvider]，交互代码不含任何样式常量。
 */
class TextSelectionActionBar(
    private val terminalView: TerminalView,
    private val styleProvider: TextSelectionBarStyleProvider = DefaultTextSelectionBarStyleProvider
) {
    /** 按钮点击回调；由控制器接管执行。 */
    var onAction: ((TextSelectionAction) -> Unit)? = null

    private var popup: PopupWindow? = null
    private var content: LinearLayout? = null
    private var style: TextSelectionBarStyle? = null
    private var lastPlacement: BarPlacement? = null
    private val location = IntArray(2)

    fun isShowing(): Boolean = popup?.isShowing == true

    /** 在选区旁显示浮条；重复调用会按当前剪贴板状态重建。 */
    fun show(anchor: Rect, hasClipboardText: Boolean) {
        hide()
        val resolved = styleProvider.resolve(
            terminalView.context,
            DefaultTextSelectionBarStyleProvider.useDarkStyle(terminalView.context)
        )
        style = resolved
        val row = build(resolved, hasClipboardText)
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val placement = placementFor(anchor, row, resolved)
        lastPlacement = placement
        terminalView.getLocationInWindow(location)
        popup?.showAtLocation(
            terminalView,
            Gravity.NO_GRAVITY,
            location[0] + placement.x,
            location[1] + placement.y
        )
    }

    /** 只把已显示的浮条挪到新选区，不重建。绘制循环里每帧调用，需廉价。 */
    fun moveTo(anchor: Rect) {
        val popupWindow = popup ?: return
        val row = content ?: return
        val resolved = style ?: return
        if (!popupWindow.isShowing) return
        val placement = placementFor(anchor, row, resolved)
        if (placement == lastPlacement) return
        lastPlacement = placement
        terminalView.getLocationInWindow(location)
        popupWindow.update(location[0] + placement.x, location[1] + placement.y, -1, -1)
    }

    fun hide() {
        popup?.dismiss()
        popup = null
        content = null
        style = null
        lastPlacement = null
    }

    private fun build(style: TextSelectionBarStyle, hasClipboardText: Boolean): LinearLayout {
        val context = terminalView.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(style.backgroundColor)
                cornerRadius = style.cornerRadiusPx
            }
            setPadding(
                style.contentPaddingPx,
                style.contentPaddingPx,
                style.contentPaddingPx,
                style.contentPaddingPx
            )
        }
        TextSelectionBarLogic.items(hasClipboardText).forEachIndexed { index, item ->
            row.addView(
                createItem(context, style, labelRes(item.action), item.action, item.enabled),
                itemLayoutParams(style, if (index == 0) 0 else style.itemSpacingPx)
            )
        }
        content = row
        popup = createPopup(row, style)
        return row
    }

    private fun labelRes(action: TextSelectionAction): Int = when (action) {
        TextSelectionAction.COPY -> R.string.copy_text
        TextSelectionAction.PASTE -> R.string.paste_text
    }

    private fun itemLayoutParams(style: TextSelectionBarStyle, startMargin: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = startMargin }

    @SuppressLint("SetTextI18n")
    private fun createItem(
        context: Context,
        style: TextSelectionBarStyle,
        labelRes: Int,
        action: TextSelectionAction,
        enabled: Boolean
    ): TextView = TextView(context).apply {
        setText(labelRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
        setTextColor(if (enabled) style.textColor else style.disabledTextColor)
        gravity = Gravity.CENTER
        minWidth = style.itemMinWidthPx
        minHeight = style.itemMinHeightPx
        setPadding(style.itemPaddingHorizontalPx, 0, style.itemPaddingHorizontalPx, 0)
        isEnabled = enabled
        isClickable = enabled
        isFocusable = false
        if (enabled) setOnClickListener { onAction?.invoke(action) }
    }

    private fun createPopup(view: View, style: TextSelectionBarStyle): PopupWindow =
        PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isSplitTouchEnabled = true
            isClippingEnabled = false
            isFocusable = false
            isOutsideTouchable = false
            setBackgroundDrawable(null)
            animationStyle = 0
            elevation = style.elevationPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
                enterTransition = null
                exitTransition = null
            } else {
                PopupWindowCompatGingerbread.setWindowLayoutType(
                    this,
                    WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
                )
            }
        }

    private fun placementFor(anchor: Rect, row: View, style: TextSelectionBarStyle): BarPlacement =
        TextSelectionBarLogic.placement(
            anchorLeft = anchor.left,
            anchorTop = anchor.top,
            anchorRight = anchor.right,
            anchorBottom = anchor.bottom,
            barWidth = row.measuredWidth,
            barHeight = row.measuredHeight,
            viewportWidth = terminalView.width,
            viewportHeight = terminalView.height,
            gap = style.anchorGapPx,
            margin = style.screenMarginPx
        )
}
