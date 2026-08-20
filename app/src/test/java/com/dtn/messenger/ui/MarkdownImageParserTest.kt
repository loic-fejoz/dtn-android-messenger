package com.dtn.messenger.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MarkdownImageParserTest {

    @Test
    fun testParseLineWithoutImages() {
        val line = "This is a simple line without images."
        val elements = MarkdownImageParser.parseLineElements(line)
        assertEquals(1, elements.size)
        assertEquals(MarkdownLineElement.Text("This is a simple line without images."), elements[0])
    }

    @Test
    fun testParseLineWithSingleImage() {
        val line = "Here is an image: ![alt text](data:image/png;base64,SGVsbG8=)"
        val elements = MarkdownImageParser.parseLineElements(line)
        assertEquals(2, elements.size)
        assertEquals(MarkdownLineElement.Text("Here is an image: "), elements[0])
        assertEquals(MarkdownLineElement.Image("alt text", "data:image/png;base64,SGVsbG8="), elements[1])
    }

    @Test
    fun testParseLineWithMultipleImages() {
        val line = "![img1](url1) middle text ![img2](url2)"
        val elements = MarkdownImageParser.parseLineElements(line)
        assertEquals(3, elements.size)
        assertEquals(MarkdownLineElement.Image("img1", "url1"), elements[0])
        assertEquals(MarkdownLineElement.Text(" middle text "), elements[1])
        assertEquals(MarkdownLineElement.Image("img2", "url2"), elements[2])
    }

    @Test
    fun testDecodeBase64DataUri() {
        // "Hello" in base64 is "SGVsbG8="
        val validUri = "data:image/png;base64,SGVsbG8="
        val decoded = MarkdownImageParser.decodeBase64DataUri(validUri)
        assertNotNull(decoded)
        assertEquals("Hello", String(decoded!!))

        val invalidUri = "data:text/plain;base64,SGVsbG8="
        assertNull(MarkdownImageParser.decodeBase64DataUri(invalidUri))

        val nonBase64Uri = "data:image/png,hello"
        assertNull(MarkdownImageParser.decodeBase64DataUri(nonBase64Uri))
    }
}
