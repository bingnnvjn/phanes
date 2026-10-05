package com.gph.fable.view.textselection

import android.content.Context
import androidx.annotation.DrawableRes
import com.gph.fable.view.R

/**
 * 选择手柄的样式：左右 drawable 与热点/触摸偏移比例。
 * 与浮条一样集中在一个可替换的来源里，换手柄外观或手感只改这里。
 */
data class TextSelectionHandleStyle(
    @DrawableRes val leftDrawableRes: Int,
    @DrawableRes val rightDrawableRes: Int,
    /** 热点在宽度方向的比例：左柄贴内（右）侧、右柄贴内（左）侧。 */
    val leftHotspotRatio: Float,
    val rightHotspotRatio: Float,
    /** 触摸点相对手柄高度的偏移比例（负值表示向上）。 */
    val touchOffsetRatio: Float
) {
    fun hotspotX(handleWidth: Int, isRight: Boolean): Float =
        handleWidth * (if (isRight) rightHotspotRatio else leftHotspotRatio)

    fun touchOffsetY(handleHeight: Int): Float = -handleHeight * touchOffsetRatio
}

/** 手柄样式的可替换来源。 */
interface TextSelectionHandleStyleProvider {
    fun resolve(context: Context): TextSelectionHandleStyle
}

/** 当前样本：沿用既有的左右泪滴 drawable 与热点比例。 */
object DefaultTextSelectionHandleStyleProvider : TextSelectionHandleStyleProvider {
    const val LEFT_HOTSPOT_RATIO = 0.75f
    const val RIGHT_HOTSPOT_RATIO = 0.25f
    const val TOUCH_OFFSET_RATIO = 0.3f

    override fun resolve(context: Context): TextSelectionHandleStyle = TextSelectionHandleStyle(
        leftDrawableRes = R.drawable.text_select_handle_left_material,
        rightDrawableRes = R.drawable.text_select_handle_right_material,
        leftHotspotRatio = LEFT_HOTSPOT_RATIO,
        rightHotspotRatio = RIGHT_HOTSPOT_RATIO,
        touchOffsetRatio = TOUCH_OFFSET_RATIO
    )
}
