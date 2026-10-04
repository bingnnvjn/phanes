package com.gph.fable.shared.shell

import java.util.LinkedList

/**
 * Tokenizes command-line style arguments while preserving the historical
 * Termux quoting and escaping behavior.
 */
object ArgumentTokenizer {
    private const val NO_TOKEN_STATE = 0
    private const val NORMAL_TOKEN_STATE = 1
    private const val SINGLE_QUOTE_STATE = 2
    private const val DOUBLE_QUOTE_STATE = 3

    @JvmStatic
    fun tokenize(arguments: String): List<String> = tokenize(arguments, false)

    @JvmStatic
    fun tokenize(arguments: String, stringify: Boolean): List<String> {
        val argList = LinkedList<String>()
        var currArg = StringBuilder()
        var escaped = false
        var state = NO_TOKEN_STATE

        var i = 0
        while (i < arguments.length) {
            val c = arguments[i]
            if (escaped) {
                escaped = false
                currArg.append(c)
            } else {
                when (state) {
                    SINGLE_QUOTE_STATE -> {
                        if (c == '\'') state = NORMAL_TOKEN_STATE else currArg.append(c)
                    }
                    DOUBLE_QUOTE_STATE -> {
                        if (c == '"') {
                            state = NORMAL_TOKEN_STATE
                        } else if (c == '\\') {
                            i++
                            val next = arguments[i]
                            if (next == '"' || next == '\\') {
                                currArg.append(next)
                            } else {
                                currArg.append(c).append(next)
                            }
                        } else {
                            currArg.append(c)
                        }
                    }
                    NO_TOKEN_STATE, NORMAL_TOKEN_STATE -> when (c) {
                        '\\' -> {
                            escaped = true
                            state = NORMAL_TOKEN_STATE
                        }
                        '\'' -> state = SINGLE_QUOTE_STATE
                        '"' -> state = DOUBLE_QUOTE_STATE
                        else -> {
                            // 用 java.lang.Character.isWhitespace 而非 Char.isWhitespace()：后者把
                            // NBSP(U+00A0) 等 SpaceChar 也算空白，会把 Java 原版的一个 token 拆成两个。
                            if (!Character.isWhitespace(c)) {
                                currArg.append(c)
                                state = NORMAL_TOKEN_STATE
                            } else if (state == NORMAL_TOKEN_STATE) {
                                argList.add(currArg.toString())
                                currArg = StringBuilder()
                                state = NO_TOKEN_STATE
                            }
                        }
                    }
                    else -> error("ArgumentTokenizer state $state is invalid!")
                }
            }
            i++
        }

        if (escaped) {
            currArg.append('\\')
            argList.add(currArg.toString())
        } else if (state != NO_TOKEN_STATE) {
            argList.add(currArg.toString())
        }

        if (stringify) {
            for (index in argList.indices) {
                argList[index] = "\"" + escapeQuotesAndBackslashes(argList[index]) + "\""
            }
        }
        return argList
    }

    @JvmStatic
    private fun escapeQuotesAndBackslashes(value: String): String {
        val buffer = StringBuilder(value)
        for (i in value.length - 1 downTo 0) {
            when (value[i]) {
                '\\', '"' -> buffer.insert(i, '\\')
                '\n' -> buffer.replace(i, i + 1, "\\n")
                '\t' -> buffer.replace(i, i + 1, "\\t")
                '\r' -> buffer.replace(i, i + 1, "\\r")
                '\b' -> buffer.replace(i, i + 1, "\\b")
                '\u000C' -> buffer.replace(i, i + 1, "\\f")
            }
        }
        return buffer.toString()
    }
}
