package com.dtn.messenger.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.model.BpsecStatus
import com.dtn.messenger.data.model.BundleRecord
import com.dtn.messenger.data.model.BundleState
import com.dtn.messenger.data.model.LocalService
import com.dtn.messenger.service.DtnEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    serviceEid: String,
    localServiceDao: LocalServiceDao,
    bundleRecordDao: BundleRecordDao,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf<LocalService?>(null) }
    var selectedBundle by remember { mutableStateOf<BundleRecord?>(null) }
    val allBundles by bundleRecordDao.getAll().collectAsState(initial = emptyList())

    val messages =
        allBundles.filter {
            com.dtn.messenger.util.PayloadUtils.isPrefixMatch(serviceEid, it.destinationEid) ||
                com.dtn.messenger.util.PayloadUtils.isPrefixMatch(it.destinationEid, serviceEid) ||
                com.dtn.messenger.util.PayloadUtils.isPrefixMatch(serviceEid, it.sourceEid) ||
                com.dtn.messenger.util.PayloadUtils.isPrefixMatch(it.sourceEid, serviceEid)
        }

    var recipientEid by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf("") }

    LaunchedEffect(serviceEid, messages) {
        val s = localServiceDao.getById(serviceEid)
        service = s
        if (recipientEid.isEmpty()) {
            if (s?.defaultDestinationEid != null && s.defaultDestinationEid.isNotBlank()) {
                recipientEid = s.defaultDestinationEid
            } else {
                val partner =
                    messages.firstOrNull { it.sourceEid != serviceEid }?.sourceEid
                        ?: messages.firstOrNull { it.destinationEid != serviceEid }?.destinationEid
                        ?: "dtn://remote-node/chat"
                recipientEid = partner
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(service?.displayName ?: "CHAT", fontWeight = FontWeight.Bold, color = NeonCyan)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
            )
        },
        containerColor = CharcoalBg,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Recipient EID Input Card (beautiful glassmorphic design)
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TO:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    BasicTextField(
                        value = recipientEid,
                        onValueChange = { recipientEid = it },
                        textStyle =
                            TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                        cursorBrush = SolidColor(NeonCyan),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (recipientEid.isEmpty()) {
                                    Text("Enter destination EID...", color = TextGray, fontSize = 13.sp)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                reverseLayout = true,
            ) {
                items(messages) { msg ->
                    // Mark as read
                    LaunchedEffect(msg.bundleId) {
                        if (!msg.isRead && msg.destinationEid == serviceEid) {
                            bundleRecordDao.markAsRead(msg.bundleId, true)
                        }
                    }

                    val isMe = msg.sourceEid == serviceEid
                    val alignment = if (isMe) Alignment.End else Alignment.Start
                    val bubbleColor = if (isMe) NeonPurple.copy(alpha = 0.4f) else GlassCardColor
                    val outlineColor = if (isMe) NeonPurple else Color(0x33FFFFFF)

                    val isAudio =
                        remember(msg.payloadFilePath) {
                            com.dtn.messenger.util.PayloadUtils.isAudioPayload(msg.payloadFilePath)
                        }
                    val text by produceState(initialValue = "Loading...", msg.payloadFilePath) {
                        value =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    if (isAudio) {
                                        "Audio message"
                                    } else {
                                        val file = File(msg.payloadFilePath)
                                        if (file.exists()) String(file.readBytes(), Charsets.UTF_8) else "No content"
                                    }
                                } catch (e: Exception) {
                                    "Binary data"
                                }
                            }
                    }

                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
                        Card(
                            shape =
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (isMe) 16.dp else 0.dp,
                                    bottomEnd = if (isMe) 0.dp else 16.dp,
                                ),
                            colors = CardDefaults.cardColors(containerColor = bubbleColor),
                            border = BorderStroke(1.dp, outlineColor),
                            modifier =
                                Modifier
                                    .widthIn(max = 280.dp)
                                    .clickable {
                                        val target = if (isMe) msg.destinationEid else msg.sourceEid
                                        recipientEid = target
                                        Toast.makeText(context, "Recipient set to: $target", Toast.LENGTH_SHORT).show()
                                    },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (!isMe) {
                                    Text(
                                        msg.sourceEid,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = NeonCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                if (isAudio) {
                                    AudioPlayerCard(filePath = msg.payloadFilePath)
                                } else {
                                    Text(text, color = Color.White, fontSize = 15.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.creationTimestamp)),
                                        fontSize = 10.sp,
                                        color = TextGray,
                                    )
                                    Icon(
                                        imageVector =
                                            when (msg.bpsecStatus) {
                                                BpsecStatus.VALID -> Icons.Default.CheckCircle
                                                BpsecStatus.INVALID -> Icons.Default.Warning
                                                BpsecStatus.UNVERIFIED -> Icons.Default.Info
                                            },
                                        contentDescription = "BPSec Details",
                                        tint =
                                            when (msg.bpsecStatus) {
                                                BpsecStatus.VALID -> GlowGreen
                                                BpsecStatus.INVALID -> GlowRed
                                                BpsecStatus.UNVERIFIED -> TextGray
                                            },
                                        modifier =
                                            Modifier
                                                .size(16.dp)
                                                .clickable {
                                                    selectedBundle = msg
                                                },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom reply bar
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    placeholder = { Text("Write a message...", color = TextGray) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                )
                IconButton(
                    onClick = {
                        if (replyText.isNotBlank()) {
                            if (!com.dtn.messenger.util.PayloadUtils.isValidEid(recipientEid)) {
                                Toast.makeText(
                                    context,
                                    "Invalid Recipient EID format! (Must start with dtn:// or ipn:)",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@IconButton
                            }
                            scope.launch {
                                val bundleId = UUID.randomUUID().toString()
                                withContext(Dispatchers.IO) {
                                    // Save payload
                                    val payloadsDir = File(context.filesDir, "payloads")
                                    if (!payloadsDir.exists()) payloadsDir.mkdirs()

                                    val payloadFile = File(payloadsDir, "$bundleId.bin")
                                    FileOutputStream(payloadFile).use { fos ->
                                        fos.write(replyText.toByteArray(Charsets.UTF_8))
                                    }

                                    val record =
                                        BundleRecord(
                                            bundleId = bundleId,
                                            destinationEid = recipientEid,
                                            sourceEid = serviceEid,
                                            creationTimestamp = System.currentTimeMillis(),
                                            sequenceNumber = System.currentTimeMillis() % 100000,
                                            lifetimeMs = 3600000L,
                                            payloadFilePath = payloadFile.absolutePath,
                                            state = BundleState.OUTBOX,
                                            isRead = true,
                                            bpsecStatus = BpsecStatus.UNVERIFIED,
                                            hopCount = 0,
                                        )
                                    bundleRecordDao.insert(record)
                                }
                                replyText = ""

                                // Trigger service flush
                                val serviceIntent =
                                    Intent(context, DtnEngineService::class.java).apply {
                                        action = "FLUSH_QUEUE"
                                    }
                                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                            }
                        }
                    },
                    modifier = Modifier.background(NeonCyan, RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black)
                }
            }
        }
    }

    if (selectedBundle != null) {
        Dialog(
            onDismissRequest = { selectedBundle = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = CharcoalBg,
            ) {
                Column {
                    CenterAlignedTopAppBar(
                        title = { Text("BUNDLE DETAILS", fontWeight = FontWeight.Bold, color = NeonCyan) },
                        navigationIcon = {
                            IconButton(onClick = { selectedBundle = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
                    )
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        BundleDetailPane(
                            bundle = selectedBundle!!,
                            navController = navController,
                            bundleRecordDao = bundleRecordDao,
                            onClose = { selectedBundle = null },
                        )
                    }
                }
            }
        }
    }
}
