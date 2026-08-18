package com.dtn.messenger.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.model.BpsecStatus
import com.dtn.messenger.data.model.BundleRecord
import com.dtn.messenger.data.model.BundleState
import com.dtn.messenger.service.DtnEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

val MicIcon: ImageVector
    get() =
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
                moveTo(17.3f, 11f)
                curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
                curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
                horizontalLineTo(5f)
                curveTo(5f, 14.42f, 7.72f, 17.23f, 11f, 17.72f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                verticalLineTo(17.72f)
                curveTo(16.28f, 17.23f, 19f, 14.42f, 19f, 11f)
                horizontalLineTo(17.3f)
                close()
            }
        }.build()

private fun getFileNameFromUri(
    context: Context,
    uri: Uri,
): String {
    var name = ""
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    if (cursor != null) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
        cursor.close()
    }
    if (name.isEmpty()) {
        name = uri.path?.substringAfterLast('/') ?: "selected_file"
    }
    return name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendBundleScreen(
    navController: NavController,
    bundleRecordDao: BundleRecordDao,
    localServiceDao: LocalServiceDao,
    preFilledDest: String = "",
    preFilledSourceService: String = "",
    preFilledPayloadText: String = "",
    preFilledFileUri: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Read local node name/EID from preferences
    val prefs = remember { com.dtn.messenger.util.PreferencesHelper.getEncryptedSharedPreferences(context) }
    val lastDestination = remember { prefs.getString("last_destination_eid", "") ?: "" }
    var destination by remember { mutableStateOf(preFilledDest.ifEmpty { lastDestination }) }
    var ttlValue by remember { mutableStateOf("1") }
    var ttlUnit by remember { mutableStateOf("h") }
    val localNodeName = remember { mutableStateOf(prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node") }

    var sourceService by remember { mutableStateOf(preFilledSourceService.ifEmpty { "chat" }) }
    var payloadText by remember { mutableStateOf(preFilledPayloadText) }

    // File picker states
    var isFileMode by remember { mutableStateOf(preFilledFileUri.isNotEmpty()) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(if (preFilledFileUri.isNotEmpty()) Uri.parse(preFilledFileUri) else null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFileSize by remember { mutableStateOf(0L) }

    // Audio recording state
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var recordingCancelled by remember { mutableStateOf(false) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasAudioPermission = granted
            if (!granted) {
                Toast.makeText(context, "Audio recording permission is required to record voice messages!", Toast.LENGTH_LONG).show()
            }
        }

    fun startRecording() {
        try {
            val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "ogg" else "m4a"
            val tempFile = File(context.cacheDir, "temp_recording.$extension")
            recordingFile = tempFile

            val recorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            recordingCancelled = false
            recordingDuration = 0
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to start recording: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecording(save: Boolean) {
        isRecording = false
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore if release failed
        }
        mediaRecorder = null

        if (save && !recordingCancelled) {
            val file = recordingFile
            if (file != null && file.exists() && file.length() > 0) {
                selectedFileUri = Uri.fromFile(file)
                selectedFileName = file.name
                selectedFileSize = file.length()
                isFileMode = true
                Toast.makeText(context, "Voice message recorded!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Recording failed or too short", Toast.LENGTH_SHORT).show()
            }
        } else {
            recordingFile?.delete()
        }
        recordingFile = null
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordingDuration += 1
            }
        }
    }

    LaunchedEffect(selectedFileUri) {
        selectedFileUri?.let { uri ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val name = getFileNameFromUri(context, uri)
                var size = 0L
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                        size = fd.length
                    }
                } catch (e: java.lang.Exception) {
                    size = 0L
                }
                selectedFileName = name
                selectedFileSize = size
            }
        }
    }

    val services by localServiceDao.getAll().collectAsState(initial = emptyList())

    val resolvedSourceEid = "${localNodeName.value.trimEnd('/')}/${sourceService.trimStart('/')}"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("OPPORTUNISTIC SENDER", fontWeight = FontWeight.Bold, color = NeonCyan) },
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
        val scrollState = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Computed Source EID display card
            GlassCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("RESOLVED SOURCE EID", fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)
                    Text(
                        resolvedSourceEid,
                        fontSize = 14.sp,
                        color = NeonCyan,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }

            // Editable source service field with helper dropdown
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = sourceService,
                    onValueChange = { sourceService = it },
                    label = { Text("Source Service (e.g., chat, sensor)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select registered service", tint = NeonCyan)
                        }
                    },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (services.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No local services registered", color = TextGray) },
                            onClick = { expanded = false },
                        )
                    } else {
                        services.forEach { s ->
                            val servicePath = s.serviceEid.substringAfterLast("/")
                            DropdownMenuItem(
                                text = { Text(s.serviceEid) },
                                onClick = {
                                    sourceService = servicePath
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Destination manual input
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination EID (ex: dtn://node-b/chat)", color = TextGray) },
                modifier = Modifier.fillMaxWidth(),
            )

            // Time-To-Live (TTL) Configuration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = ttlValue,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            ttlValue = newValue
                        }
                    },
                    label = { Text("Lifetime / TTL", color = TextGray) },
                    modifier = Modifier.weight(1.5f),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                )

                var unitDropdownExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = ttlUnit,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unit", color = TextGray) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { unitDropdownExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Unit", tint = NeonCyan)
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = unitDropdownExpanded,
                        onDismissRequest = { unitDropdownExpanded = false },
                    ) {
                        listOf("s", "mn", "h", "d").forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit) },
                                onClick = {
                                    ttlUnit = unit
                                    unitDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Mode Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = { isFileMode = false },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (!isFileMode) NeonPurple else GlassCardColor,
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("TEXT PAYLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { isFileMode = true },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (isFileMode) NeonPurple else GlassCardColor,
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("FILE / IMAGE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Payload content
            if (!isFileMode) {
                OutlinedTextField(
                    value = payloadText,
                    onValueChange = { payloadText = it },
                    label = { Text("Payload text content", color = TextGray) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                )
            } else {
                val filePickerLauncher =
                    rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent(),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            selectedFileUri = uri
                            selectedFileName = getFileNameFromUri(context, uri)
                            try {
                                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                                    selectedFileSize = fd.length
                                }
                            } catch (e: Exception) {
                                selectedFileSize = 0L
                            }
                        }
                    }

                GlassCard(
                    onClick = { filePickerLauncher.launch("*/*") },
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Pick File",
                            tint = NeonCyan,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = if (selectedFileUri == null) "TAP TO SELECT ANY FILE OR IMAGE" else "FILE: $selectedFileName",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                        if (selectedFileUri != null) {
                            val isAudio =
                                remember(selectedFileUri) {
                                    val uriString = selectedFileUri.toString()
                                    val ext = uriString.substringAfterLast('.').lowercase()
                                    ext in listOf("ogg", "opus", "mp3", "m4a", "mp4", "wav", "amr") ||
                                        selectedFileName.lowercase().let {
                                            it.endsWith(".ogg") || it.endsWith(".opus") || it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".mp4") || it.endsWith(".wav") || it.endsWith(".amr")
                                        }
                                }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Size: $selectedFileSize bytes",
                                        color = TextGray,
                                        fontSize = 12.sp,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        selectedFileUri = null
                                        selectedFileName = ""
                                        selectedFileSize = 0L
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear file",
                                        tint = GlowRed,
                                    )
                                }
                            }

                            if (isAudio) {
                                AudioPlayerCard(filePath = selectedFileUri.toString())
                            } else if (selectedFileName.lowercase().let {
                                    it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") || it.endsWith(".gif")
                                }
                            ) {
                                AsyncImage(
                                    model = selectedFileUri,
                                    contentDescription = "Preview",
                                    modifier =
                                        Modifier
                                            .size(100.dp)
                                            .background(Color.Black, RoundedCornerShape(4.dp)),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("OR RECORD VOICE MESSAGE", fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)

                val infiniteTransition = rememberInfiniteTransition(label = "flashing")
                val flashAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.2f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "flashAlpha",
                )

                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(hasAudioPermission) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    if (!hasAudioPermission) {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@awaitEachGesture
                                    }
                                    scope.launch {
                                        startRecording()
                                    }
                                    var cancelled = false
                                    var accumX = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val anyPressed = event.changes.any { it.pressed }
                                        if (anyPressed) {
                                            event.changes.forEach { change ->
                                                accumX += change.positionChange().x
                                                if (accumX < -150f && !cancelled) {
                                                    cancelled = true
                                                    recordingCancelled = true
                                                    scope.launch {
                                                        stopRecording(false)
                                                    }
                                                }
                                            }
                                        } else {
                                            if (!cancelled) {
                                                scope.launch {
                                                    stopRecording(true)
                                                }
                                            }
                                            break
                                        }
                                    }
                                }
                            },
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = if (isRecording) NeonPurple.copy(alpha = 0.2f) else GlassCardColor,
                        ),
                    border = BorderStroke(1.dp, if (isRecording) NeonPurple else Color(0x1AFFFFFF)),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isRecording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(10.dp)
                                            .background(GlowRed, RoundedCornerShape(5.dp))
                                            .alpha(flashAlpha),
                                )
                                Text(
                                    text = String.format("Recording: %02d:%02d", recordingDuration / 60, recordingDuration % 60),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                            Text(
                                text = "<< SLIDE LEFT TO CANCEL",
                                color = TextGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(
                                imageVector = MicIcon,
                                contentDescription = "Record Voice Message",
                                tint = NeonCyan,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "TOUCH & HOLD TO RECORD VOICE MESSAGE",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = "Release to attach, slide left to cancel",
                                color = TextGray,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (destination.isBlank() || sourceService.isBlank()) {
                        Toast.makeText(context, "Destination and Source Service are required!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!isFileMode && payloadText.isBlank()) {
                        Toast.makeText(context, "Payload text is required!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isFileMode && selectedFileUri == null) {
                        Toast.makeText(context, "Please select a file!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (!com.dtn.messenger.util.PayloadUtils.isValidEid(destination)) {
                        Toast.makeText(
                            context,
                            "Invalid Destination EID format! (Must start with dtn:// or ipn:)",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@Button
                    }

                    scope.launch {
                        try {
                            val maxSizeBytes = com.dtn.messenger.util.PreferencesHelper.getMaxBundleSizeBytes(context)
                            val maxMb = maxSizeBytes / (1024 * 1024)

                            val payloadFile =
                                withContext(Dispatchers.IO) {
                                    val payloadsDir = File(context.filesDir, "payloads")
                                    if (!payloadsDir.exists()) payloadsDir.mkdirs()

                                    val bundleId = UUID.randomUUID().toString()
                                    val fileExt = if (isFileMode) selectedFileName.substringAfterLast('.', "bin") else "bin"
                                    val file = File(payloadsDir, "$bundleId.$fileExt")

                                    if (isFileMode) {
                                        context.contentResolver.openInputStream(selectedFileUri!!).use { input ->
                                            file.outputStream().use { output ->
                                                input!!.copyTo(output)
                                            }
                                        }
                                    } else {
                                        val textBytes = payloadText.toByteArray(Charsets.UTF_8)
                                        FileOutputStream(file).use { fos ->
                                            fos.write(textBytes)
                                        }
                                    }
                                    file
                                }

                            if (payloadFile.length() > maxSizeBytes) {
                                withContext(Dispatchers.IO) {
                                    if (payloadFile.exists()) payloadFile.delete()
                                }
                                Toast.makeText(context, "Payload size (${payloadFile.length()} bytes) exceeds maximum limit of $maxMb MB", Toast.LENGTH_LONG).show()
                                return@launch
                            }

                            // Save last destination EID in preferences
                            prefs.edit().putString("last_destination_eid", destination.trim()).apply()

                            val parsedTtlValue = ttlValue.toLongOrNull() ?: 1L
                            val multiplier =
                                when (ttlUnit) {
                                    "s" -> 1000L
                                    "mn" -> 60 * 1000L
                                    "h" -> 3600 * 1000L
                                    "d" -> 24 * 3600 * 1000L
                                    else -> 3600 * 1000L
                                }
                            val calculatedLifetimeMs = parsedTtlValue * multiplier

                            withContext(Dispatchers.IO) {
                                val record =
                                    BundleRecord(
                                        bundleId = payloadFile.nameWithoutExtension,
                                        destinationEid = destination.trim(),
                                        sourceEid = resolvedSourceEid,
                                        creationTimestamp = System.currentTimeMillis(),
                                        sequenceNumber = System.currentTimeMillis() % 100000,
                                        lifetimeMs = calculatedLifetimeMs,
                                        payloadFilePath = payloadFile.absolutePath,
                                        state = BundleState.OUTBOX,
                                        isRead = true,
                                        bpsecStatus = BpsecStatus.UNVERIFIED,
                                        hopCount = 0,
                                    )
                                bundleRecordDao.insert(record)
                            }

                            // Start service queue flush
                            val serviceIntent =
                                Intent(context, DtnEngineService::class.java).apply {
                                    action = "FLUSH_QUEUE"
                                }
                            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)

                            Toast.makeText(context, "Bundle created in OUTBOX. Transmission pending.", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        } catch (e: Exception) {
                            android.util.Log.e("SendBundleScreen", "Failed to create bundle", e)
                            Toast.makeText(context, "Error creating bundle: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("QUEUE FOR TRANSMISSION", fontWeight = FontWeight.Bold)
            }
        }
    }
}
