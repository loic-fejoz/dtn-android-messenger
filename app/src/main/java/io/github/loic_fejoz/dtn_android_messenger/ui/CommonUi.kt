package io.github.loic_fejoz.dtn_android_messenger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke = BorderStroke(1.dp, Color(0x22FFFFFF)),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GlassCardColor),
        border = border,
        content = content,
    )
}

@Composable
fun HeaderItem(
    label: String,
    value: String,
) {
    Column {
        Text(label.uppercase(), fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 13.sp, color = Color.White, fontFamily = FontFamily.Monospace)
    }
}

fun parseMarkdownInline(text: String): AnnotatedString {
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            if (text.startsWith("**", index)) {
                val endIndex = text.indexOf("**", index + 2)
                if (endIndex != -1) {
                    val boldText = text.substring(index + 2, endIndex)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(boldText)
                    pop()
                    index = endIndex + 2
                    continue
                }
            }
            if (text.startsWith("*", index)) {
                val endIndex = text.indexOf("*", index + 1)
                if (endIndex != -1) {
                    val italicText = text.substring(index + 1, endIndex)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(italicText)
                    pop()
                    index = endIndex + 1
                    continue
                }
            }
            if (text.startsWith("`", index)) {
                val endIndex = text.indexOf("`", index + 1)
                if (endIndex != -1) {
                    val codeText = text.substring(index + 1, endIndex)
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = NeonCyan, background = Color.Black.copy(alpha = 0.3f)))
                    append(codeText)
                    pop()
                    index = endIndex + 1
                    continue
                }
            }
            append(text[index])
            index++
        }
    }
}

private sealed class MarkdownBlock {
    data class CodeBlock(val content: String) : MarkdownBlock()
    data object Divider : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class BulletItem(val content: String) : MarkdownBlock()
    data class Paragraph(val line: String) : MarkdownBlock()
    data object BlankLine : MarkdownBlock()
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<MarkdownBlock>()
    var inCodeBlock = false
    val codeBuffer = StringBuilder()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeBuffer.toString()))
                codeBuffer.clear()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
        } else if (inCodeBlock) {
            if (codeBuffer.isNotEmpty()) codeBuffer.append('\n')
            codeBuffer.append(line)
        } else {
            when {
                trimmed == "---" -> blocks.add(MarkdownBlock.Divider)
                trimmed.startsWith("# ") -> blocks.add(MarkdownBlock.Heading(1, trimmed.substring(2)))
                trimmed.startsWith("## ") -> blocks.add(MarkdownBlock.Heading(2, trimmed.substring(3)))
                trimmed.startsWith("### ") -> blocks.add(MarkdownBlock.Heading(3, trimmed.substring(4)))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    blocks.add(MarkdownBlock.BulletItem(line.substring(line.indexOf(' ') + 1)))
                }
                line.isNotEmpty() -> blocks.add(MarkdownBlock.Paragraph(line))
                else -> blocks.add(MarkdownBlock.BlankLine)
            }
        }
    }

    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        blocks.add(MarkdownBlock.CodeBlock(codeBuffer.toString()))
    }
    return blocks
}

@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    Text(
                        block.content,
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(8.dp),
                    )
                }
                MarkdownBlock.Divider -> {
                    HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                }
                is MarkdownBlock.Heading -> {
                    when (block.level) {
                        1 -> Text(
                            text = parseMarkdownInline(block.text),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        2 -> Text(
                            text = parseMarkdownInline(block.text),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonPurple,
                            modifier = Modifier.padding(top = 6.dp, bottom = 3.dp),
                        )
                        else -> Text(
                            text = parseMarkdownInline(block.text),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("•", color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        val elements = MarkdownImageParser.parseLineElements(block.content)
                        Box(modifier = Modifier.weight(1f)) {
                            RenderLineContent(elements = elements, fontSize = 14.sp, color = Color.White)
                        }
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    val elements = MarkdownImageParser.parseLineElements(block.line)
                    RenderLineContent(elements = elements, fontSize = 14.sp, color = Color.White)
                }
                MarkdownBlock.BlankLine -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun MarkdownImage(imageUrl: String, altText: String) {
    val model: Any? = remember(imageUrl) {
        if (imageUrl.startsWith("data:", ignoreCase = true)) {
            MarkdownImageParser.decodeBase64DataUri(imageUrl)
        } else {
            imageUrl
        }
    }

    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = altText.ifEmpty { "Markdown Image" },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(4.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = "[Image could not be loaded: $altText]",
            color = Color.Red,
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic
        )
    }
}

@Composable
fun RenderLineContent(
    elements: List<MarkdownLineElement>,
    fontSize: TextUnit = 14.sp,
    color: Color = Color.White,
) {
    if (elements.size == 1 && elements[0] is MarkdownLineElement.Text) {
        Text(
            text = parseMarkdownInline((elements[0] as MarkdownLineElement.Text).content),
            fontSize = fontSize,
            color = color,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (element in elements) {
                when (element) {
                    is MarkdownLineElement.Text -> {
                        Text(
                            text = parseMarkdownInline(element.content),
                            fontSize = fontSize,
                            color = color,
                        )
                    }
                    is MarkdownLineElement.Image -> {
                        MarkdownImage(imageUrl = element.url, altText = element.altText)
                    }
                }
            }
        }
    }
}
