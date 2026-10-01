package com.gph.fable.app.terminal.io

/** Keyboard shortcut descriptor kept as a Java-visible value object. */
class KeyboardShortcut(
    @JvmField val codePoint: Int,
    @JvmField val shortcutAction: Int
)
