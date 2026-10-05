package com.gph.fable.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FableMorePanelLogicTest {

    @Test
    fun listsPanelActionsInOrder() {
        assertEquals(
            listOf(
                FableMorePanelLogic.Action.NEW_SESSION,
                FableMorePanelLogic.Action.CLOSE_SESSION,
                FableMorePanelLogic.Action.RENAME_SESSION,
                FableMorePanelLogic.Action.THEME,
                FableMorePanelLogic.Action.FONT_SIZE,
                FableMorePanelLogic.Action.SETTINGS
            ),
            FableMorePanelLogic.items(hasCurrentSession = true).map { it.action }
        )
    }

    @Test
    fun sessionBoundActionsAreDisabledWithoutCurrentSession() {
        val items = FableMorePanelLogic.items(hasCurrentSession = false).associateBy { it.action }
        assertFalse(items.getValue(FableMorePanelLogic.Action.CLOSE_SESSION).enabled)
        assertFalse(items.getValue(FableMorePanelLogic.Action.RENAME_SESSION).enabled)
        assertTrue(items.getValue(FableMorePanelLogic.Action.NEW_SESSION).enabled)
        assertTrue(items.getValue(FableMorePanelLogic.Action.THEME).enabled)
        assertTrue(items.getValue(FableMorePanelLogic.Action.FONT_SIZE).enabled)
        assertTrue(items.getValue(FableMorePanelLogic.Action.SETTINGS).enabled)
    }

    @Test
    fun clampsFontSizeToSupportedRange() {
        assertEquals(8, FableMorePanelLogic.clampFontDp(0))
        assertEquals(8, FableMorePanelLogic.clampFontDp(8))
        assertEquals(16, FableMorePanelLogic.clampFontDp(16))
        assertEquals(24, FableMorePanelLogic.clampFontDp(99))
    }

    @Test
    fun mapsThemeValuesAndIndices() {
        assertEquals(0, FableMorePanelLogic.themeIndex("system"))
        assertEquals(1, FableMorePanelLogic.themeIndex("false"))
        assertEquals(2, FableMorePanelLogic.themeIndex("true"))
        assertEquals(0, FableMorePanelLogic.themeIndex(null))

        assertEquals("system", FableMorePanelLogic.themeValue(0))
        assertEquals("false", FableMorePanelLogic.themeValue(1))
        assertEquals("true", FableMorePanelLogic.themeValue(2))
        assertEquals("system", FableMorePanelLogic.themeValue(9))
    }
}
