package com.gph.fable.app.terminal

import android.content.Context
import com.gph.fable.core.adapter.CoreAdapter
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.theme.NightMode
import com.gph.fable.shared.theme.ThemeUtils
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Properties

/** Fable terminal palette, with colors.properties and light/dark fallbacks. */
object FableTerminalPalette {
    @JvmField
    val DARK_FOREGROUND = 0xFFFFFFFF.toInt()
    @JvmField
    val DARK_BACKGROUND = 0xFF000000.toInt()
    @JvmField
    val DARK_SELECTION = 0xFF335588.toInt()
    @JvmField
    val DARK_CURSOR = 0xFFFFFFFF.toInt()
    @JvmField
    val LIGHT_FOREGROUND = 0xFF000000.toInt()
    @JvmField
    val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    @JvmField
    val LIGHT_SELECTION = 0xFFBBDEFB.toInt()
    @JvmField
    val LIGHT_CURSOR = 0xFF000000.toInt()

    private val darkAnsi = intArrayOf(
        0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
        0xFF6495ED.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
    )
    private val lightAnsi = intArrayOf(
        0xFF000000.toInt(), 0xFFC50F1F.toInt(), 0xFF0F7A1F.toInt(), 0xFFB58900.toInt(),
        0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF007C8A.toInt(), 0xFF808080.toInt(),
        0xFF595959.toInt(), 0xFFE74856.toInt(), 0xFF16A012.toInt(), 0xFFC19C00.toInt(),
        0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF0098A6.toInt(), 0xFFE0E0E0.toInt()
    )

    class Palette(
        @JvmField val custom: Boolean,
        @JvmField val foreground: Int,
        @JvmField val background: Int,
        @JvmField val selection: Int,
        @JvmField val cursor: Int,
        ansi: IntArray
    ) {
        @JvmField
        val ansi: IntArray = ansi.clone()
    }

    @JvmStatic
    fun resolve(context: Context): Palette {
        val dark = ThemeUtils.shouldEnableDarkTheme(context, NightMode.getAppNightMode().name)
        return resolve(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, dark)
    }

    @JvmStatic
    fun resolve(colorsFile: File?, dark: Boolean): Palette {
        if (colorsFile?.isFile == true) {
            return try {
                FileInputStream(colorsFile).use { input -> fromProperties(Properties().also { it.load(input) }, dark) }
            } catch (_: Exception) {
                builtIn(dark)
            }
        }
        return builtIn(dark)
    }

    @JvmStatic
    fun fromProperties(props: Properties, dark: Boolean): Palette {
        val fallback = builtIn(dark)
        val foreground = parse(props.getProperty("foreground"), fallback.foreground)
        val background = parse(props.getProperty("background"), fallback.background)
        val selection = parse(props.getProperty("selection"), fallback.selection)
        val cursor = parse(props.getProperty("cursor"), cursorFor(background))
        val ansi = fallback.ansi.copyOf()
        for (index in 0 until 16) {
            ansi[index] = parse(props.getProperty("color$index"), ansi[index])
        }
        return Palette(true, foreground, background, selection, cursor, ansi)
    }

    @JvmStatic
    fun builtIn(dark: Boolean): Palette {
        return if (dark) {
            Palette(false, DARK_FOREGROUND, DARK_BACKGROUND, DARK_SELECTION, DARK_CURSOR, darkAnsi)
        } else {
            Palette(false, LIGHT_FOREGROUND, LIGHT_BACKGROUND, LIGHT_SELECTION, LIGHT_CURSOR, lightAnsi)
        }
    }

    @JvmStatic
    fun apply(context: Context, adapter: CoreAdapter) {
        apply(
            TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE,
            adapter,
            ThemeUtils.shouldEnableDarkTheme(context, NightMode.getAppNightMode().name)
        )
    }

    @JvmStatic
    fun apply(colorsFile: File?, adapter: CoreAdapter?, dark: Boolean) {
        if (adapter == null || !adapter.supportsPalette()) return
        val palette = resolve(colorsFile, dark)
        adapter.setPalette(palette.foreground, palette.background, palette.selection, palette.cursor)
        adapter.setAnsiPalette(palette.ansi)
    }

    private fun parse(value: String?, fallback: Int): Int {
        return if (value == null) fallback else parseColor(value.trim()).takeUnless { it == 0 } ?: fallback
    }

    private fun parseColor(value: String): Int {
        return try {
            val skipInitial: Int
            val skipBetween: Int
            if (value[0] == '#') {
                skipInitial = 1
                skipBetween = 0
            } else if (value.startsWith("rgb:")) {
                skipInitial = 4
                skipBetween = 1
            } else {
                return 0
            }
            val charsForColors = value.length - skipInitial - 2 * skipBetween
            if (charsForColors % 3 != 0) return 0
            val componentLength = charsForColors / 3
            val mult = 255.0 / (Math.pow(2.0, componentLength * 4.0) - 1)
            var position = skipInitial
            val red = value.substring(position, position + componentLength)
            position += componentLength + skipBetween
            val green = value.substring(position, position + componentLength)
            position += componentLength + skipBetween
            val blue = value.substring(position, position + componentLength)
            val r = (red.toInt(16) * mult).toInt()
            val g = (green.toInt(16) * mult).toInt()
            val b = (blue.toInt(16) * mult).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (_: NumberFormatException) {
            0
        } catch (_: IndexOutOfBoundsException) {
            0
        }
    }

    private fun cursorFor(background: Int): Int {
        return if (isDarkBackground(background)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    @JvmStatic
    fun isDarkBackground(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val brightness = Math.sqrt(r * r * 0.241 + g * g * 0.691 + b * b * 0.068)
        return brightness < 130
    }
}
