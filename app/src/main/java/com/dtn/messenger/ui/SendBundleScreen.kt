package com.dtn.messenger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.model.BpsecStatus
import com.dtn.messenger.data.model.BundleRecord
import com.dtn.messenger.data.model.BundleState
import com.dtn.messenger.service.DtnEngineService
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private fun getFileNameFromUri(context: Context, uri: Uri): String {
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
    preFilledFileUri: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(preFilledDest) }
    
    // Read local node name/EID from preferences
    val prefs = remember { context.getSharedPreferences("dtn_prefs", Context.MODE_PRIVATE) }
    val localNodeName = remember { mutableStateOf(prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node") }
    
    var sourceService by remember { mutableStateOf(preFilledSourceService.ifEmpty { "chat" }) }
    var payloadText by remember { mutableStateOf(preFilledPayloadText) }
    
    // File picker states
    var isFileMode by remember { mutableStateOf(preFilledFileUri.isNotEmpty()) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(if (preFilledFileUri.isNotEmpty()) Uri.parse(preFilledFileUri) else null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFileSize by remember { mutableStateOf(0L) }

    LaunchedEffect(selectedFileUri) {
        selectedFileUri?.let { uri ->
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg)
            )
        },
        containerColor = CharcoalBg
    ) { paddingValues ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Computed Source EID display card
            GlassCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("RESOLVED SOURCE EID", fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)
                    Text(resolvedSourceEid, fontSize = 14.sp, color = NeonCyan, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
                    }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (services.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No local services registered", color = TextGray) },
                            onClick = { expanded = false }
                        )
                    } else {
                        services.forEach { s ->
                            val servicePath = s.serviceEid.substringAfterLast("/")
                            DropdownMenuItem(
                                text = { Text(s.serviceEid) },
                                onClick = {
                                    sourceService = servicePath
                                    expanded = false
                                }
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
                modifier = Modifier.fillMaxWidth()
            )

            // Mode Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { isFileMode = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isFileMode) NeonPurple else GlassCardColor,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("TEXT PAYLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { isFileMode = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFileMode) NeonPurple else GlassCardColor,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
            } else {
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
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
                    onClick = { filePickerLauncher.launch("*/*") }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Pick File",
                            tint = NeonCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = if (selectedFileUri == null) "TAP TO SELECT ANY FILE OR IMAGE" else "FILE: $selectedFileName",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        if (selectedFileUri != null) {
                            Text(
                                text = "Size: $selectedFileSize bytes",
                                color = TextGray,
                                fontSize = 12.sp
                            )
                            if (selectedFileName.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") || it.endsWith(".gif") }) {
                                AsyncImage(
                                    model = selectedFileUri,
                                    contentDescription = "Preview",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .background(Color.Black, RoundedCornerShape(4.dp))
                                )
                            }
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
                        Toast.makeText(context, "Invalid Destination EID format! (Must start with dtn:// or ipn:)", Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    scope.launch {
                        try {
                            val payloadsDir = File(context.filesDir, "payloads")
                            if (!payloadsDir.exists()) payloadsDir.mkdirs()

                            val bundleId = UUID.randomUUID().toString()
                            val fileExt = if (isFileMode) selectedFileName.substringAfterLast('.', "bin") else "bin"
                            val payloadFile = File(payloadsDir, "$bundleId.$fileExt")

                            if (isFileMode) {
                                context.contentResolver.openInputStream(selectedFileUri!!).use { input ->
                                    payloadFile.outputStream().use { output ->
                                        input!!.copyTo(output)
                                    }
                                }
                            } else {
                                FileOutputStream(payloadFile).use { fos ->
                                    fos.write(payloadText.toByteArray(Charsets.UTF_8))
                                }
                            }

                            val record = BundleRecord(
                                bundleId = bundleId,
                                destinationEid = destination.trim(),
                                sourceEid = resolvedSourceEid,
                                creationTimestamp = System.currentTimeMillis(),
                                sequenceNumber = System.currentTimeMillis() % 100000,
                                lifetimeMs = 3600000L,
                                payloadFilePath = payloadFile.absolutePath,
                                state = BundleState.OUTBOX,
                                isRead = true,
                                bpsecStatus = BpsecStatus.UNVERIFIED,
                                hopCount = 0
                            )
                            bundleRecordDao.insert(record)
                            
                            // Start service queue flush
                            context.startService(Intent(context, DtnEngineService::class.java).apply {
                                action = "FLUSH_QUEUE"
                            })
                            
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
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("QUEUE FOR TRANSMISSION", fontWeight = FontWeight.Bold)
            }
        }
    }
}
