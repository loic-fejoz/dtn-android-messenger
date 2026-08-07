package com.dtn.messenger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke = BorderStroke(1.dp, Color(0x22FFFFFF)),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GlassCardColor),
        border = border,
        content = content
    )
}

@Composable
fun HeaderItem(label: String, value: String) {
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

@Composable
fun MarkdownText(text: String) {
    val lines = text.split("\n")
    var inCodeBlock = false
    val codeBlockContent = remember { mutableStateListOf<String>() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // End code block
                    val blockText = codeBlockContent.joinToString("\n")
                    Text(
                        blockText,
                        color = NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                    codeBlockContent.clear()
                    inCodeBlock = false
                } else {
                    // Start code block
                    inCodeBlock = true
                }
            } else if (inCodeBlock) {
                codeBlockContent.add(line)
            } else {
                if (trimmed == "---") {
                    HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                } else if (trimmed.startsWith("# ")) {
                    Text(
                        text = parseMarkdownInline(trimmed.substring(2)),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                } else if (trimmed.startsWith("## ")) {
                    Text(
                        text = parseMarkdownInline(trimmed.substring(3)),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonPurple,
                        modifier = Modifier.padding(top = 6.dp, bottom = 3.dp)
                    )
                } else if (trimmed.startsWith("### ")) {
                    Text(
                        text = parseMarkdownInline(trimmed.substring(4)),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("•", color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = parseMarkdownInline(line.substring(line.indexOf(' ') + 1)),
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                } else {
                    if (line.isNotEmpty()) {
                        Text(
                            text = parseMarkdownInline(line),
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
            i++
        }
        
        // In case code block didn't close
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            val blockText = codeBlockContent.joinToString("\n")
            Text(
                blockText,
                color = NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(8.dp)
            )
            codeBlockContent.clear()
        }
    }
}
