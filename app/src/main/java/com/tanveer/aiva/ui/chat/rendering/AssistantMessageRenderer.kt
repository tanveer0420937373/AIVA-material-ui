
package com.tanveer.aiva.ui.chat.rendering

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tanveer.aiva.R

/** Renders Markdown as native Android views. Generated code is always shown as text, never executed. */
class AssistantMessageRenderer(
    private val context: Context,
    private val notify: (String) -> Unit,
) {
    private val inlineToken = Regex(
        """(?<!\\)`[^`\n]+`|(?<!\\)\[[^\]]+\]\((?:https?://|mailto:)[^)]+\)|(?<!\\)\*\*.+?\*\*|(?<!\\)__.+?__|(?<!\\)~~.+?~~|(?<![\w\\])\*[^*\n]+\*(?![\w])|(?<![\w\\])_[^_\n]+_(?![\w])"""
    )

    fun renderInto(
        container: LinearLayout,
        markdown: String,
        streaming: Boolean = false
    ) {
        container.removeAllViews()
        if (markdown.isEmpty()) return

        val segments = MessageContentParser.parse(markdown)

        if (segments.isEmpty()) {
            addParagraph(container, markdown)
            return
        }

        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Paragraph ->
                    addParagraph(container, segment.text)

                is MessageSegment.Heading ->
                    addHeading(container, segment)

                is MessageSegment.CodeBlock ->
                    addCodeBlock(container, segment, streaming)

                is MessageSegment.Quote ->
                    addQuote(container, segment.text)

                is MessageSegment.ListBlock ->
                    addList(container, segment.items)

                is MessageSegment.Table ->
                    addTable(container, segment)

                MessageSegment.Divider ->
                    addDivider(container)
            }
        }
    }

    fun renderPlainTextInto(
        container: LinearLayout,
        text: String,
        textColor: Int
    ) {
        container.removeAllViews()
        if (text.isEmpty()) return

        val view = TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            includeFontPadding = false
            setTextIsSelectable(true)
            layoutParams = blockParams(bottom = 0)
        }

        container.addView(view)
    }

    private fun addParagraph(container: LinearLayout, text: String) {
        if (text.isEmpty()) return

        val view = TextView(context).apply {
            this.text = inline(text)
            setTextColor(color(R.color.aiva_text))
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            includeFontPadding = false
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            layoutParams = blockParams(bottom = 10)
        }

        container.addView(view)
    }

    private fun addHeading(
        container: LinearLayout,
        heading: MessageSegment.Heading
    ) {
        val sizes = floatArrayOf(0f, 25f, 22f, 19f, 17f, 16f, 15f)

        val view = TextView(context).apply {
            text = inline(heading.text)
            setTextColor(color(R.color.aiva_text))
            textSize = sizes[heading.level.coerceIn(1, 6)]
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            layoutParams = blockParams(
                top = if (heading.level <= 2) 8 else 5,
                bottom = 8
            )
        }

        container.addView(view)
    }

    private fun addCodeBlock(
        container: LinearLayout,
        codeBlock: MessageSegment.CodeBlock,
        streaming: Boolean
    ) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(
                color(R.color.aiva_card),
                color(R.color.aiva_border),
                12
            )
            clipToOutline = true
            layoutParams = blockParams(bottom = 12)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(6), dp(5))
            background = solidBackground(color(R.color.aiva_background))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val languageLabel = displayLanguage(codeBlock.language)

        val language = TextView(context).apply {
            text = languageLabel
            textSize = 11f
            setTextColor(color(R.color.aiva_muted))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            contentDescription = "Code language: $languageLabel"
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val copy = TextView(context).apply {
            text = "▢  Copy code"
            textSize = 12f
            setTextColor(color(R.color.aiva_primary))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            minHeight = dp(40)
            minWidth = dp(44)
            isFocusable = true
            isClickable = true
            contentDescription = "Copy this code block"
            background = selectableBackground()

            setOnClickListener {
                copyCode(codeBlock.code)
            }
        }

        header.addView(language)
        header.addView(copy)
        card.addView(header)

        val separator = View(context).apply {
            setBackgroundColor(color(R.color.aiva_border))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }

        card.addView(separator)

        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val codeText = TextView(context).apply {
            text = codeBlock.code.ifEmpty {
                if (streaming && !codeBlock.complete) {
                    "Waiting for code…"
                } else {
                    ""
                }
            }

            setTextColor(color(R.color.aiva_text))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = true
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(16), dp(12))

            // Keep long source lines horizontally scrollable.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            contentDescription = "Source code"
        }

        horizontal.addView(codeText)
        card.addView(horizontal)
        container.addView(card)
    }

    private fun addQuote(container: LinearLayout, text: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = blockParams(bottom = 10)
        }

        val stripe = View(context).apply {
            setBackgroundColor(color(R.color.aiva_primary))
            layoutParams = LinearLayout.LayoutParams(
                dp(3),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = dp(10)
            }
        }

        val quote = TextView(context).apply {
            this.text = inline(text)
            setTextColor(color(R.color.aiva_muted))
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(2), 0, dp(2))
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        row.addView(stripe)
        row.addView(quote)
        container.addView(row)
    }

    private fun addList(
        container: LinearLayout,
        items: List<ListItem>
    ) {
        items.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = blockParams(bottom = 5).apply {
                    marginStart = dp(4 + item.depth * 16)
                }
            }

            val marker = TextView(context).apply {
                text = if (item.ordered) item.marker else "•"
                textSize = 14f
                setTextColor(color(R.color.aiva_text))
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(1), dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    dp(if (item.ordered) 28 else 18),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val text = TextView(context).apply {
                this.text = inline(item.text)
                setTextColor(color(R.color.aiva_text))
                textSize = 15f
                setLineSpacing(dp(3).toFloat(), 1f)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            row.addView(marker)
            row.addView(text)
            container.addView(row)
        }
    }

    private fun addTable(
        container: LinearLayout,
        table: MessageSegment.Table
    ) {
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            layoutParams = blockParams(bottom = 12)
        }

        val tableLayout = TableLayout(context).apply {
            isStretchAllColumns = false
            isShrinkAllColumns = false
            background = roundedBackground(
                color(R.color.aiva_card),
                color(R.color.aiva_border),
                8
            )
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeRow(cells: List<String>, header: Boolean): TableRow {
            return TableRow(context).apply {
                cells.forEach { cell ->
                    val cellView = TextView(context).apply {
                        text = inline(cell)
                        textSize = 13f
                        setTextColor(
                            color(
                                if (header) {
                                    R.color.aiva_primary
                                } else {
                                    R.color.aiva_text
                                }
                            )
                        )

                        if (header) {
                            setTypeface(typeface, Typeface.BOLD)
                        }

                        setPadding(dp(10), dp(9), dp(10), dp(9))
                        minWidth = dp(86)
                        maxWidth = dp(260)
                        gravity = Gravity.CENTER_VERTICAL
                        movementMethod = LinkMovementMethod.getInstance()
                        setTextIsSelectable(true)

                        background = solidBackground(
                            color(
                                if (header) {
                                    R.color.aiva_accent
                                } else {
                                    R.color.aiva_card
                                }
                            )
                        )

                        layoutParams = TableRow.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, dp(1), dp(1))
                        }
                    }

                    addView(cellView)
                }
            }
        }

        tableLayout.addView(makeRow(table.headers, true))
        table.rows.forEach { row ->
            tableLayout.addView(makeRow(row, false))
        }

        horizontal.addView(tableLayout)
        container.addView(horizontal)
    }

    private fun addDivider(container: LinearLayout) {
        val view = View(context).apply {
            setBackgroundColor(color(R.color.aiva_border))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
        }

        container.addView(view)
    }

    private fun inline(
        text: String,
        depth: Int = 0
    ): CharSequence {
        if (text.isEmpty() || depth > 4) return text

        val out = SpannableStringBuilder()
        var cursor = 0

        inlineToken.findAll(text).forEach { match ->
            if (match.range.first < cursor) return@forEach

            out.append(text.substring(cursor, match.range.first))
            val token = match.value

            when {
                token.startsWith("`") && token.endsWith("`") -> {
                    val start = out.length
                    out.append(token.substring(1, token.length - 1))
                    val end = out.length

                    out.setSpan(
                        TypefaceSpan("monospace"),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    out.setSpan(
                        BackgroundColorSpan(color(R.color.aiva_accent)),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                token.startsWith("[") -> {
                    val closeLabel = token.indexOf("](")
                    val label = token.substring(1, closeLabel)
                    val target = token.substring(
                        closeLabel + 2,
                        token.length - 1
                    )

                    val start = out.length
                    out.append(inline(label, depth + 1))
                    val end = out.length

                    out.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                openLink(target)
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = color(R.color.aiva_primary)
                                ds.isUnderlineText = true
                            }
                        },
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                token.startsWith("**") || token.startsWith("__") -> {
                    val inner = token.substring(2, token.length - 2)
                    val start = out.length
                    out.append(inline(inner, depth + 1))

                    out.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                token.startsWith("~~") -> {
                    val start = out.length
                    out.append(
                        inline(token.substring(2, token.length - 2), depth + 1)
                    )

                    out.setSpan(
                        StrikethroughSpan(),
                        start,
                        out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                token.startsWith("*") || token.startsWith("_") -> {
                    val start = out.length
                    out.append(
                        inline(token.substring(1, token.length - 1), depth + 1)
                    )

                    out.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start,
                        out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            out.append(text.substring(cursor))
        }

        return out
    }

    private fun copyCode(code: String) {
        if (code.isEmpty()) {
            notify("This code block is empty")
            return
        }

        runCatching {
            val clipboard = context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText("AIVA code", code)
            )
        }.onSuccess {
            notify("Code copied to clipboard")
        }.onFailure {
            notify(
                "Couldn’t copy code. Try selecting the code and copying it manually."
            )
        }
    }

    private fun openLink(target: String) {
        val uri = runCatching {
            Uri.parse(target)
        }.getOrNull() ?: run {
            notify("This link is invalid")
            return
        }

        val scheme = uri.scheme?.lowercase()

        if (scheme == null || scheme !in setOf("http", "https", "mailto")) {
            notify("For safety, only web and email links can be opened")
            return
        }

        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri)

            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        }.onFailure {
            notify("Couldn’t open this link")
        }
    }

    private fun displayLanguage(language: String): String {
        if (language.isBlank()) return "CODE"

        return when (language.lowercase()) {
            "js", "jsx", "javascript" -> "JAVASCRIPT"
            "ts", "tsx", "typescript" -> "TYPESCRIPT"
            "kt", "kts", "kotlin" -> "KOTLIN"
            "py", "python" -> "PYTHON"
            "html", "htm" -> "HTML"
            "css" -> "CSS"
            "xml" -> "XML"
            "json", "jsonc" -> "JSON"
            "java" -> "JAVA"
            "sh", "bash", "shell", "zsh" -> "BASH"
            "sql" -> "SQL"
            "md", "markdown" -> "MARKDOWN"
            "yml", "yaml" -> "YAML"
            "c++", "cpp", "cc" -> "C++"
            "c#", "csharp" -> "C#"
            else -> language.take(20).uppercase()
        }
    }

    private fun blockParams(
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun solidBackground(fill: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
        }
    }

    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        return ContextCompat.getDrawable(
            context,
            android.R.drawable.list_selector_background
        )
    }

    private fun color(id: Int): Int {
        return ContextCompat.getColor(context, id)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
