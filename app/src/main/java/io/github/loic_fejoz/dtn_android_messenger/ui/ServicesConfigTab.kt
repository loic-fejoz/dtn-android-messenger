package io.github.loic_fejoz.dtn_android_messenger.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SenmlEntryDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import io.github.loic_fejoz.dtn_android_messenger.data.model.ViewerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ServicesConfigTab(
    dao: LocalServiceDao,
    scope: CoroutineScope,
    senmlEntryDao: SenmlEntryDao = org.koin.compose.koinInject(),
) {
    val context = LocalContext.current
    val services by dao.getAll().collectAsState(initial = emptyList())

    var eid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var viewerType by remember { mutableStateOf(ViewerType.CHAT) }
    var defaultDest by remember { mutableStateOf("") }
    var isBroadcast by remember { mutableStateOf(false) }
    var isNotificationEnabled by remember { mutableStateOf(true) }

    var editingService by remember { mutableStateOf<LocalService?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

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
                Text(
                    if (isEditMode) "EDIT DTN SERVICE" else "REGISTER LOCAL DTN SERVICE",
                    fontWeight = FontWeight.Bold,
                    color = NeonPurple,
                    fontSize = 12.sp,
                )

                OutlinedTextField(
                    value = eid,
                    onValueChange = { eid = it },
                    label = { Text("Service EID (ex: dtn://my-node/chat)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                var viewerExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = viewerType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("UI Viewer Type", color = TextGray) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { viewerExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select viewer", tint = NeonCyan)
                            }
                        },
                    )
                    DropdownMenu(expanded = viewerExpanded, onDismissRequest = { viewerExpanded = false }) {
                        listOf(ViewerType.CHAT, ViewerType.BUNDLE_LIST, ViewerType.SENML_LAST).forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.name) },
                                onClick = {
                                    viewerType = v
                                    viewerExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = defaultDest,
                    onValueChange = { defaultDest = it },
                    label = { Text("Default Destination EID (Optional)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = isBroadcast,
                        onCheckedChange = { isBroadcast = it },
                        colors =
                            CheckboxDefaults.colors(
                                checkedColor = NeonCyan,
                                uncheckedColor = TextGray,
                                checkmarkColor = Color.Black,
                            ),
                    )
                    Text("Broadcast / Multicast EID (relays messages back to network)", color = Color.White, fontSize = 14.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = isNotificationEnabled,
                        onCheckedChange = { isNotificationEnabled = it },
                        colors =
                            CheckboxDefaults.colors(
                                checkedColor = NeonCyan,
                                uncheckedColor = TextGray,
                                checkmarkColor = Color.Black,
                            ),
                    )
                    Text("Enable System Notifications", color = Color.White, fontSize = 14.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isEditMode) {
                        Button(
                            onClick = {
                                isEditMode = false
                                eid = ""
                                name = ""
                                defaultDest = ""
                                isBroadcast = false
                                viewerType = ViewerType.CHAT
                                editingService = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray, contentColor = Color.White),
                        ) {
                            Text("CANCEL", fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            if (eid.isBlank() || name.isBlank()) {
                                Toast.makeText(context, "EID and Display Name are required!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            scope.launch {
                                val newEid = eid.trim()
                                if (isEditMode && editingService != null && editingService!!.serviceEid != newEid) {
                                    dao.delete(editingService!!)
                                }
                                dao.insert(
                                    LocalService(
                                        serviceEid = newEid,
                                        displayName = name,
                                        viewerType = viewerType,
                                        defaultDestinationEid = defaultDest.trim(),
                                        isBroadcast = isBroadcast,
                                        isNotificationEnabled = isNotificationEnabled,
                                    ),
                                )
                                isEditMode = false
                                eid = ""
                                name = ""
                                defaultDest = ""
                                isBroadcast = false
                                isNotificationEnabled = true
                                viewerType = ViewerType.CHAT
                                editingService = null
                                Toast.makeText(context, "Service Saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    ) {
                        Text(if (isEditMode) "UPDATE" else "REGISTER", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        services.forEach { service ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingService = service
                            isEditMode = true
                            eid = service.serviceEid
                            name = service.displayName
                            viewerType = service.viewerType
                            defaultDest = service.defaultDestinationEid ?: ""
                            isBroadcast = service.isBroadcast
                            isNotificationEnabled = service.isNotificationEnabled
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
                        Text(service.displayName, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            service.serviceEid,
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text("Viewer: ${service.viewerType.name}", color = TextGray, fontSize = 12.sp)
                        Text(
                            "Type: ${if (service.isBroadcast) "Broadcast / Multicast" else "Unicast"}",
                            color = if (service.isBroadcast) NeonCyan else TextGray,
                            fontSize = 11.sp,
                        )
                        if (!service.defaultDestinationEid.isNullOrBlank()) {
                            Text("Default Dest: ${service.defaultDestinationEid}", color = NeonPurple, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            dao.delete(service)
                            senmlEntryDao.deleteAllForService(service.serviceEid)
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                    }
                }
            }
        }
    }
}
