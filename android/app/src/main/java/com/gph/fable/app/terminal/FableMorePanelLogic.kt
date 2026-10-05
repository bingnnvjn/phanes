package com.gph.fable.app.terminal

/**
 * 抽屉「更多」面板的纯逻辑：条目清单、可用性与外观取值换算。
 * 与 Android 无关，供 JVM 单测直接覆盖；弹层绘制在 [FableMorePanel]。
 */
object FableMorePanelLogic {

    enum class Action {
        NEW_SESSION,
        CLOSE_SESSION,
        RENAME_SESSION,
        THEME,
        FONT_SIZE,
        SETTINGS
    }

    data class Item(val action: Action, val enabled: Boolean)

    const val MIN_FONT_DP = 8
    const val MAX_FONT_DP = 24

    /** 无当前会话时，关闭与重命名不可用。 */
    fun items(hasCurrentSession: Boolean): List<Item> = listOf(
        Item(Action.NEW_SESSION, true),
        Item(Action.CLOSE_SESSION, hasCurrentSession),
        Item(Action.RENAME_SESSION, hasCurrentSession),
        Item(Action.THEME, true),
        Item(Action.FONT_SIZE, true),
        Item(Action.SETTINGS, true)
    )

    fun clampFontDp(value: Int): Int = value.coerceIn(MIN_FONT_DP, MAX_FONT_DP)

    /**
     * 主题存储值（`NightMode` 的 name：system/false/true）与设置页数组下标互换；
     * 未知或未设置回落到「跟随系统」。
     */
    fun themeIndex(value: String?): Int = when (value) {
        "false" -> 1
        "true" -> 2
        else -> 0
    }

    fun themeValue(index: Int): String = when (index) {
        1 -> "false"
        2 -> "true"
        else -> "system"
    }
}
