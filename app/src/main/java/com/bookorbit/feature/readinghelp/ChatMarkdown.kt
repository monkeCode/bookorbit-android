package com.bookorbit.feature.readinghelp

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Native selectable text. No WebView, remote images, HTML execution or automatic link opening. */
@Composable
fun ChatMarkdown(text: String) {
    val rendered = remember(text) { chatMarkdown(text) }
    SelectionContainer { Text(rendered, style = MaterialTheme.typography.bodyLarge) }
}

internal fun chatMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    fun visit(node: Node) {
        val style = when (node) {
            is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
            is Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
            is Code, is FencedCodeBlock, is IndentedCodeBlock -> SpanStyle(fontFamily = FontFamily.Monospace)
            is Heading -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (24 - node.level).sp)
            is Link -> SpanStyle(textDecoration = TextDecoration.Underline)
            else -> null
        }
        if (style != null) pushStyle(style)
        when (node) {
            is org.commonmark.node.Text -> append(node.literal)
            is Code -> append(node.literal)
            is FencedCodeBlock -> append(node.literal)
            is IndentedCodeBlock -> append(node.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is HtmlInline -> append(node.literal) // Literal text, not interpreted markup.
            is HtmlBlock -> append(node.literal)
            is ThematicBreak -> append("────────\n")
            else -> {
                if (node is ListItem) {
                    val list = node.parent
                    if (list is OrderedList) {
                        var number = list.markerStartNumber ?: 1
                        var previous = node.previous
                        while (previous != null) { number++; previous = previous.previous }
                        append("$number. ")
                    } else append("• ")
                }
                if (node is BlockQuote) append("│ ")
                var child = node.firstChild
                while (child != null) { visit(child); child = child.next }
            }
        }
        if (style != null) pop()
        if (node is Paragraph || node is Heading || node is FencedCodeBlock || node is IndentedCodeBlock || node is ListItem || node is BlockQuote) append('\n')
    }
    visit(Parser.builder().build().parse(text.take(32_768)))
}
