package com.gph.fable.view

/** Pure interaction rules shared by touch/selection code and JVM tests. */
object TerminalViewInteractionLogic {
    enum class ScrollDestination { MOUSE_WHEEL, ALTERNATE_BUFFER_KEYS, SCROLLBACK }

    fun scrollDestination(mouseTracking: Boolean, alternateBuffer: Boolean): ScrollDestination =
        when {
            mouseTracking -> ScrollDestination.MOUSE_WHEEL
            alternateBuffer -> ScrollDestination.ALTERNATE_BUFFER_KEYS
            else -> ScrollDestination.SCROLLBACK
        }

    fun mouseWheelButton(rowsDown: Int, wheelUpButton: Int, wheelDownButton: Int): Int =
        if (rowsDown < 0) wheelUpButton else wheelDownButton

    fun shouldReportMouseMove(mouseTracking: Boolean, fromMouseSource: Boolean): Boolean =
        mouseTracking && fromMouseSource

    fun translateControlCode(codePoint: Int, controlDown: Boolean): Int {
        if (!controlDown) return codePoint
        return when {
            codePoint in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            codePoint in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            codePoint == ' '.code || codePoint == '2'.code -> 0
            codePoint == '['.code || codePoint == '3'.code -> 27
            codePoint == '\\'.code || codePoint == '4'.code -> 28
            codePoint == ']'.code || codePoint == '5'.code -> 29
            codePoint == '^'.code || codePoint == '6'.code -> 30
            codePoint == '_'.code || codePoint == '7'.code || codePoint == '/'.code -> 31
            codePoint == '8'.code -> 127
            else -> codePoint
        }
    }

    fun clampTopRow(topRow: Int, scrollbackRows: Int): Int =
        topRow.coerceIn(-scrollbackRows.coerceAtLeast(0), 0)

    fun scrollRows(distancePx: Float, cellHeightPx: Float, remainderPx: Float = 0f): ScrollDelta {
        if (cellHeightPx <= 0f) return ScrollDelta(0, remainderPx)
        val distance = distancePx + remainderPx
        val rows = (distance / cellHeightPx).toInt()
        return ScrollDelta(rows, distance - rows * cellHeightPx)
    }

    fun normalizeSelection(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int
    ): SelectionRange {
        return if (startRow < endRow || (startRow == endRow && startCol <= endCol)) {
            SelectionRange(startRow, startCol, endRow, endCol)
        } else {
            SelectionRange(endRow, endCol, startRow, startCol)
        }
    }

    data class ScrollDelta(val rows: Int, val remainderPx: Float)
    data class SelectionRange(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)
}
