package com.dtn.messenger.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.dtn.messenger.service.DtnEngineService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dtn.messenger.data.dao.BpsecKeyDao
import com.dtn.messenger.data.dao.ConvergenceProfileDao
import com.dtn.messenger.data.dao.LocalServiceDao
import com.dtn.messenger.data.dao.RoutingRuleDao
import com.dtn.messenger.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun getAvailableWifiSsids(context: Context): List<String> {
    val ssids = mutableSetOf<String>()
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Modern connected SSID resolution for API 31+
        var currentSsid: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            val wifiInfo = capabilities?.transportInfo as? WifiInfo
            currentSsid = wifiInfo?.ssid
        } else {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            currentSsid = info?.ssid
        }
        
        if (currentSsid != null && currentSsid != "<unknown ssid>") {
            val clean = currentSsid.trim('"')
            if (clean.isNotEmpty()) {
                ssids.add(clean)
            }
        }
        
        val scans = wifiManager.scanResults
        if (scans != null) {
            for (scan in scans) {
                if (scan.SSID != null && scan.SSID.isNotEmpty()) {
                    ssids.add(scan.SSID.trim('"'))
                }
            }
        }
        
        @Suppress("DEPRECATION")
        val configs = wifiManager.configuredNetworks
        if (configs != null) {
            for (config in configs) {
                if (config.SSID != null && config.SSID.isNotEmpty()) {
                    ssids.add(config.SSID.trim('"'))
                }
            }
        }
    } catch (e: Exception) {
        // Fallback to manual SSID entry
    }
    return ssids.toList().sorted()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    navController: NavController,
    localServiceDao: LocalServiceDao,
    routingRuleDao: RoutingRuleDao,
    convergenceProfileDao: ConvergenceProfileDao,
    bpsecKeyDao: BpsecKeyDao
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SETTINGS", fontWeight = FontWeight.Bold, color = NeonCyan) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab Rows
            val tabs = listOf("CLA Profiles", "Routing", "BPSec", "Services")
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Black.copy(alpha = 0.2f),
                contentColor = NeonCyan
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> ProfilesConfigTab(convergenceProfileDao, scope)
                    1 -> RoutingConfigTab(routingRuleDao, scope)
                    2 -> KeystoreConfigTab(bpsecKeyDao, scope)
                    3 -> ServicesConfigTab(localServiceDao, scope)
                }
            }
        }
    }
}

@Composable
fun LocalNodeIdentityCard(context: Context = LocalContext.current) {
    val prefs = remember { com.dtn.messenger.util.PreferencesHelper.getEncryptedSharedPreferences(context) }
    var localNodeName by remember { mutableStateOf(prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GlassCardColor),
        border = BorderStroke(1.dp, Color(0x11FFFFFF))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("LOCAL NODE IDENTITY", fontSize = 10.sp, color = TextGray, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = localNodeName,
                onValueChange = {
                    localNodeName = it
                    prefs.edit().putString("local_node_name", it.trim()).apply()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Local EID (e.g. dtn://my-node)", color = TextGray) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesConfigTab(dao: ConvergenceProfileDao, scope: CoroutineScope) {
    val context = LocalContext.current
    val profiles by dao.getAll().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TriggerType.WIFI_SSID) }
    var address by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var profileId by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LocalNodeIdentityCard(context)
        // Form to add profile
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ADD CONVERGENCE PROFILE", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)

                OutlinedTextField(
                    value = profileId,
                    onValueChange = { profileId = it },
                    label = { Text("Profile EID (ex: dtn://node-b)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                // Trigger Type drop down
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
                        }
                    )
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        TriggerType.values().forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                onClick = {
                                    type = t
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                // If WIFI_SSID, offer helper dropdown/manual text
                if (type == TriggerType.WIFI_SSID) {
                    var wifiExpanded by remember { mutableStateOf(false) }
                    val wifiSsids = remember { getAvailableWifiSsids(context) }

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
                            }
                        )
                        DropdownMenu(expanded = wifiExpanded, onDismissRequest = { wifiExpanded = false }) {
                            if (wifiSsids.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No visible SSIDs found (Enter manually)", color = TextGray) },
                                    onClick = { wifiExpanded = false }
                                )
                            } else {
                                wifiSsids.forEach { ssid ->
                                    DropdownMenuItem(
                                        text = { Text(ssid) },
                                        onClick = {
                                            condition = ssid
                                            wifiExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = condition,
                        onValueChange = { condition = it },
                        label = { Text("Trigger Condition / Interval", color = TextGray) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Target address (e.g. 192.168.1.10:5051)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (profileId.isBlank() || name.isBlank() || address.isBlank()) {
                            Toast.makeText(context, "Fill required fields!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            dao.insert(
                                ConvergenceProfile(
                                    profileId = profileId.trim(),
                                    name = name,
                                    triggerType = type,
                                    targetAddress = address,
                                    triggerCondition = condition
                                )
                            )
                            name = ""
                            address = ""
                            condition = ""
                            profileId = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text("SAVE PROFILE", fontWeight = FontWeight.Bold)
                }
            }
        }

        // List of profiles
        profiles.forEach { profile ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        profileId = profile.profileId
                        name = profile.name
                        type = profile.triggerType
                        condition = profile.triggerCondition ?: ""
                        address = profile.targetAddress
                        Toast.makeText(context, "Loaded profile for editing", Toast.LENGTH_SHORT).show()
                    },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, Color(0x08FFFFFF))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Target EID: ${profile.profileId}", color = NeonCyan, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Type: ${profile.triggerType.name} | Dest: ${profile.targetAddress}", color = TextGray, fontSize = 12.sp)
                        if (!profile.triggerCondition.isNullOrBlank()) {
                            Text("Cond: ${profile.triggerCondition}", color = NeonPurple, fontSize = 11.sp)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (profile.triggerType == TriggerType.PERIODIC_INTERNET || profile.triggerType == TriggerType.WIFI_SSID) {
                            IconButton(onClick = {
                                val intent = Intent(context, DtnEngineService::class.java).apply {
                                    action = "FORCE_PULL"
                                    putExtra("address", profile.targetAddress)
                                }
                                context.startService(intent)
                                Toast.makeText(context, "Forcing connection to ${profile.targetAddress}...", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Force Connection", tint = NeonCyan)
                            }
                        }
                        IconButton(onClick = {
                            scope.launch { dao.delete(profile) }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoutingConfigTab(dao: RoutingRuleDao, scope: CoroutineScope) {
    val context = LocalContext.current
    val rules by dao.getAll().collectAsState(initial = emptyList())
    var pattern by remember { mutableStateOf("") }
    var nextHop by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LocalNodeIdentityCard(context)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ADD ROUTING RULE", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)

                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Destination EID Pattern (ex: dtn://node-b/*)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nextHop,
                    onValueChange = { nextHop = it },
                    label = { Text("Next Hop Node EID (ex: dtn://gateway)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (pattern.isBlank() || nextHop.isBlank()) {
                            Toast.makeText(context, "All fields are required!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            dao.insert(
                                RoutingRule(
                                    destinationEidPattern = pattern.trim(),
                                    nextHopEid = nextHop.trim()
                                )
                            )
                            pattern = ""
                            nextHop = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text("SAVE RULE", fontWeight = FontWeight.Bold)
                }
            }
        }

        rules.forEach { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, Color(0x08FFFFFF))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Pattern: ${rule.destinationEidPattern}", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Forward to: ${rule.nextHopEid}", color = NeonCyan, fontSize = 13.sp)
                    }
                    IconButton(onClick = {
                        scope.launch { dao.delete(rule) }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                    }
                }
            }
        }
    }
}

@Composable
fun KeystoreConfigTab(dao: BpsecKeyDao, scope: CoroutineScope) {
    val context = LocalContext.current
    val keys by dao.getAll().collectAsState(initial = emptyList())
    var nodeId by remember { mutableStateOf("") }
    var secretHex by remember { mutableStateOf("") }

    val prefs = remember { com.dtn.messenger.util.PreferencesHelper.getEncryptedSharedPreferences(context) }
    var policy by remember { mutableStateOf(prefs.getString("bpsec_policy", "none") ?: "none") }
    var policyExpanded by remember { mutableStateOf(false) }

    val localNodeName = remember { prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node" }
    var localKeyRecord by remember { mutableStateOf<BpsecKey?>(null) }
    var localKeyHex by remember { mutableStateOf("") }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(localNodeName) {
        localKeyRecord = dao.getByKeyId(localNodeName)
        val decryptedBytes = localKeyRecord?.let {
            try {
                com.dtn.messenger.util.CryptoManager.decrypt(it.secretKey)
            } catch (e: Exception) {
                null
            }
        }
        localKeyHex = decryptedBytes?.joinToString("") { "%02x".format(it) } ?: ""
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LocalNodeIdentityCard(context)

        // 1. Policy Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BPSEC INTEGRITY POLICY", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = policy.uppercase(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Integrity Verification Mode", color = TextGray) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { policyExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select policy", tint = NeonCyan)
                            }
                        }
                    )
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        listOf("none", "warn", "strict").forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.uppercase()) },
                                onClick = {
                                    policy = p
                                    prefs.edit().putString("bpsec_policy", p).apply()
                                    policyExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    text = when (policy) {
                        "strict" -> "STRICT: Discard bundles if signature is missing or verification fails."
                        "warn" -> "WARN: Accept all bundles but log a warning if signature check fails."
                        else -> "NONE: Accept all bundles. Verification results are shown on details screen only."
                    },
                    color = TextGray,
                    fontSize = 11.sp
                )
            }
        }

        // 2. Local Node Key Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOCAL NODE SECRET KEY", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)
                Text("Used to sign outgoing bundles. Share this key with peers to let them verify your bundles.", color = TextGray, fontSize = 11.sp)
                
                OutlinedTextField(
                    value = localKeyHex,
                    onValueChange = { localKeyHex = it },
                    label = { Text("Local HMAC Key (Hex)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val secureRandom = java.security.SecureRandom()
                            val keyBytes = ByteArray(32)
                            secureRandom.nextBytes(keyBytes)
                            localKeyHex = keyBytes.joinToString("") { "%02x".format(it) }
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = GlassCardColor, contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF))
                    ) {
                        Text("GENERATE", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            if (localKeyHex.isNotBlank()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(localKeyHex))
                                Toast.makeText(context, "Key copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = GlassCardColor, contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF))
                    ) {
                        Text("COPY", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            if (localKeyHex.isBlank()) {
                                Toast.makeText(context, "Key hex cannot be blank!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val keyBytes = try {
                                localKeyHex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Invalid Hex format!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val encryptedKey = try {
                                com.dtn.messenger.util.CryptoManager.encrypt(keyBytes)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Encryption failed!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            scope.launch {
                                dao.insert(
                                    BpsecKey(
                                        nodeEid = localNodeName,
                                        secretKey = encryptedKey
                                    )
                                )
                                localKeyRecord = dao.getByKeyId(localNodeName)
                                Toast.makeText(context, "Local key saved successfully!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                    ) {
                        Text("SAVE", fontSize = 11.sp)
                    }
                }
            }
        }

        // 3. Remote Keys Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ADD PEER SYMMETRIC KEY", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)

                OutlinedTextField(
                    value = nodeId,
                    onValueChange = { nodeId = it },
                    label = { Text("Peer Node EID / Key ID (ex: dtn://node-b)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = secretHex,
                    onValueChange = { secretHex = it },
                    label = { Text("Shared Secret Hex Key", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (nodeId.isBlank() || secretHex.isBlank()) {
                            Toast.makeText(context, "All fields are required!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val keyBytes = try {
                            secretHex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Invalid Hex format!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val encryptedKey = try {
                            com.dtn.messenger.util.CryptoManager.encrypt(keyBytes)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Encryption failed!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            dao.insert(
                                BpsecKey(
                                    nodeEid = nodeId.trim(),
                                    secretKey = encryptedKey
                                )
                            )
                            nodeId = ""
                            secretHex = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text("SAVE PEER KEY", fontWeight = FontWeight.Bold)
                }
            }
        }

        keys.forEach { key ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, Color(0x08FFFFFF))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Node EID: ${key.nodeEid}", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("HMAC-256 Enabled", color = GlowGreen, fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        scope.launch { dao.delete(key) }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesConfigTab(dao: LocalServiceDao, scope: CoroutineScope) {
    val context = LocalContext.current
    val services by dao.getAll().collectAsState(initial = emptyList())
    
    var eid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var viewerType by remember { mutableStateOf(ViewerType.CHAT) }
    var defaultDest by remember { mutableStateOf("") }
    
    var editingService by remember { mutableStateOf<LocalService?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LocalNodeIdentityCard(context)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (isEditMode) "EDIT DTN SERVICE" else "REGISTER LOCAL DTN SERVICE",
                    fontWeight = FontWeight.Bold,
                    color = NeonPurple,
                    fontSize = 12.sp
                )

                OutlinedTextField(
                    value = eid,
                    onValueChange = { eid = it },
                    label = { Text("Service EID (ex: dtn://my-node/chat)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isEditMode // Disable editing EID primary key
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
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
                        }
                    )
                    DropdownMenu(expanded = viewerExpanded, onDismissRequest = { viewerExpanded = false }) {
                        ViewerType.values().forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.name) },
                                onClick = {
                                    viewerType = v
                                    viewerExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = defaultDest,
                    onValueChange = { defaultDest = it },
                    label = { Text("Default Destination EID (Optional)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isEditMode) {
                        Button(
                            onClick = {
                                isEditMode = false
                                eid = ""
                                name = ""
                                defaultDest = ""
                                viewerType = ViewerType.CHAT
                                editingService = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray, contentColor = Color.White)
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
                                dao.insert(
                                    LocalService(
                                        serviceEid = eid.trim(),
                                        displayName = name,
                                        viewerType = viewerType,
                                        defaultDestinationEid = defaultDest.trim()
                                    )
                                )
                                // Clear form
                                isEditMode = false
                                eid = ""
                                name = ""
                                defaultDest = ""
                                viewerType = ViewerType.CHAT
                                editingService = null
                                Toast.makeText(context, "Service Saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                    ) {
                        Text(if (isEditMode) "UPDATE" else "REGISTER", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Services list
        services.forEach { service ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        editingService = service
                        isEditMode = true
                        eid = service.serviceEid
                        name = service.displayName
                        viewerType = service.viewerType
                        defaultDest = service.defaultDestinationEid ?: ""
                    },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardColor),
                border = BorderStroke(1.dp, Color(0x08FFFFFF))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(service.displayName, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(service.serviceEid, color = NeonCyan, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Viewer: ${service.viewerType.name}", color = TextGray, fontSize = 12.sp)
                        if (!service.defaultDestinationEid.isNullOrBlank()) {
                            Text("Default Dest: ${service.defaultDestinationEid}", color = NeonPurple, fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = {
                        scope.launch { dao.delete(service) }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GlowRed)
                    }
                }
            }
        }
    }
}
