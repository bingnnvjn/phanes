package com.gph.fable.view.textselection

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionHandleStyleTest {

    private val style = TextSelectionHandleStyle(
        leftDrawableRes = 0,
        rightDrawableRes = 0,
        leftHotspotRatio = 0.75f,
        rightHotspotRatio = 0.25f,
        touchOffsetRatio = 0.3f
    )

    @Test
    fun hotspotsSitOnInnerEdgeOfEachHandle() {
        assertEquals(36f, style.hotspotX(48, isRight = false), 0.001f)
        assertEquals(12f, style.hotspotX(48, isRight = true), 0.001f)
    }

    @Test
    fun touchOffsetLiftsAboveHandle() {
        assertEquals(-7.2f, style.touchOffsetY(24), 0.001f)
    }
}
