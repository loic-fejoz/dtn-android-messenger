package io.github.loic_fejoz.dtn_android_messenger.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.data.dao.*
import io.github.loic_fejoz.dtn_android_messenger.util.ConfigBackupManager
import io.github.loic_fejoz.dtn_android_messenger.util.OnboardingHelper
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    localServiceDao: LocalServiceDao,
    convergenceProfileDao: ConvergenceProfileDao,
    routingRuleDao: RoutingRuleDao,
    bpsecKeyDao: BpsecKeyDao,
    senmlEntryDao: SenmlEntryDao,
    onOnboardingFinished: () -> Unit,
) {
    val context = LocalContext.current
    val database = org.koin.compose.koinInject<io.github.loic_fejoz.dtn_android_messenger.data.db.AppDatabase>()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var callsignInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingEncryptedBackupJson by remember { mutableStateOf<String?>(null) }
    var passphraseInput by remember { mutableStateOf("") }

    val importBackupLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    try {
                        val jsonText =
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            } ?: throw IllegalStateException(context.getString(R.string.onboarding_file_read_error))

                        if (ConfigBackupManager.isEncryptedBackup(jsonText)) {
                            pendingEncryptedBackupJson = jsonText
                            passphraseInput = ""
                        } else {
                            val result =
                                ConfigBackupManager.importConfigJson(
                                    jsonString = jsonText,
                                    context = context,
                                    localServiceDao = localServiceDao,
                                    convergenceProfileDao = convergenceProfileDao,
                                    routingRuleDao = routingRuleDao,
                                    bpsecKeyDao = bpsecKeyDao,
                                    senmlEntryDao = senmlEntryDao,
                                    clearExisting = true,
                                    runInTransaction = { block -> database.withTransaction { block() } },
                                )
                            PreferencesHelper.setOnboardingCompleted(context, true)
                            Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                            onOnboardingFinished()
                        }
                    } catch (e: Exception) {
                        errorMessage = context.getString(R.string.onboarding_import_failed, e.message ?: "")
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    } finally {
                        isLoading = false
                    }
                }
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CharcoalBg),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // App Branding Header
            Icon(
                imageVector = Icons.Default.Radio,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(R.string.onboarding_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 2.sp,
            )
            Text(
                text = stringResource(R.string.onboarding_welcome),
                fontSize = 13.sp,
                color = TextGray,
                textAlign = TextAlign.Center,
            )

            errorMessage?.let { error ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = GlowRed.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, GlowRed),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // OPTION 1: Callsign configuration
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_callsign_option),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_callsign_desc),
                        fontSize = 12.sp,
                        color = TextGray,
                        lineHeight = 18.sp,
                    )

                    OutlinedTextField(
                        value = callsignInput,
                        onValueChange = { callsignInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.onboarding_callsign_label), color = TextGray) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        textStyle =
                            LocalTextStyle.current.copy(
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                    )

                    Button(
                        onClick = {
                            if (callsignInput.isNotBlank()) {
                                scope.launch {
                                    isLoading = true
                                    try {
                                        OnboardingHelper.configureWithCallsign(
                                            callsign = callsignInput,
                                            context = context,
                                            localServiceDao = localServiceDao,
                                            convergenceProfileDao = convergenceProfileDao,
                                        )
                                        Toast.makeText(context, context.getString(R.string.onboarding_done_toast), Toast.LENGTH_SHORT).show()
                                        onOnboardingFinished()
                                    } catch (e: Exception) {
                                        errorMessage = context.getString(R.string.onboarding_error_prefix, e.message ?: "")
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        enabled = callsignInput.isNotBlank() && !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    ) {
                        Text(stringResource(R.string.onboarding_validate_start), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.15f))
                Text(
                    text = stringResource(R.string.onboarding_or),
                    color = TextGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.15f))
            }

            // OPTION 2: Restore from backup
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_restore_option),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonPurple,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_restore_desc),
                        fontSize = 12.sp,
                        color = TextGray,
                        lineHeight = 18.sp,
                    )

                    Button(
                        onClick = {
                            importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_restore_button), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = NeonCyan)
            }
        }

        if (pendingEncryptedBackupJson != null) {
            AlertDialog(
                onDismissRequest = { pendingEncryptedBackupJson = null },
                title = { Text("Encrypted Backup", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = { Text("Enter Backup Passphrase", color = TextGray) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val jsonText = pendingEncryptedBackupJson ?: return@Button
                            val pass = passphraseInput
                            pendingEncryptedBackupJson = null
                            scope.launch {
                                isLoading = true
                                try {
                                    val result =
                                        ConfigBackupManager.importConfigJson(
                                            jsonString = jsonText,
                                            context = context,
                                            localServiceDao = localServiceDao,
                                            convergenceProfileDao = convergenceProfileDao,
                                            routingRuleDao = routingRuleDao,
                                            bpsecKeyDao = bpsecKeyDao,
                                            senmlEntryDao = senmlEntryDao,
                                            clearExisting = true,
                                            passphrase = pass,
                                            runInTransaction = { block -> database.withTransaction { block() } },
                                        )
                                    PreferencesHelper.setOnboardingCompleted(context, true)
                                    Toast.makeText(context, result.summary, Toast.LENGTH_LONG).show()
                                    onOnboardingFinished()
                                } catch (e: Exception) {
                                    errorMessage = context.getString(R.string.onboarding_import_failed, e.message ?: "")
                                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    ) {
                        Text("DECRYPT & IMPORT", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingEncryptedBackupJson = null }) {
                        Text("CANCEL", color = TextGray)
                    }
                },
                containerColor = CharcoalBg,
            )
        }
    }
}
