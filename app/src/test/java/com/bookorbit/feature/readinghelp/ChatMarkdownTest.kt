package com.bookorbit.feature.readinghelp

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class ChatMarkdownTest {
    @Test fun `markdown produces selectable native spans without fetching images or executing HTML`() {
        val result = chatMarkdown("# Heading\n\n**Bold** and *italic* `code`\n\n- one\n- two\n\n![alt](https://private.example/image)\n\n<script>alert(1)</script>")
        assertTrue(result.text.contains("Heading")); assertTrue(result.text.contains("Bold"))
        assertFalse(result.text.contains("**")); assertTrue(result.text.contains("• one"))
        assertFalse(result.text.contains("private.example"))
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.getStringAnnotations(0, result.length).isEmpty())
    }
}
