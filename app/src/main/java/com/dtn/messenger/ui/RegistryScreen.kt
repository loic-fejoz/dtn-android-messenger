package com.dtn.messenger.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dtn.messenger.data.dao.BundleRecordDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.model.ViewerType
import com.dtn.messenger.service.DtnEngineService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistryScreen(
    navController: NavController,
    localServiceDao: LocalServiceDao,
    bundleRecordDao: BundleRecordDao,
) {
    val services by localServiceDao.getAll().collectAsState(initial = emptyList())
    val isTcpActive by DtnEngineService.isTcpActive.collectAsState()
    val isBluetoothActive by DtnEngineService.isBluetoothActive.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "DTN Messenger",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = NeonCyan,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(isActive = isTcpActive, label = "TCP")
                            StatusDot(isActive = isBluetoothActive, label = "BT")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("logs") }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs", tint = NeonCyan)
                    }
                    IconButton(onClick = { navController.navigate("config") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = NeonPurple)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("send_bundle") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Registered local DTN service endpoints on this node. Tap to open data viewer.",
                    color = TextGray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (services.isEmpty()) {
                item {
                    Text(
                        "No local services registered.",
                        color = TextGray,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(services) { service ->
                    val unreadCount by bundleRecordDao.getUnreadCount(service.serviceEid).collectAsState(initial = 0)

                    GlassCard(onClick = {
                        navController.navigate("service_view/${Uri.encode(service.serviceEid)}")
                    }) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    service.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color.White,
                                )
                                Text(
                                    service.serviceEid,
                                    color = NeonCyan,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector =
                                            when (service.viewerType) {
                                                ViewerType.CHAT -> Icons.AutoMirrored.Filled.Send
                                                ViewerType.BUNDLE_LIST -> Icons.Default.Email
                                                ViewerType.MINIAPP -> Icons.Default.Home
                                                ViewerType.SENML_GRAPH -> Icons.Default.Info
                                            },
                                        contentDescription = "Viewer type",
                                        tint = TextGray,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        service.viewerType.name,
                                        color = TextGray,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            if (unreadCount > 0) {
                                Box(
                                    modifier =
                                        Modifier
                                            .background(NeonPurple, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        unreadCount.toString(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusDot(
    isActive: Boolean,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(
                        color = if (isActive) GlowGreen else Color.Gray,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
        )
        Text(label, color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
