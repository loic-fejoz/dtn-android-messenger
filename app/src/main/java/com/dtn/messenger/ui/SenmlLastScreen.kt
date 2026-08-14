package com.dtn.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.dao.SenmlEntryDao
import com.dtn.messenger.data.model.SenmlEntry
import com.dtn.messenger.protocol.SenmlParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenmlLastScreen(
    serviceEid: String,
    onBack: () -> Unit,
    senmlEntryDao: SenmlEntryDao = koinInject(),
    localServiceDao: LocalServiceDao = koinInject(),
    bundleRecordDao: BundleRecordDao = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    val entries by senmlEntryDao.getActiveEntries(serviceEid).collectAsState(initial = emptyList())
    var serviceName by remember { mutableStateOf(serviceEid) }
    var editingName by remember { mutableStateOf<String?>(null) }

    // Fetch service title and scan existing bundles if entries list is empty
    LaunchedEffect(serviceEid) {
        // Mark bundles for this service as read
        bundleRecordDao.markAllAsReadForDestination(serviceEid)

        val s = localServiceDao.getById(serviceEid)
        if (s != null) {
            serviceName = s.displayName
        }

        // If no entries exist yet, attempt to populate from historical bundles for this service
        val currentEntries = senmlEntryDao.getActiveEntriesList(serviceEid)
        if (currentEntries.isEmpty()) {
            val bundles = bundleRecordDao.getByDestination(serviceEid).first()
            for (bundle in bundles.reversed()) {
                try {
                    val file = File(bundle.payloadFilePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val parsed = SenmlParser.parse(bytes, bundle.creationTimestamp)
                        val latestInBundle = parsed.groupBy { it.name }
                            .mapValues { (_, list) -> list.maxByOrNull { it.timestamp }!! }
                            .values

                        for (rec in latestInBundle) {
                            val existing = senmlEntryDao.getEntry(serviceEid, rec.name)
                            if (existing != null) {
                                if (rec.timestamp >= existing.timestamp) {
                                    senmlEntryDao.insertOrUpdate(
                                        existing.copy(
                                            value = rec.value,
                                            unit = rec.unit,
                                            timestamp = rec.timestamp,
                                            isDeleted = false,
                                        )
                                    )
                                }
                            } else {
                                val maxOrder = senmlEntryDao.getMaxOrder(serviceEid) ?: 0
                                senmlEntryDao.insertOrUpdate(
                                    SenmlEntry(
                                        serviceEid = serviceEid,
                                        name = rec.name,
                                        value = rec.value,
                                        unit = rec.unit,
                                        timestamp = rec.timestamp,
                                        displayOrder = maxOrder + 1,
                                        isDeleted = false,
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore corrupted bundles
                }
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(serviceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucune mesure SenML reçue pour le moment.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(entries, key = { _, item -> item.name }) { index, item ->
                        SenmlEntryCard(
                            entry = item,
                            dateFormat = dateFormat,
                            isEditing = editingName == item.name,
                            canMoveUp = index > 0,
                            canMoveDown = index < entries.size - 1,
                            onMoveUp = {
                                coroutineScope.launch {
                                    val prev = entries[index - 1]
                                    val currentOrder = item.displayOrder
                                    val prevOrder = prev.displayOrder
                                    val targetCurrentOrder = if (prevOrder == currentOrder) currentOrder - 1 else prevOrder
                                    val targetPrevOrder = currentOrder
                                    senmlEntryDao.updateOrder(serviceEid, item.name, targetCurrentOrder)
                                    senmlEntryDao.updateOrder(serviceEid, prev.name, targetPrevOrder)
                                }
                            },
                            onMoveDown = {
                                coroutineScope.launch {
                                    val next = entries[index + 1]
                                    val currentOrder = item.displayOrder
                                    val nextOrder = next.displayOrder
                                    val targetCurrentOrder = if (nextOrder == currentOrder) currentOrder + 1 else nextOrder
                                    val targetNextOrder = currentOrder
                                    senmlEntryDao.updateOrder(serviceEid, item.name, targetCurrentOrder)
                                    senmlEntryDao.updateOrder(serviceEid, next.name, targetNextOrder)
                                }
                            },
                            onStartEdit = {
                                editingName = item.name
                            },
                            onSaveCustomLabel = { newLabel ->
                                coroutineScope.launch {
                                    senmlEntryDao.updateCustomLabel(serviceEid, item.name, newLabel)
                                }
                                editingName = null
                            },
                            onCancelEdit = {
                                editingName = null
                            },
                            onDelete = {
                                coroutineScope.launch {
                                    senmlEntryDao.markDeleted(serviceEid, item.name)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SenmlEntryCard(
    entry: SenmlEntry,
    dateFormat: SimpleDateFormat,
    isEditing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onStartEdit: () -> Unit,
    onSaveCustomLabel: (String?) -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var labelInput by remember(isEditing) { mutableStateOf(entry.customLabel ?: "") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Reordering controls
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp && !isEditing,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Monter",
                            tint = if (canMoveUp && !isEditing) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown && !isEditing,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = "Descendre",
                            tint = if (canMoveDown && !isEditing) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Main Info Column
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isEditing) {
                        val hasCustomLabel = !entry.customLabel.isNullOrBlank()

                        // Primary Label (customLabel if present, else original name 'n')
                        Text(
                            text = if (hasCustomLabel) entry.customLabel!! else entry.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Value and Unit
                        Text(
                            text = "${entry.value} ${entry.unit}".trim(),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )

                        // If customized: display original 'n' smaller underneath
                        if (hasCustomLabel) {
                            Text(
                                text = "n: ${entry.name}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        // Timestamp below in smaller font
                        val formattedTime = remember(entry.timestamp) {
                            try {
                                dateFormat.format(Date(entry.timestamp))
                            } catch (e: Exception) {
                                entry.timestamp.toString()
                            }
                        }

                        Text(
                            text = formattedTime,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Inline Editing Mode
                        Text(
                            text = "n: ${entry.name}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        OutlinedTextField(
                            value = labelInput,
                            onValueChange = { labelInput = it },
                            label = { Text("Label personnalisé") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val cleaned = labelInput.trim().ifEmpty { null }
                                    onSaveCustomLabel(cleaned)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }
                }

                // Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isEditing) {
                        IconButton(onClick = onStartEdit) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Éditer le label",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Supprimer",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val cleaned = labelInput.trim().ifEmpty { null }
                                onSaveCustomLabel(cleaned)
                            }
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Valider",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onCancelEdit) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Annuler",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            if (isEditing && entry.customLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onSaveCustomLabel(null) }
                    ) {
                        Text("Réinitialiser (Effacer label)", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
