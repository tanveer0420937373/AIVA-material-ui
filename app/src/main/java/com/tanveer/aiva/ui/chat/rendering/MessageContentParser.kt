package com.tanveer.aiva.ui.chat.rendering

/** A small, dependency-free block parser for common model-generated Markdown. */
sealed class MessageSegment {
    data class Paragraph(val text: String) : MessageSegment()
    data class Heading(val level: Int, val text: String) : MessageSegment()
    data class CodeBlock(val language: String, val code: String, val complete: Boolean) : MessageSegment()
    data class Quote(val text: String) : MessageSegment()
    data class ListBlock(val items: List<ListItem>) : MessageSegment()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MessageSegment()
    object Divider : MessageSegment()
}

data class ListItem(val marker: String, val depth: Int, val text: String, val ordered: Boolean)

object MessageContentParser {
    private val fenceStart = Regex("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)?.*$")
    private val heading = Regex("^ {0,3}(#{1,6})[ \\t]+(.+?)\\s*#*\\s*$")
    private val listItem = Regex("^(\\s*)([-+*]|\\d+[.)])[ \\t]+(.*)$")
    private val divider = Regex("^\\s*(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
    private val tableDividerCell = Regex("^:?-{3,}:?$")

    fun parse(markdown: String): List<MessageSegment> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = mutableListOf<MessageSegment>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            val fence = fenceStart.matchEntire(line)
            if (fence != null) {
                val fenceToken = fence.groupValues[1]
                val fenceChar = fenceToken.first()
                val fenceLength = fenceToken.length
                val language = fence.groupValues.getOrNull(2).orEmpty().trim().lowercase()
                val codeLines = mutableListOf<String>()
                index++
                var closed = false
                while (index < lines.size) {
                    val candidate = lines[index]
                    val trimmed = candidate.trim()
                    if (trimmed.length >= fenceLength && trimmed.all { it == fenceChar }) {
                        closed = true
                        index++
                        break
                    }
                    codeLines += candidate
                    index++
                }
                result += MessageSegment.CodeBlock(language, codeLines.joinToString("\n"), closed)
                continue
            }

            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                result += MessageSegment.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2])
                index++
                continue
            }

            if (divider.matches(line)) {
                result += MessageSegment.Divider
                index++
                continue
            }

            if (line.trimStart().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quoteLines += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                    index++
                }
                result += MessageSegment.Quote(quoteLines.joinToString("\n"))
                continue
            }

            if (index + 1 < lines.size && line.contains('|') && isTableDivider(lines[index + 1])) {
                val headers = splitTableRow(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                    rows += splitTableRow(lines[index])
                    index++
                }
                val normalizedRows = rows.map { row ->
                    (0 until maxOf(headers.size, row.size)).map { column -> row.getOrElse(column) { "" } }
                }
                result += MessageSegment.Table(headers, normalizedRows)
                continue
            }

            if (listItem.matches(line)) {
                val items = mutableListOf<ListItem>()
                while (index < lines.size) {
                    val match = listItem.matchEntire(lines[index]) ?: break
                    val indent = match.groupValues[1].replace("\t", "    ").length
                    val marker = match.groupValues[2]
                    items += ListItem(
                        marker = marker,
                        depth = (indent / 2).coerceAtMost(8),
                        text = match.groupValues[3],
                        ordered = marker.firstOrNull()?.isDigit() == true,
                    )
                    index++
                }
                result += MessageSegment.ListBlock(items)
                continue
            }

            val paragraphLines = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                if (paragraphLines.isNotEmpty() && isBlockStart(lines, index)) break
                paragraphLines += lines[index]
                index++
            }
            if (paragraphLines.isNotEmpty()) {
                result += MessageSegment.Paragraph(paragraphLines.joinToString("\n"))
            } else {
                // Defensive progress guard for malformed input.
                result += MessageSegment.Paragraph(lines[index])
                index++
            }
        }
        return result
    }

    private fun isBlockStart(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return fenceStart.matches(line) || heading.matches(line) || divider.matches(line) ||
            line.trimStart().startsWith(">") || listItem.matches(line) ||
            (index + 1 < lines.size && line.contains('|') && isTableDivider(lines[index + 1]))
    }

    private fun isTableDivider(line: String): Boolean {
        if (!line.contains('|')) return false
        val cells = splitTableRow(line)
        return cells.isNotEmpty() && cells.all { tableDividerCell.matches(it.replace(" ", "")) }
    }

    private fun splitTableRow(line: String): List<String> {
        var value = line.trim()
        if (value.startsWith('|')) value = value.drop(1)
        if (value.endsWith('|')) value = value.dropLast(1)
        // Markdown table cells rarely need literal pipes; preserve escaped pipes safely.
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var escaped = false
        value.forEach { char ->
            when {
                escaped -> {
                    if (char != '|' && char != '\\') cell.append('\\')
                    cell.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '|' -> {
                    cells += cell.toString().trim()
                    cell.setLength(0)
                }
                else -> cell.append(char)
            }
        }
        if (escaped) cell.append('\\')
        cells += cell.toString().trim()
        return cells
    }
}
