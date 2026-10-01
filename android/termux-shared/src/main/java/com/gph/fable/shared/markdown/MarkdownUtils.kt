package com.gph.fable.shared.markdown

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import com.google.common.base.Strings
import com.gph.fable.shared.R
import com.gph.fable.shared.theme.ThemeUtils
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.StrongEmphasis
import java.util.regex.Matcher
import java.util.regex.Pattern
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin

object MarkdownUtils {

    const val backtick = "`"
    @JvmField
    val backticksPattern = Pattern.compile("(" + backtick + "+)")

    /**
     * Get the markdown code [String] for a [String]. This ensures all backticks "`" are
     * properly escaped so that markdown does not break.
     */
    @JvmStatic
    fun getMarkdownCodeForString(string: String?, codeBlock: Boolean): String? {
        if (string == null) return null
        if (string.isEmpty()) return ""

        var string = string
        val maxConsecutiveBackTicksCount = getMaxConsecutiveBackTicksCount(string)

        // markdown requires surrounding backticks count to be at least one more than the count
        // of consecutive ticks in the string itself
        val backticksCountToUse = if (codeBlock)
            maxConsecutiveBackTicksCount + 3
        else
            maxConsecutiveBackTicksCount + 1

        // create a string with n backticks where n==backticksCountToUse
        val backticksToUse = Strings.repeat(backtick, backticksCountToUse)

        return if (codeBlock)
            backticksToUse + "\n" + string + "\n" + backticksToUse
        else {
            // add a space to any prefixed or suffixed backtick characters
            if (string.startsWith(backtick))
                string = " " + string
            if (string.endsWith(backtick))
                string = string + " "

            backticksToUse + string + backticksToUse
        }
    }

    /**
     * Get the max consecutive backticks "`" in a [String].
     */
    @JvmStatic
    fun getMaxConsecutiveBackTicksCount(string: String?): Int {
        if (string == null || string.isEmpty()) return 0

        var maxCount = 0
        var matchCount: Int
        var match: String?

        val matcher: Matcher = backticksPattern.matcher(string)
        while (matcher.find()) {
            match = matcher.group(1)
            matchCount = if (match != null) match.length else 0
            if (matchCount > maxCount)
                maxCount = matchCount
        }

        return maxCount
    }

    @JvmStatic
    fun getLiteralSingleLineMarkdownStringEntry(label: String, obj: Any?, def: String?): String {
        return "**" + label + "**: " + (if (obj != null) obj.toString() else def) + "  "
    }

    @JvmStatic
    fun getSingleLineMarkdownStringEntry(label: String, obj: Any?, def: String?): String {
        return if (obj != null)
            "**" + label + "**: " + getMarkdownCodeForString(obj.toString(), false) + "  "
        else
            "**" + label + "**: " + def + "  "
    }

    @JvmStatic
    fun getMultiLineMarkdownStringEntry(label: String, obj: Any?, def: String?): String {
        return if (obj != null)
            "**" + label + "**:\n" + getMarkdownCodeForString(obj.toString(), true) + "\n"
        else
            "**" + label + "**: " + def + "\n"
    }

    @JvmStatic
    fun getLinkMarkdownString(label: String, url: String?): String {
        return if (url != null)
            "[" + label.replace("]", "\\]") + "](" + url.replace(")", "\\)") + ")"
        else
            label
    }

    @JvmStatic
    fun getRecyclerMarkwonBuilder(context: Context): Markwon {
        return Markwon.builder(context)
            .usePlugin(LinkifyPlugin.create(Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureVisitor(@NonNull builder: MarkwonVisitor.Builder) {
                    builder.on(FencedCodeBlock::class.java) { visitor, fencedCodeBlock ->
                        // we actually won't be applying code spans here, as our custom xml view will
                        // draw background and apply mono typeface
                        //
                        // NB the `trim` operation on literal (as code will have a new line at the end)
                        val code = visitor.configuration()
                            .syntaxHighlight()
                            .highlight(fencedCodeBlock.info, fencedCodeBlock.literal.trim())
                        visitor.builder().append(code)
                    }
                }

                override fun configureSpansFactory(@NonNull builder: MarkwonSpansFactory.Builder) {
                    // Do not change color for night themes
                    if (!ThemeUtils.isNightModeEnabled(context)) {
                        builder
                            // set color for inline code
                            .setFactory(Code::class.java) { configuration, props ->
                                arrayOf<Any>(
                                    BackgroundColorSpan(ContextCompat.getColor(context, R.color.background_markdown_code_inline))
                                )
                            }
                    }
                }
            })
            .build()
    }

    @JvmStatic
    fun getSpannedMarkwonBuilder(context: Context): Markwon {
        return Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(@NonNull builder: MarkwonSpansFactory.Builder) {
                    builder
                        .setFactory(Emphasis::class.java) { configuration, props -> StyleSpan(Typeface.ITALIC) }
                        .setFactory(StrongEmphasis::class.java) { configuration, props -> StyleSpan(Typeface.BOLD) }
                        .setFactory(BlockQuote::class.java) { configuration, props -> QuoteSpan() }
                        .setFactory(Strikethrough::class.java) { configuration, props -> StrikethroughSpan() }
                        // NB! notification does not handle background color
                        .setFactory(Code::class.java) { configuration, props ->
                            arrayOf<Any>(
                                BackgroundColorSpan(ContextCompat.getColor(context, R.color.background_markdown_code_inline)),
                                TypefaceSpan("monospace"),
                                AbsoluteSizeSpan(48)
                            )
                        }
                        // NB! both ordered and bullet list items
                        .setFactory(ListItem::class.java) { configuration, props -> BulletSpan() }
                }
            })
            .build()
    }

    @JvmStatic
    fun getSpannedMarkdownText(context: Context?, string: String?): Spanned? {
        if (context == null || string == null) return null
        val markwon = getSpannedMarkwonBuilder(context)
        return markwon.toMarkdown(string)
    }
}
