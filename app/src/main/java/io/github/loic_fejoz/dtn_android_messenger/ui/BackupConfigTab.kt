package io.github.loic_fejoz.dtn_android_messenger.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.NavController
import androidx.room.withTransaction
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BpsecKeyDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.RoutingRuleDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SenmlEntryDao
import io.github.loic_fejoz.dtn_android_messenger.data.db.AppDatabase
import io.github.loic_fejoz.dtn_android_messenger.util.ConfigBackupManager
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupConfigTab(
    localServiceDao: LocalServiceDao,
    convergenceProfileDao: ConvergenceProfileDao,
    routingRuleDao: RoutingRuleDao,
    bpsecKeyDao: BpsecKeyDao,
    senmlEntryDao: SenmlEntryDao,
    navController: NavController,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val database = org.koin.compose.koinInject<AppDatabase>()
    val scrollState = rememberScrollState()
    var clearExistingBeforeImport by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var backupPassphrase by remember { mutableStateOf("") }
    var showPlaintextWarningForExport by remember { mutableStateOf(false) }
    var showPlaintextWarningForShare by remember { mutableStateOf(false) }
    var pendingEncryptedImportJson by remember { mutableStateOf<String?>(null) }
    var importPassphraseInput by remember { mutableStateOf("") }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                ConfigBackupManager.exportToStream(
                                    stream,
                                    context,
                                    localServiceDao,
                                    convergenceProfileDao,
                                    routingRuleDao,
                                    bpsecKeyDao,
                                    senmlEntryDao,
                                    passphrase = backupPassphrase.ifEmpty { null },
                                )
                            } ?: error("Unable to open output stream")
                        }
                        statusMessage = "Configuration exported successfully to file!"
                        isError = false
                        Toast.makeText(context, "Export successful!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        statusMessage = "Export failed: ${e.message}"
                        isError = true
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val jsonText =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    ConfigBackupManager.readBoundedUtf8(stream)
                                } ?: error("Unable to open input stream")
                            }
                        if (ConfigBackupManager.isEncryptedBackup(jsonText)) {
                            pendingEncryptedImportJson = jsonText
                            importPassphraseInput = ""
                        } else {
                            val result =
                                ConfigBackupManager.importConfigJson(
                                    jsonText,
                                    context,
                                    localServiceDao,
                                    convergenceProfileDao,
                                    routingRuleDao,
                                    bpsecKeyDao,
                                    senmlEntryDao,
                                    clearExisting = clearExistingBeforeImport,
                                    runInTransaction = { block -> database.withTransaction { block() } },
                                )
                            statusMessage = result.summary
                            isError = false
                            Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        statusMessage = "Import failed: ${e.message}"
                        isError = true
                        Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        statusMessage?.let { msg ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = if (isError) GlowRed.copy(alpha = 0.2f) else GlowGreen.copy(alpha = 0.2f),
                    ),
                border = BorderStroke(1.dp, if (isError) GlowRed else GlowGreen),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CONFIGURATION BACKUP & RESTORE", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                Text(
                    "Export your complete node configuration to a JSON backup file to avoid data loss during reinstallations, device migrations, or updates. The backup includes:\n" +
                        "• Local Node EID, Identity & Limits\n" +
                        "• Convergence Layer Profiles (TCPCL, Bluetooth & File-CLA)\n" +
                        "• Next-Hop Routing Rules\n" +
                        "• BPSec Pre-shared HMAC Keys (safely re-encryptable)\n" +
                        "• Local Channels & Services\n" +
                        "• SenML Sensor custom labels & display orders",
                    color = TextGray,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("EXPORT CONFIGURATION", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                Text(
                    "Save a JSON backup file to external storage, cloud drive, or share it with other apps. Set an optional passphrase to encrypt BPSec secret keys.",
                    color = TextGray,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = backupPassphrase,
                    onValueChange = { backupPassphrase = it },
                    label = { Text("Optional Backup Passphrase (AES-256-GCM)", color = TextGray) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (backupPassphrase.isEmpty()) {
                            showPlaintextWarningForExport = true
                        } else {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("dtn_backup_$timestamp.json")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SAVE BACKUP FILE (.JSON)", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        if (backupPassphrase.isEmpty()) {
                            showPlaintextWarningForShare = true
                        } else {
                            scope.launch {
                                try {
                                    val json =
                                        ConfigBackupManager.exportConfigJson(
                                            context,
                                            localServiceDao,
                                            convergenceProfileDao,
                                            routingRuleDao,
                                            bpsecKeyDao,
                                            senmlEntryDao,
                                            passphrase = backupPassphrase,
                                        )
                                    val sendIntent =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_SUBJECT, "DTN Android Messenger Backup")
                                            putExtra(Intent.EXTRA_TEXT, json)
                                        }
                                    val shareIntent = Intent.createChooser(sendIntent, "Share Backup JSON")
                                    context.startActivity(shareIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                    border = BorderStroke(1.dp, NeonCyan),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SHARE CONFIGURATION VIA...", fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("IMPORT CONFIGURATION", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                Text(
                    "Restore settings from an existing DTN Messenger JSON backup file.",
                    color = TextGray,
                    fontSize = 12.sp,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { clearExistingBeforeImport = !clearExistingBeforeImport },
                ) {
                    Checkbox(
                        checked = clearExistingBeforeImport,
                        onCheckedChange = { clearExistingBeforeImport = it },
                        colors =
                            CheckboxDefaults.colors(
                                checkedColor = NeonCyan,
                                checkmarkColor = Color.Black,
                                uncheckedColor = TextGray,
                            ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Wipe existing configuration before import (replace instead of merge)",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }

                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SELECT & IMPORT BACKUP FILE", fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, GlowRed.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.config_reset_title), fontSize = 11.sp, color = GlowRed, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.config_reset_desc),
                    color = TextGray,
                    fontSize = 12.sp,
                )

                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GlowRed, contentColor = Color.White),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.config_reset_button), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showPlaintextWarningForExport || showPlaintextWarningForShare) {
        val isShare = showPlaintextWarningForShare
        AlertDialog(
            onDismissRequest = {
                showPlaintextWarningForExport = false
                showPlaintextWarningForShare = false
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = GlowRed) },
            title = { Text("Export Unencrypted Secrets?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "No passphrase is set. Your BPSec HMAC secret keys will be exported in plaintext JSON. Anyone with this file can forge bundle signatures.",
                    color = TextGray,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPlaintextWarningForExport = false
                        showPlaintextWarningForShare = false
                        if (isShare) {
                            scope.launch {
                                try {
                                    val json =
                                        ConfigBackupManager.exportConfigJson(
                                            context,
                                            localServiceDao,
                                            convergenceProfileDao,
                                            routingRuleDao,
                                            bpsecKeyDao,
                                            senmlEntryDao,
                                        )
                                    val sendIntent =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_SUBJECT, "DTN Android Messenger Backup")
                                            putExtra(Intent.EXTRA_TEXT, json)
                                        }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Backup JSON"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("dtn_backup_$timestamp.json")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GlowRed, contentColor = Color.White),
                ) {
                    Text("EXPORT PLAINTEXT", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPlaintextWarningForExport = false
                    showPlaintextWarningForShare = false
                }) {
                    Text("CANCEL", color = TextGray)
                }
            },
            containerColor = CharcoalBg,
        )
    }

    if (pendingEncryptedImportJson != null) {
        EncryptedBackupPassphraseDialog(
            passphrase = importPassphraseInput,
            onPassphraseChange = { importPassphraseInput = it },
            onDismiss = { pendingEncryptedImportJson = null },
            onConfirm = {
                val jsonText = pendingEncryptedImportJson ?: return@EncryptedBackupPassphraseDialog
                val pass = importPassphraseInput
                pendingEncryptedImportJson = null
                scope.launch {
                    try {
                        val result =
                            ConfigBackupManager.importConfigJson(
                                jsonText,
                                context,
                                localServiceDao,
                                convergenceProfileDao,
                                routingRuleDao,
                                bpsecKeyDao,
                                senmlEntryDao,
                                clearExisting = clearExistingBeforeImport,
                                passphrase = pass,
                                runInTransaction = { block -> database.withTransaction { block() } },
                            )
                        statusMessage = result.summary
                        isError = false
                        Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        statusMessage = "Import failed: ${e.message}"
                        isError = true
                        Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = GlowRed) },
            title = { Text(stringResource(R.string.config_reset_dialog_title), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.config_reset_dialog_msg),
                    color = TextGray,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                database.withTransaction {
                                    localServiceDao.deleteAll()
                                    convergenceProfileDao.deleteAll()
                                    routingRuleDao.deleteAll()
                                    bpsecKeyDao.deleteAll()
                                    senmlEntryDao.deleteAll()
                                }
                                PreferencesHelper.getEncryptedSharedPreferences(context).edit { clear() }
                                PreferencesHelper.setOnboardingCompleted(context, false)
                            }
                            navController.navigate("onboarding") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GlowRed, contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.config_reset_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.config_reset_cancel), color = TextGray)
                }
            },
            containerColor = CharcoalBg,
        )
    }
}
