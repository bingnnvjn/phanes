package com.gph.fable.shared.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.TypedArray
import androidx.appcompat.app.AppCompatActivity

object ThemeUtils {

    const val ATTR_TEXT_COLOR_PRIMARY = android.R.attr.textColorPrimary
    const val ATTR_TEXT_COLOR_SECONDARY = android.R.attr.textColorSecondary
    const val ATTR_TEXT_COLOR = android.R.attr.textColor
    const val ATTR_TEXT_COLOR_LINK = android.R.attr.textColorLink

    /**
     * Will return true if system has enabled night mode.
     */
    @JvmStatic
    fun isNightModeEnabled(context: Context?): Boolean {
        if (context == null) return false
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Will return true if mode is set to [NightMode.TRUE], otherwise will return true if
     * mode is set to [NightMode.SYSTEM] and night mode is enabled by system. */
    @JvmStatic
    fun shouldEnableDarkTheme(context: Context?, name: String?): Boolean {
        return when {
            NightMode.TRUE.name == name -> true
            NightMode.FALSE.name == name -> false
            NightMode.SYSTEM.name == name -> isNightModeEnabled(context)
            else -> false
        }
    }

    /** Get [ATTR_TEXT_COLOR_PRIMARY] value being used by current theme. */
    @JvmStatic
    fun getTextColorPrimary(context: Context?): Int {
        return getSystemAttrColor(context, ATTR_TEXT_COLOR_PRIMARY)
    }

    /** Get [ATTR_TEXT_COLOR_SECONDARY] value being used by current theme. */
    @JvmStatic
    fun getTextColorSecondary(context: Context?): Int {
        return getSystemAttrColor(context, ATTR_TEXT_COLOR_SECONDARY)
    }

    /** Get [ATTR_TEXT_COLOR] value being used by current theme. */
    @JvmStatic
    fun getTextColor(context: Context?): Int {
        return getSystemAttrColor(context, ATTR_TEXT_COLOR)
    }

    /** Get [ATTR_TEXT_COLOR_LINK] value being used by current theme. */
    @JvmStatic
    fun getTextColorLink(context: Context?): Int {
        return getSystemAttrColor(context, ATTR_TEXT_COLOR_LINK)
    }

    /** Wrapper for [getSystemAttrColor] with `def` value `0`. */
    @JvmStatic
    fun getSystemAttrColor(context: Context?, attr: Int): Int {
        return getSystemAttrColor(context, attr, 0)
    }

    /**
     * Get a values defined by the current theme listed in attrs.
     */
    @JvmStatic
    fun getSystemAttrColor(context: Context?, attr: Int, def: Int): Int {
        val typedArray: TypedArray = context!!.theme.obtainStyledAttributes(intArrayOf(attr))
        val color = typedArray.getColor(0, def)
        typedArray.recycle()
        return color
    }
}
