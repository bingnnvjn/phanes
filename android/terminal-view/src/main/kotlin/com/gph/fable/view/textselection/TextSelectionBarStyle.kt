package com.gph.fable.view.textselection

import android.content.Context
import android.content.res.Configuration

/**
 * 选择浮条的样式参数。全部样式集中于此，交互代码不写死任何颜色/尺寸，
 * 后续整 UI 重置时只需换一个 [TextSelectionBarStyleProvider] 实现。
 */
data class TextSelectionBarStyle(
    val backgroundColor: Int,
    val textColor: Int,
    val disabledTextColor: Int,
    val cornerRadiusPx: Float,
    val textSizeSp: Float,
    val itemMinWidthPx: Int,
    val itemMinHeightPx: Int,
    val itemPaddingHorizontalPx: Int,
    val itemSpacingPx: Int,
    val contentPaddingPx: Int,
    val elevationPx: Float,
    /** 浮条与选区之间的间距。 */
    val anchorGapPx: Int,
    /** 浮条与屏幕边缘的最小边距。 */
    val screenMarginPx: Int
)

/** 浮条样式的可替换来源。 */
interface TextSelectionBarStyleProvider {
    fun resolve(context: Context, useDarkStyle: Boolean): TextSelectionBarStyle
}

/**
 * 默认样式：深/浅两套半透明圆角条 + 文字按钮，作为当前样本。
 * 只求观感克制、与终端底色不冲突；真机精修或 UI 重置时替换本实现即可。
 */
object DefaultTextSelectionBarStyleProvider : TextSelectionBarStyleProvider {
    private const val DARK_BACKGROUND = 0xE6323232.toInt()
    private const val LIGHT_BACKGROUND = 0xF2FFFFFF.toInt()
    private const val DARK_TEXT = 0xFFFFFFFF.toInt()
    private const val LIGHT_TEXT = 0xFF212121.toInt()
    private const val DARK_DISABLED_TEXT = 0x66FFFFFF
    private const val LIGHT_DISABLED_TEXT = 0x66212121

    override fun resolve(context: Context, useDarkStyle: Boolean): TextSelectionBarStyle {
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = value * density
        return TextSelectionBarStyle(
            backgroundColor = if (useDarkStyle) DARK_BACKGROUND else LIGHT_BACKGROUND,
            textColor = if (useDarkStyle) DARK_TEXT else LIGHT_TEXT,
            disabledTextColor = if (useDarkStyle) DARK_DISABLED_TEXT else LIGHT_DISABLED_TEXT,
            cornerRadiusPx = dp(10f),
            textSizeSp = 15f,
            itemMinWidthPx = dp(64f).toInt(),
            itemMinHeightPx = dp(40f).toInt(),
            itemPaddingHorizontalPx = dp(16f).toInt(),
            itemSpacingPx = dp(4f).toInt(),
            contentPaddingPx = dp(4f).toInt(),
            elevationPx = dp(6f),
            anchorGapPx = dp(8f).toInt(),
            screenMarginPx = dp(8f).toInt()
        )
    }

    /** 由资源夜间模式推断浮条明暗；无样式注入时使用。 */
    fun useDarkStyle(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
