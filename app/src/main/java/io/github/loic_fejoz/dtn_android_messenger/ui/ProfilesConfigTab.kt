package io.github.loic_fejoz.dtn_android_messenger.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.cla.FileClaAdapter
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.ConvergenceProfile
import io.github.loic_fejoz.dtn_android_messenger.data.model.TriggerType
import io.github.loic_fejoz.dtn_android_messenger.service.DtnEngineService
import io.github.loic_fejoz.dtn_android_messenger.util.WifiUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatSafUriForDisplay(uriString: String): String {
    if (uriString.isBlank()) return ""
    return try {
        val decoded = android.net.Uri.decode(uriString.toUri().lastPathSegment ?: uriString)
        val colonIdx = decoded.indexOf(':')
        if (colonIdx == -1) return decoded
        val volume = decoded.substring(0, colonIdx)
        val path = decoded.substring(colonIdx + 1)
        when {
            volume.equals("primary", ignoreCase = true) -> path.ifBlank { "primary" }
            path.isBlank() -> volume
            else -> "$volume/$path"
        }
    } catch (_: Exception) {
        uriString
    }
}

@Composable
private fun FileClaFolderSelector(
    address: String,
    folderName: String,
    onPickFolder: () -> Unit,
) {
    OutlinedButton(
        onClick = onPickFolder,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, NeonCyan),
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = NeonCyan)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.file_cla_pick_folder), color = NeonCyan, fontWeight = FontWeight.Bold)
    }
    val folderDisplay =
        if (address.isBlank()) {
            stringResource(R.string.file_cla_no_folder_selected)
        } else {
            stringResource(R.string.file_cla_selected_folder_prefix, formatSafUriForDisplay(address))
        }
    Text(
        folderDisplay,
        color = if (address.isBlank()) TextGray else NeonCyan,
        fontSize = 12.sp,
    )
    val previewFolder = FileClaAdapter.sanitizeFolderName(folderName)
    if (previewFolder.isNotBlank()) {
        Text(
            "$previewFolder/RX & $previewFolder/TX",
            color = TextGray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun ProfilesConfigTab(
    dao: ConvergenceProfileDao,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val profiles by dao.getAll().collectAsState(initial = emptyList())

    val carConnectionState by remember(context) {
        androidx.car.app.connection.CarConnection(context).type
    }.observeAsState(initial = androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED)

    val isAutoConnected =
        carConnectionState == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION ||
            carConnectionState == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NATIVE
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TriggerType.WIFI_SSID) }
    var address by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var profileId by remember { mutableStateOf("") }
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var editingIsPaused by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val pickFileClaFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                }
                address = uri.toString()
            }
        }

    val scrollState = rememberScrollState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LocalNodeIdentityCard(context)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ADD CONVERGENCE PROFILE", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)

                OutlinedTextField(
                    value = profileId,
                    onValueChange = { profileId = it },
                    label = { Text("Profile EID (ex: dtn://node-b)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = {
                        Text(
                            if (type == TriggerType.FILE_CLA) {
                                stringResource(R.string.file_cla_folder_name_label)
                            } else {
                                "Display Name"
                            },
                            color = TextGray,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                var typeExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = type.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Trigger Type", color = TextGray) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { typeExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select type", tint = NeonCyan)
                            }
                        },
                    )
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        TriggerType.values().forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                onClick = {
                                    val previousType = type
                                    type = t
                                    if (t == TriggerType.FILE_CLA && previousType != TriggerType.FILE_CLA) {
                                        if (!address.startsWith("content://")) {
                                            address = ""
                                        }
                                        if (condition.isBlank()) {
                                            condition = FileClaAdapter.DEFAULT_POLL_INTERVAL_SECONDS.toString()
                                        }
                                    }
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                if (type == TriggerType.WIFI_SSID) {
                    var wifiExpanded by remember { mutableStateOf(false) }
                    val wifiSsids = remember { listOfNotNull(WifiUtils.getConnectedWifiSsid(context)) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = condition,
                            onValueChange = { condition = it },
                            label = { Text("Target WiFi SSID", color = TextGray) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { wifiExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select WiFi", tint = NeonCyan)
                                }
                            },
                        )
                        DropdownMenu(expanded = wifiExpanded, onDismissRequest = { wifiExpanded = false }) {
                            if (wifiSsids.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No visible SSIDs found (Enter manually)", color = TextGray) },
                                    onClick = { wifiExpanded = false },
                                )
                            } else {
                                wifiSsids.forEach { ssid ->
                                    DropdownMenuItem(
                                        text = { Text(ssid) },
                                        onClick = {
                                            condition = ssid
                                            wifiExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else if (type == TriggerType.FILE_CLA) {
                    OutlinedTextField(
                        value = condition,
                        onValueChange = { condition = it },
                        label = { Text(stringResource(R.string.file_cla_poll_interval_label), color = TextGray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = condition,
                        onValueChange = { condition = it },
                        label = { Text("Trigger Condition / Interval", color = TextGray) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (type == TriggerType.FILE_CLA) {
                    FileClaFolderSelector(
                        address = address,
                        folderName = name,
                        onPickFolder = { pickFileClaFolderLauncher.launch(null) },
                    )
                } else {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Target address (e.g. 192.168.1.10:5051)", color = TextGray) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    enabled = !isSaving,
                    onClick = {
                        if (profileId.isBlank() || name.isBlank() || address.isBlank()) {
                            Toast.makeText(context, "Fill required fields!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val trimmedId = profileId.trim()
                        val previousTargetAddress =
                            profiles.firstOrNull { it.profileId == trimmedId && it.triggerType == TriggerType.FILE_CLA }?.targetAddress
                        val preservedPaused =
                            (editingProfileId == trimmedId && editingIsPaused) ||
                                profiles.any { it.profileId == trimmedId && it.isPaused }
                        if (type == TriggerType.FILE_CLA) {
                            val sanitized = FileClaAdapter.sanitizeFolderName(name)
                            if (sanitized.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.file_cla_invalid_name), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val intervalSec =
                                if (condition.isBlank()) {
                                    FileClaAdapter.DEFAULT_POLL_INTERVAL_SECONDS
                                } else {
                                    condition.trim().toLongOrNull()
                                }
                            if (intervalSec == null || intervalSec < FileClaAdapter.MIN_POLL_INTERVAL_SECONDS) {
                                Toast.makeText(context, context.getString(R.string.file_cla_invalid_interval), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSaving = true
                            scope.launch {
                                try {
                                    val created =
                                        withContext(Dispatchers.IO) {
                                            FileClaAdapter.ensureClaFolders(context, address, sanitized)
                                        }
                                    if (!created) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.file_cla_folders_error, sanitized),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        return@launch
                                    }
                                    dao.insert(
                                        ConvergenceProfile(
                                            profileId = trimmedId,
                                            name = sanitized,
                                            triggerType = type,
                                            targetAddress = address,
                                            triggerCondition = intervalSec.toString(),
                                            isPaused = preservedPaused,
                                        ),
                                    )
                                    if (previousTargetAddress != null && previousTargetAddress != address) {
                                        val allProfiles = dao.getAllList()
                                        val stillReferenced =
                                            allProfiles.any {
                                                it.triggerType == TriggerType.FILE_CLA && it.targetAddress == previousTargetAddress
                                            }
                                        if (!stillReferenced) {
                                            runCatching {
                                                val flags =
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                context.contentResolver.releasePersistableUriPermission(
                                                    previousTargetAddress.toUri(),
                                                    flags,
                                                )
                                            }
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.file_cla_folders_created, sanitized),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    name = ""
                                    address = ""
                                    condition = ""
                                    profileId = ""
                                    editingProfileId = null
                                    editingIsPaused = false
                                } finally {
                                    isSaving = false
                                }
                            }
                        } else {
                            isSaving = true
                            scope.launch {
                                try {
                                    dao.insert(
                                        ConvergenceProfile(
                                            profileId = trimmedId,
                                            name = name,
                                            triggerType = type,
                                            targetAddress = address,
                                            triggerCondition = condition,
                                            isPaused = preservedPaused,
                                        ),
                                    )
                                    name = ""
                                    address = ""
                                    condition = ""
                                    profileId = ""
                                    editingProfileId = null
                                    editingIsPaused = false
                                } finally {
                                    isSaving = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                ) {
                    Text("SAVE PROFILE", fontWeight = FontWeight.Bold)
                }
            }
        }

        profiles.forEach { profile ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            profileId = profile.profileId
                            editingProfileId = profile.profileId
                            name = profile.name
                            type = profile.triggerType
                            condition = profile.triggerCondition ?: ""
                            address = profile.targetAddress
                            editingIsPaused = profile.isPaused
                            Toast.makeText(context, "Loaded profile for editing", Toast.LENGTH_SHORT).show()
                        },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, Color(0x08FFFFFF)),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val isTemporarilyPaused = !profile.isPaused && isAutoConnected && profile.triggerType == TriggerType.BLUETOOTH_ALWAYS
                        val titleColor = if (profile.isPaused || isTemporarilyPaused) Color.Gray else Color.White
                        val statusText =
                            when {
                                profile.isPaused -> " (PAUSED)"
                                isTemporarilyPaused -> " (AUTO-PAUSED)"
                                else -> ""
                            }
                        Text(profile.name + statusText, fontWeight = FontWeight.Bold, color = titleColor)
                        if (isTemporarilyPaused) {
                            Text(
                                "Android Auto Connected - Bluetooth CLA temporarily paused to avoid interference",
                                color = NeonPurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(
                                "Target EID: ${profile.profileId}",
                                color = if (profile.isPaused) Color.Gray else NeonCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        val destSummary =
                            if (profile.triggerType == TriggerType.FILE_CLA) {
                                "${formatSafUriForDisplay(profile.targetAddress)}/${FileClaAdapter.sanitizeFolderName(profile.name)}/{RX,TX}"
                            } else {
                                profile.targetAddress
                            }
                        Text(
                            "Type: ${profile.triggerType.name} | Dest: $destSummary",
                            color = if (profile.isPaused || isTemporarilyPaused) Color.Gray else TextGray,
                            fontSize = 12.sp,
                        )
                        if (profile.triggerType == TriggerType.FILE_CLA) {
                            val hasSafGrant =
                                remember(profile.targetAddress, profiles) {
                                    context.contentResolver.persistedUriPermissions.any { perm ->
                                        perm.uri.toString() == profile.targetAddress &&
                                            perm.isReadPermission &&
                                            perm.isWritePermission
                                    }
                                }
                            val pollSec =
                                profile.triggerCondition?.ifBlank {
                                    FileClaAdapter.DEFAULT_POLL_INTERVAL_SECONDS.toString()
                                } ?: FileClaAdapter.DEFAULT_POLL_INTERVAL_SECONDS.toString()
                            Text(
                                stringResource(R.string.file_cla_poll_display, pollSec),
                                color = if (profile.isPaused || isTemporarilyPaused) Color.Gray else NeonPurple,
                                fontSize = 11.sp,
                            )
                            if (!hasSafGrant) {
                                Text(
                                    "Folder permission missing — tap to re-pick base folder",
                                    color = GlowRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else if (!profile.triggerCondition.isNullOrBlank()) {
                            Text(
                                "Cond: ${profile.triggerCondition}",
                                color = if (profile.isPaused || isTemporarilyPaused) Color.Gray else NeonPurple,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            scope.launch {
                                dao.insert(profile.copy(isPaused = !profile.isPaused))
                            }
                        }) {
                            Icon(
                                imageVector = if (profile.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (profile.isPaused) "Resume CLA" else "Pause CLA",
                                tint = if (profile.isPaused) Color.LightGray else NeonCyan,
                            )
                        }
                        if (profile.triggerType == TriggerType.FILE_CLA) {
                            IconButton(onClick = {
                                val intent =
                                    Intent(context, DtnEngineService::class.java).apply {
                                        action = "SYNC_FILE_CLA"
                                        putExtra("profile_id", profile.profileId)
                                    }
                                try {
                                    ContextCompat.startForegroundService(context, intent)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.file_cla_syncing_toast, profile.name),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Unable to start service: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = stringResource(R.string.file_cla_sync_now),
                                    tint = NeonCyan,
                                )
                            }
                        } else if (!profile.isPaused && (profile.triggerType == TriggerType.PERIODIC_INTERNET || profile.triggerType == TriggerType.WIFI_SSID)) {
                            IconButton(onClick = {
                                val intent =
                                    Intent(context, DtnEngineService::class.java).apply {
                                        action = "FORCE_PULL"
                                        putExtra("address", profile.targetAddress)
                                    }
                                try {
                                    ContextCompat.startForegroundService(context, intent)
                                    Toast.makeText(context, "Forcing connection to ${profile.targetAddress}...", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Unable to start service: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.BackHand, contentDescription = "Force Connection", tint = NeonCyan)
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                dao.delete(profile)
                                if (profile.triggerType == TriggerType.FILE_CLA) {
                                    val remainingProfiles = dao.getAllList()
                                    val stillReferenced =
                                        remainingProfiles.any {
                                            it.triggerType == TriggerType.FILE_CLA &&
                                                it.targetAddress == profile.targetAddress
                                        }
                                    if (!stillReferenced) {
                                        runCatching {
                                            val flags =
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            context.contentResolver.releasePersistableUriPermission(
                                                profile.targetAddress.toUri(),
                                                flags,
                                            )
                                        }
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                        }
                    }
                }
            }
        }
    }
}
