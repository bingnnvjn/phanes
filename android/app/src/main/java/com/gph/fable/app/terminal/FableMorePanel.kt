package com.gph.fable.app.terminal

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gph.fable.R
import com.gph.fable.app.FableActivity

/**
 * 抽屉顶部「更多」按钮的下拉面板：会话/界面设置的快捷入口。
 *
 * 样式为当前样本（与抽屉同底色 + 圆角 + 抬升），条目来自
 * [FableMorePanelLogic]；后续整 UI 重置时替换本类即可换肤。
 */
class FableMorePanel(
    private val activity: FableActivity,
    private val anchor: View,
    private val onAction: (FableMorePanelLogic.Action) -> Unit
) {
    private var popup: PopupWindow? = null

    fun show() {
        val items = FableMorePanelLogic.items(activity.getCurrentSession() != null)
        val panelColor = resolveColor(R.attr.fableActivityDrawerBackground, Color.DKGRAY)
        val textColor = resolveColor(android.R.attr.textColorPrimary, Color.WHITE)
        val panelWidth = dp(200f).toInt()

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(panelColor)
                cornerRadius = dp(10f)
            }
            setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
        }
        items.forEach { list.addView(createItem(it, textColor)) }

        list.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        popup = PopupWindow(list, panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8f)
        }.also { it.showAsDropDown(anchor, 0, dp(4f).toInt()) }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun createItem(item: FableMorePanelLogic.Item, textColor: Int): TextView =
        TextView(activity).apply {
            setText(labelRes(item.action))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(12f).toInt())
            setTextColor(textColor)
            isEnabled = item.enabled
            isClickable = item.enabled
            alpha = if (item.enabled) 1f else 0.4f
            if (item.enabled) {
                setOnClickListener {
                    dismiss()
                    onAction(item.action)
                }
            }
        }

    private fun labelRes(action: FableMorePanelLogic.Action): Int = when (action) {
        FableMorePanelLogic.Action.NEW_SESSION -> R.string.action_new_session
        FableMorePanelLogic.Action.CLOSE_SESSION -> R.string.action_close_session
        FableMorePanelLogic.Action.RENAME_SESSION -> R.string.action_rename_session
        FableMorePanelLogic.Action.THEME -> R.string.action_theme
        FableMorePanelLogic.Action.FONT_SIZE -> R.string.action_font_size
        FableMorePanelLogic.Action.SETTINGS -> R.string.action_open_settings
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(activity, value.resourceId) else value.data
        } else {
            fallback
        }
    }

    private fun dp(value: Float): Float = value * activity.resources.displayMetrics.density
}
