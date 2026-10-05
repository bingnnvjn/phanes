package com.gph.fable.view.textselection

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionBarLogicTest {

    @Test
    fun listsCopyAndPasteInOrder() {
        assertEquals(
            listOf(
                TextSelectionBarItem(TextSelectionAction.COPY, true),
                TextSelectionBarItem(TextSelectionAction.PASTE, false)
            ),
            TextSelectionBarLogic.items(hasClipboardText = false)
        )
    }

    @Test
    fun enablesPasteOnlyWithClipboardContent() {
        assertEquals(true, TextSelectionBarLogic.items(hasClipboardText = true)[1].enabled)
        assertEquals(false, TextSelectionBarLogic.items(hasClipboardText = false)[1].enabled)
    }

    @Test
    fun placesBarAboveSelectionWhenRoomExists() {
        assertEquals(
            BarPlacement(90, 152),
            TextSelectionBarLogic.placement(
                anchorLeft = 100, anchorTop = 200, anchorRight = 200, anchorBottom = 220,
                barWidth = 120, barHeight = 40,
                viewportWidth = 400, viewportHeight = 800,
                gap = 8, margin = 8
            )
        )
    }

    @Test
    fun dropsBarBelowSelectionWhenTopIsCramped() {
        assertEquals(
            BarPlacement(90, 38),
            TextSelectionBarLogic.placement(
                anchorLeft = 100, anchorTop = 10, anchorRight = 200, anchorBottom = 30,
                barWidth = 120, barHeight = 40,
                viewportWidth = 400, viewportHeight = 800,
                gap = 8, margin = 8
            )
        )
    }

    @Test
    fun clampsBarInsideViewportHorizontally() {
        assertEquals(
            272,
            TextSelectionBarLogic.placement(
                anchorLeft = 380, anchorTop = 400, anchorRight = 400, anchorBottom = 420,
                barWidth = 120, barHeight = 40,
                viewportWidth = 400, viewportHeight = 800,
                gap = 8, margin = 8
            ).x
        )
    }

    @Test
    fun fallsBackToLeftMarginWhenBarWiderThanViewport() {
        assertEquals(
            8,
            TextSelectionBarLogic.placement(
                anchorLeft = 100, anchorTop = 400, anchorRight = 200, anchorBottom = 420,
                barWidth = 500, barHeight = 40,
                viewportWidth = 400, viewportHeight = 800,
                gap = 8, margin = 8
            ).x
        )
    }

    @Test
    fun clampsBarToBottomWhenNeitherSideHasRoom() {
        assertEquals(
            52,
            TextSelectionBarLogic.placement(
                anchorLeft = 100, anchorTop = 10, anchorRight = 200, anchorBottom = 790,
                barWidth = 120, barHeight = 40,
                viewportWidth = 400, viewportHeight = 100,
                gap = 8, margin = 8
            ).y
        )
    }
}
