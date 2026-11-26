package io.github.loic_fejoz.dtn_android_messenger.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.navigation.NavController
import io.github.loic_fejoz.dtn_android_messenger.R
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BpsecKeyDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.ConvergenceProfileDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.LocalServiceDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.RoutingRuleDao
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SenmlEntryDao
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    localServiceDao: LocalServiceDao,
    routingRuleDao: RoutingRuleDao,
    convergenceProfileDao: ConvergenceProfileDao,
    bpsecKeyDao: BpsecKeyDao,
    senmlEntryDao: SenmlEntryDao = org.koin.compose.koinInject(),
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, color = NeonCyan) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                    }
                },
                actions = {
                    IconButton(onClick = { selectedTab = 4 }) {
                        Icon(Icons.Default.Backup, contentDescription = "Backup & Restore", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CharcoalBg),
            )
        },
        containerColor = CharcoalBg,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            val tabs =
                listOf(
                    stringResource(R.string.settings_tab_profiles),
                    stringResource(R.string.settings_tab_routing),
                    stringResource(R.string.settings_tab_bpsec),
                    stringResource(R.string.settings_tab_services),
                    stringResource(R.string.settings_tab_backup),
                )
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Black.copy(alpha = 0.2f),
                contentColor = NeonCyan,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.Bold) },
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                when (selectedTab) {
                    0 -> ProfilesConfigTab(convergenceProfileDao, scope)
                    1 -> RoutingConfigTab(routingRuleDao, scope)
                    2 -> KeystoreConfigTab(bpsecKeyDao, scope)
                    3 -> ServicesConfigTab(localServiceDao, scope, senmlEntryDao)
                    4 -> BackupConfigTab(localServiceDao, convergenceProfileDao, routingRuleDao, bpsecKeyDao, senmlEntryDao, navController, scope)
                }
            }
        }
    }
}

@Composable
fun LocalNodeIdentityCard(
    context: Context = LocalContext.current,
    onLocalNodeNameChanged: ((String) -> Unit)? = null,
) {
    val prefs = remember { PreferencesHelper.getEncryptedSharedPreferences(context) }
    var localNodeName by remember { mutableStateOf(prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node") }
    var maxBundleSizeMb by remember {
        mutableStateOf(
            prefs.getInt(PreferencesHelper.PREF_MAX_BUNDLE_SIZE_MB, PreferencesHelper.DEFAULT_MAX_BUNDLE_SIZE_MB).toString(),
        )
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GlassCardColor),
        border = BorderStroke(1.dp, Color(0x11FFFFFF)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("LOCAL NODE IDENTITY & LIMITS", fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = localNodeName,
                onValueChange = {
                    localNodeName = it
                    val trimmed = it.trim()
                    prefs.edit { putString("local_node_name", trimmed) }
                    onLocalNodeNameChanged?.invoke(trimmed)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Local EID (e.g. dtn://my-node)", color = TextGray) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            val isSizeError = maxBundleSizeMb.toIntOrNull()?.let { it !in 1..PreferencesHelper.MAX_BUNDLE_SIZE_MB_LIMIT } ?: true
            OutlinedTextField(
                value = maxBundleSizeMb,
                onValueChange = { input ->
                    maxBundleSizeMb = input
                    val parsed = input.toIntOrNull()
                    if (parsed != null && parsed in 1..PreferencesHelper.MAX_BUNDLE_SIZE_MB_LIMIT) {
                        prefs.edit { putInt(PreferencesHelper.PREF_MAX_BUNDLE_SIZE_MB, parsed) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Maximum Payload / Bundle Size (MB, 1-${PreferencesHelper.MAX_BUNDLE_SIZE_MB_LIMIT})", color = TextGray) },
                isError = isSizeError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
    }
}

@Composable
fun <T> LiveData<T>.observeAsState(initial: T): State<T> {
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val state = remember { mutableStateOf(initial) }
    DisposableEffect(this, lifecycleOwner) {
        val observer = Observer<T> { state.value = it }
        observe(lifecycleOwner, observer)
        onDispose {
            removeObserver(observer)
        }
    }
    return state
}
