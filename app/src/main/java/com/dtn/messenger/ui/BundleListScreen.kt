package com.dtn.messenger.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.model.BpsecStatus
import com.dtn.messenger.data.model.BundleRecord
import com.dtn.messenger.data.model.LocalService
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleListScreen(
    navController: NavController,
    serviceEid: String,
    localServiceDao: LocalServiceDao,
    bundleRecordDao: BundleRecordDao,
) {
    var service by remember { mutableStateOf<LocalService?>(null) }
    val allBundles by bundleRecordDao.getAll().collectAsState(initial = emptyList())
    val messages = allBundles.filter { it.destinationEid == serviceEid }

    var selectedBundle by remember { mutableStateOf<BundleRecord?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(serviceEid) {
        service = localServiceDao.getById(serviceEid)
    }

    if (selectedBundle == null) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(service?.displayName ?: "BUNDLE EXCHANGE", fontWeight = FontWeight.Bold, color = NeonCyan) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        val sourceSuffix = serviceEid.substringAfterLast("/")
                        navController.navigate("send_bundle?sourceService=${Uri.encode(sourceSuffix)}")
                    },
                    containerColor = NeonCyan,
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send bundle")
                }
            },
            containerColor = CharcoalBg,
        ) { paddingValues ->
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No bundles received", color = TextGray, fontSize = 14.sp)
                        }
                    }
                } else {
                    items(messages) { bundle ->
                        GlassCard(
                            onClick = {
                                selectedBundle = bundle
                                scope.launch {
                                    bundleRecordDao.markAsRead(bundle.bundleId, true)
                                }
                            },
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "From: ${bundle.sourceEid}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector =
                                            when (bundle.bpsecStatus) {
                                                BpsecStatus.VALID -> Icons.Default.CheckCircle
                                                BpsecStatus.INVALID -> Icons.Default.Warning
                                                BpsecStatus.UNVERIFIED -> Icons.Default.Info
                                            },
                                        contentDescription = "BPSec",
                                        tint =
                                            when (bundle.bpsecStatus) {
                                                BpsecStatus.VALID -> GlowGreen
                                                BpsecStatus.INVALID -> GlowRed
                                                BpsecStatus.UNVERIFIED -> TextGray
                                            },
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                val size =
                                    try {
                                        File(bundle.payloadFilePath).length()
                                    } catch (e: Exception) {
                                        0L
                                    }
                                Text(
                                    "Date: ${SimpleDateFormat(
                                        "yyyy-MM-dd HH:mm",
                                        Locale.getDefault(),
                                    ).format(Date(bundle.creationTimestamp))} | Size: $size bytes",
                                    color = TextGray,
                                    fontSize = 12.sp,
                                )
                                if (!bundle.isRead) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier =
                                            Modifier
                                                .background(NeonPurple, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("BUNDLE DETAILS", fontWeight = FontWeight.Bold, color = NeonCyan) },
                    navigationIcon = {
                        IconButton(onClick = { selectedBundle = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
                )
            },
            containerColor = CharcoalBg,
        ) { paddingValues ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            ) {
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

private fun downloadBundlePayload(
    context: Context,
    bundle: BundleRecord,
) {
    try {
        val sourceFile = File(bundle.payloadFilePath)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "Error: Payload file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val extension = com.dtn.messenger.util.PayloadUtils.getPayloadFileExtension(bundle.payloadFilePath)
        val displayName = "dtn_payload_${bundle.bundleId}.$extension"

        val resolver = context.contentResolver
        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                val mime =
                    when (extension) {
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "bmp" -> "image/bmp"
                        "txt" -> "text/plain"
                        "md" -> "text/markdown"
                        else -> "application/octet-stream"
                    }
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri).use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output!!)
                }
            }
            Toast.makeText(context, "Downloaded to Downloads/$displayName", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error saving file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun BundleDetailPane(
    bundle: BundleRecord,
    navController: NavController,
    bundleRecordDao: BundleRecordDao,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val payloadText =
        remember(bundle.bundleId) {
            try {
                val file = File(bundle.payloadFilePath)
                if (file.exists()) String(file.readBytes(), Charsets.UTF_8) else "No payload content"
            } catch (e: Exception) {
                "Unable to parse payload as UTF-8 string."
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("BUNDLE HEADERS", fontWeight = FontWeight.Bold, color = NeonCyan, fontSize = 14.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1. Reply
                IconButton(
                    onClick = {
                        val sourceSuffix = bundle.destinationEid.substringAfterLast("/")
                        navController.navigate("send_bundle?dest=${Uri.encode(bundle.sourceEid)}&sourceService=${Uri.encode(sourceSuffix)}")
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Reply", tint = NeonPurple)
                }

                // 2. Download / Save
                IconButton(
                    onClick = {
                        downloadBundlePayload(context, bundle)
                    },
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download to Phone", tint = NeonCyan)
                }

                // 3. Delete
                IconButton(
                    onClick = {
                        scope.launch {
                            try {
                                val file = File(bundle.payloadFilePath)
                                if (file.exists()) file.delete()
                            } catch (e: Exception) {
                            }
                            bundleRecordDao.delete(bundle)
                            onClose()
                        }
                    },
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                }
            }
        }

        HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 8.dp))

        // Headers scrollable
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                HeaderItem("Bundle ID", bundle.bundleId)
                HeaderItem("Source", bundle.sourceEid)
                HeaderItem("Destination", bundle.destinationEid)
                HeaderItem(
                    "Creation Time",
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(bundle.creationTimestamp)),
                )
                HeaderItem("Sequence No", bundle.sequenceNumber.toString())
                HeaderItem("Lifetime", "${bundle.lifetimeMs} ms")
                HeaderItem("Hop Count", bundle.hopCount.toString())
                HeaderItem("BPSec Integrity", bundle.bpsecStatus.name)
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("PAYLOAD VIEWER", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 14.sp)
                HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                val isImage = com.dtn.messenger.util.PayloadUtils.isImagePayload(bundle.payloadFilePath)
                val isAudio = com.dtn.messenger.util.PayloadUtils.isAudioPayload(bundle.payloadFilePath)
                if (isImage) {
                    AsyncImage(
                        model = File(bundle.payloadFilePath),
                        contentDescription = "Payload Image",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(Color.Black, RoundedCornerShape(8.dp)),
                    )
                } else if (isAudio) {
                    AudioPlayerCard(filePath = bundle.payloadFilePath)
                } else {
                    MarkdownText(payloadText)
                }
            }
        }
    }
}
