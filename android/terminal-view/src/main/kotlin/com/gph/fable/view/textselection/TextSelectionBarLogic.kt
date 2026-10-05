package com.gph.fable.view.textselection

/**
 * 选择浮条的动作。工单 39 定案：浮条仅保留复制与粘贴两项，
 * 「更多」拆为抽屉入口，不再出现在选择菜单里。
 */
enum class TextSelectionAction {
    COPY,
    PASTE
}

/** 浮条上的一个按钮及其可用性。 */
data class TextSelectionBarItem(
    val action: TextSelectionAction,
    val enabled: Boolean
)

/** 浮条左上角在终端视图坐标系里的位置。 */
data class BarPlacement(val x: Int, val y: Int)

/**
 * 浮条的纯逻辑：按钮清单与定位。
 *
 * 与 Android 无关，供 JVM 单测直接覆盖；绘图与触摸在 [TextSelectionActionBar]。
 */
object TextSelectionBarLogic {

    /** 复制恒可用；粘贴取决于剪贴板是否有内容。 */
    fun items(hasClipboardText: Boolean): List<TextSelectionBarItem> = listOf(
        TextSelectionBarItem(TextSelectionAction.COPY, true),
        TextSelectionBarItem(TextSelectionAction.PASTE, hasClipboardText)
    )

    /**
     * 把浮条摆到选区旁：默认贴选区上方、水平居中；
     * 上方放不下则放下方；水平与垂直都夹在视口内（留 [margin] 边距）。
     */
    fun placement(
        anchorLeft: Int,
        anchorTop: Int,
        anchorRight: Int,
        anchorBottom: Int,
        barWidth: Int,
        barHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        gap: Int,
        margin: Int
    ): BarPlacement {
        val centerX = (anchorLeft + anchorRight) / 2
        val minX = margin
        val maxX = viewportWidth - margin - barWidth
        val x = if (maxX < minX) minX else (centerX - barWidth / 2).coerceIn(minX, maxX)

        var y = anchorTop - gap - barHeight
        if (y < margin) {
            y = anchorBottom + gap
            val maxY = viewportHeight - margin - barHeight
            if (y > maxY) y = if (maxY >= margin) maxY else margin
        }
        return BarPlacement(x, y)
    }
}
