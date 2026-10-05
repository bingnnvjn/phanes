package com.gph.fable.view.textselection

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionActionRunnerTest {

    private val calls = mutableListOf<String>()

    private fun runner(text: String? = "hello") = TextSelectionActionRunner(
        selectedText = { calls += "read"; text },
        copyToClipboard = { calls += "copy:$it" },
        stopSelection = { calls += "stop" },
        pasteFromClipboard = { calls += "paste" }
    )

    @Test
    fun copyReadsSelectionThenCopiesThenEndsSelection() {
        runner().run(TextSelectionAction.COPY)
        assertEquals(listOf("read", "copy:hello", "stop"), calls)
    }

    @Test
    fun copySendsEmptyStringWhenNothingSelected() {
        runner(null).run(TextSelectionAction.COPY)
        assertEquals(listOf("read", "copy:", "stop"), calls)
    }

    @Test
    fun pasteEndsSelectionThenPastes() {
        runner().run(TextSelectionAction.PASTE)
        assertEquals(listOf("stop", "paste"), calls)
    }
}
