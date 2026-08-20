package com.dtn.messenger.ui

sealed class MarkdownLineElement {
    data class Text(val content: String) : MarkdownLineElement()
    data class Image(val altText: String, val url: String) : MarkdownLineElement()
}

object MarkdownImageParser {
    val imageRegex = Regex("""!\[(.*?)]\((.*?)\)""")

    fun decodeBase64DataUri(uri: String): ByteArray? {
        if (!uri.startsWith("data:image/", ignoreCase = true)) return null
        val commaIndex = uri.indexOf(',')
        if (commaIndex == -1) return null
        val header = uri.substring(0, commaIndex)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val base64Data = uri.substring(commaIndex + 1).trim()
        return try {
            decodeBase64(base64Data)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase64(base64Str: String): ByteArray {
        return try {
            android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            val getDecoder = Class.forName("java.util.Base64").getMethod("getDecoder")
            val decoder = getDecoder.invoke(null)
            val decode = decoder.javaClass.getMethod("decode", String::class.java)
            decode.invoke(decoder, base64Str) as ByteArray
        }
    }

    fun parseLineElements(line: String): List<MarkdownLineElement> {
        val elements = mutableListOf<MarkdownLineElement>()
        val matches = imageRegex.findAll(line).toList()
        if (matches.isEmpty()) {
            elements.add(MarkdownLineElement.Text(line))
        } else {
            var lastIndex = 0
            for (match in matches) {
                val matchRange = match.range
                if (matchRange.first > lastIndex) {
                    val textSegment = line.substring(lastIndex, matchRange.first)
                    if (textSegment.isNotEmpty()) {
                        elements.add(MarkdownLineElement.Text(textSegment))
                    }
                }
                val altText = match.groupValues[1]
                val imageUrl = match.groupValues[2]
                elements.add(MarkdownLineElement.Image(altText, imageUrl))
                lastIndex = matchRange.last + 1
            }
            if (lastIndex < line.length) {
                val textSegment = line.substring(lastIndex)
                if (textSegment.isNotEmpty()) {
                    elements.add(MarkdownLineElement.Text(textSegment))
                }
            }
        }
        return elements
    }
}
