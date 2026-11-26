package io.github.loic_fejoz.dtn_android_messenger.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.loic_fejoz.dtn_android_messenger.data.dao.BpsecKeyDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.BpsecKey
import io.github.loic_fejoz.dtn_android_messenger.util.CryptoManager
import io.github.loic_fejoz.dtn_android_messenger.util.HexUtils
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.SecureRandom

@Composable
fun KeystoreConfigTab(
    dao: BpsecKeyDao,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val keys by dao.getAll().collectAsState(initial = emptyList())
    var nodeId by remember { mutableStateOf("") }
    var secretHex by remember { mutableStateOf("") }

    val prefs = remember { PreferencesHelper.getEncryptedSharedPreferences(context) }
    var policy by remember { mutableStateOf(prefs.getString("bpsec_policy", "none") ?: "none") }
    var policyExpanded by remember { mutableStateOf(false) }

    val localNodeName = remember { prefs.getString("local_node_name", "dtn://my-node") ?: "dtn://my-node" }
    var localKeyRecord by remember { mutableStateOf<BpsecKey?>(null) }
    var localKeyHex by remember { mutableStateOf("") }

    LaunchedEffect(localNodeName) {
        localKeyRecord = dao.getByKeyId(localNodeName)
        val decryptedBytes =
            localKeyRecord?.let {
                try {
                    CryptoManager.decrypt(it.secretKey)
                } catch (_: Exception) {
                    null
                }
            }
        localKeyHex = decryptedBytes?.let { HexUtils.bytesToHex(it) } ?: ""
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
                        },
                    )
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        listOf("none", "warn", "strict").forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.uppercase()) },
                                onClick = {
                                    policy = p
                                    prefs.edit().putString("bpsec_policy", p).apply()
                                    policyExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    text =
                        when (policy) {
                            "strict" -> "STRICT: Discard bundles if signature is missing or verification fails."
                            "warn" -> "WARN: Accept all bundles but log a warning if signature check fails."
                            else -> "NONE: Accept all bundles. Verification results are shown on details screen only."
                        },
                    color = TextGray,
                    fontSize = 11.sp,
                )
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF)),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOCAL NODE SECRET KEY", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)
                Text(
                    "Used to sign outgoing bundles. Share this key with peers to let them verify your bundles.",
                    color = TextGray,
                    fontSize = 11.sp,
                )

                OutlinedTextField(
                    value = localKeyHex,
                    onValueChange = { localKeyHex = it },
                    label = { Text("Local HMAC Key (Hex)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val secureRandom = SecureRandom()
                            val keyBytes = ByteArray(32)
                            secureRandom.nextBytes(keyBytes)
                            localKeyHex = HexUtils.bytesToHex(keyBytes)
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = GlassCardColor, contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    ) {
                        Text("GENERATE", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            if (localKeyHex.isNotBlank()) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("BPSec Key", localKeyHex)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    clip.description.extras =
                                        PersistableBundle().apply {
                                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                }
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, "Key copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = GlassCardColor, contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    ) {
                        Text("COPY", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            if (localKeyHex.isBlank()) {
                                Toast.makeText(context, "Key hex cannot be blank!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val keyBytes =
                                try {
                                    HexUtils.hexToBytes(localKeyHex)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Invalid Hex format!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                            val encryptedKey =
                                try {
                                    CryptoManager.encrypt(keyBytes)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Encryption failed!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                            scope.launch {
                                dao.insert(
                                    BpsecKey(
                                        nodeEid = localNodeName,
                                        secretKey = encryptedKey,
                                    ),
                                )
                                localKeyRecord = dao.getByKeyId(localNodeName)
                                Toast.makeText(context, "Local key saved successfully!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    ) {
                        Text("SAVE", fontSize = 11.sp)
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardColor),
            border = BorderStroke(1.dp, Color(0x11FFFFFF)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ADD PEER SYMMETRIC KEY", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)

                OutlinedTextField(
                    value = nodeId,
                    onValueChange = { nodeId = it },
                    label = { Text("Peer Node EID / Key ID (ex: dtn://node-b)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = secretHex,
                    onValueChange = { secretHex = it },
                    label = { Text("Shared Secret Hex Key", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        if (nodeId.isBlank() || secretHex.isBlank()) {
                            Toast.makeText(context, "All fields are required!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val keyBytes =
                            try {
                                HexUtils.hexToBytes(secretHex)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Invalid Hex format!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                        val encryptedKey =
                            try {
                                CryptoManager.encrypt(keyBytes)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Encryption failed!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                        scope.launch {
                            dao.insert(
                                BpsecKey(
                                    nodeEid = nodeId.trim(),
                                    secretKey = encryptedKey,
                                ),
                            )
                            nodeId = ""
                            secretHex = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
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
