package com.gph.fable.view

/** Pure interaction rules shared by touch/selection code and JVM tests. */
object TerminalViewInteractionLogic {
    enum class ScrollDestination { MOUSE_WHEEL, ALTERNATE_BUFFER_KEYS, SCROLLBACK }

    data class ImeCodePoint(val codePoint: Int, val ctrlDown: Boolean)

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

    fun shouldStartLongPress(scaleInProgress: Boolean, pointerCount: Int): Boolean =
        !scaleInProgress && pointerCount <= 1

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

    /**
     * Translate committed IME text using the same terminal-facing rules as the
     * legacy Java view.  IME text is UTF-16, while TerminalSession accepts
     * Unicode scalar values, so surrogate pairs are combined and malformed
     * sequences become U+FFFD instead of being sent as invalid code points.
     */
    fun translateImeText(text: CharSequence, shiftDown: Boolean): List<ImeCodePoint> {
        val result = ArrayList<ImeCodePoint>(text.length)
        var index = 0
        while (index < text.length) {
            val first = text[index++]
            val codePoint = when {
                first.isHighSurrogate() -> {
                    if (index < text.length && text[index].isLowSurrogate()) {
                        Character.toCodePoint(first, text[index++])
                    } else {
                        0xfffd
                    }
                }
                first.isLowSurrogate() -> 0xfffd
                else -> first.code
            }

            var translated = if (shiftDown) Character.toUpperCase(codePoint) else codePoint
            var ctrlDown = false
            if (translated <= 31 && translated != 27) {
                if (translated == '\n'.code) translated = '\r'.code
                ctrlDown = true
                translated = when (translated) {
                    31 -> '_'.code
                    30 -> '^'.code
                    29 -> ']'.code
                    28 -> '\\'.code
                    else -> translated + 96
                }
            }
            result += ImeCodePoint(translated, ctrlDown)
        }
        return result
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

    /**
     * Selection controller columns are inclusive endpoints; the core receives
     * the exclusive end as `endCol + 1`.  Therefore equal row/column endpoints
     * still represent a one-cell selection (for example a one-character word).
     */
    fun hasSelection(startRow: Int, startCol: Int, endRow: Int, endCol: Int): Boolean =
        startRow < endRow || (startRow == endRow && startCol <= endCol)

    data class ScrollDelta(val rows: Int, val remainderPx: Float)
    data class SelectionRange(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)
}
