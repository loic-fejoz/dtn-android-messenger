package com.dtn.messenger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dtn.messenger.data.dao.SystemLogDao
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemLogScreen(
    navController: NavController,
    logDao: SystemLogDao,
) {
    val scope = rememberCoroutineScope()
    val logs by logDao.getAll().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SYSTEM LOGS", fontWeight = FontWeight.Bold, color = NeonCyan) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { logDao.clearAll() }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = GlowRed)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
            )
        },
        containerColor = CharcoalBg,
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (logs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No logs registered.", color = TextGray, fontSize = 14.sp)
                    }
                }
            } else {
                items(logs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                        border = BorderStroke(1.dp, Color(0x05FFFFFF)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    log.level,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color =
                                        when (log.level) {
                                            "ERROR" -> GlowRed
                                            "WARN" -> NeonPurple
                                            else -> NeonCyan
                                        },
                                )
                                Text(
                                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp)),
                                    color = TextGray,
                                    fontSize = 10.sp,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                log.message,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}
