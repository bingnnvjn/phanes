package com.gph.fable.view

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewInteractionLogicTest {
    @Test fun clampsScrollToAvailableHistory() {
        assertEquals(0, TerminalViewInteractionLogic.clampTopRow(3, 10))
        assertEquals(-10, TerminalViewInteractionLogic.clampTopRow(-20, 10))
        assertEquals(-3, TerminalViewInteractionLogic.clampTopRow(-3, 10))
    }

    @Test fun preservesFractionalScrollBetweenEvents() {
        val first = TerminalViewInteractionLogic.scrollRows(9f, 20f)
        assertEquals(0, first.rows)
        assertEquals(9f, first.remainderPx)
        val second = TerminalViewInteractionLogic.scrollRows(15f, 20f, first.remainderPx)
        assertEquals(1, second.rows)
        assertEquals(4f, second.remainderPx)
    }

    @Test fun normalizesReverseHandleOrder() {
        assertEquals(
            TerminalViewInteractionLogic.SelectionRange(2, 3, 5, 7),
            TerminalViewInteractionLogic.normalizeSelection(5, 7, 2, 3)
        )
    }

    @Test fun mapsScrollModesAndWheelDirection() {
        assertEquals(
            TerminalViewInteractionLogic.ScrollDestination.MOUSE_WHEEL,
            TerminalViewInteractionLogic.scrollDestination(true, true)
        )
        assertEquals(
            TerminalViewInteractionLogic.ScrollDestination.ALTERNATE_BUFFER_KEYS,
            TerminalViewInteractionLogic.scrollDestination(false, true)
        )
        assertEquals(
            TerminalViewInteractionLogic.ScrollDestination.SCROLLBACK,
            TerminalViewInteractionLogic.scrollDestination(false, false)
        )
        assertEquals(7, TerminalViewInteractionLogic.mouseWheelButton(-1, 7, 8))
        assertEquals(8, TerminalViewInteractionLogic.mouseWheelButton(1, 7, 8))
    }

    @Test fun mouseMoveOnlyComesFromMouseSource() {
        assertEquals(true, TerminalViewInteractionLogic.shouldReportMouseMove(true, true))
        assertEquals(false, TerminalViewInteractionLogic.shouldReportMouseMove(true, false))
        assertEquals(false, TerminalViewInteractionLogic.shouldReportMouseMove(false, true))
        assertEquals(true, TerminalViewInteractionLogic.shouldStartLongPress(false, 1))
        assertEquals(false, TerminalViewInteractionLogic.shouldStartLongPress(true, 1))
        assertEquals(false, TerminalViewInteractionLogic.shouldStartLongPress(false, 2))
    }

    @Test fun translatesExpandedControlKeys() {
        assertEquals(1, TerminalViewInteractionLogic.translateControlCode('a'.code, true))
        assertEquals(0, TerminalViewInteractionLogic.translateControlCode(' '.code, true))
        assertEquals(27, TerminalViewInteractionLogic.translateControlCode('['.code, true))
        assertEquals('x'.code, TerminalViewInteractionLogic.translateControlCode('x'.code, false))
    }

    @Test fun translatesImeTextLikeLegacyTerminalView() {
        assertEquals(
            listOf(
                TerminalViewInteractionLogic.ImeCodePoint('a'.code, false),
                TerminalViewInteractionLogic.ImeCodePoint('m'.code, true),
                TerminalViewInteractionLogic.ImeCodePoint(27, false),
                TerminalViewInteractionLogic.ImeCodePoint('_'.code, true)
            ),
            TerminalViewInteractionLogic.translateImeText("a\n\u001b\u001f", shiftDown = false)
        )
    }

    @Test fun translatesImeSurrogatePairsAndMalformedSurrogates() {
        assertEquals(
            listOf(
                TerminalViewInteractionLogic.ImeCodePoint(0x1f600, false),
                TerminalViewInteractionLogic.ImeCodePoint(0xfffd, false),
                TerminalViewInteractionLogic.ImeCodePoint(0xfffd, false)
            ),
            TerminalViewInteractionLogic.translateImeText("\uD83D\uDE00\uDE00\uD83D", shiftDown = false)
        )
    }

    @Test fun appliesImeShiftBeforeControlTranslation() {
        assertEquals(
            listOf(
                TerminalViewInteractionLogic.ImeCodePoint('A'.code, false),
                TerminalViewInteractionLogic.ImeCodePoint('m'.code, true)
            ),
            TerminalViewInteractionLogic.translateImeText("a\n", shiftDown = true)
        )
    }

    @Test fun keepsCrossRowSelectionWhenColumnsMatch() {
        assertEquals(
            true,
            TerminalViewInteractionLogic.hasSelection(2, 5, 3, 5)
        )
        assertEquals(
            true,
            TerminalViewInteractionLogic.hasSelection(2, 5, 2, 5)
        )
    }
}
