package io.github.loic_fejoz.dtn_android_messenger.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.loic_fejoz.dtn_android_messenger.data.dao.RoutingRuleDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.RoutingRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun RoutingConfigTab(
    dao: RoutingRuleDao,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val rules by dao.getAll().collectAsState(initial = emptyList())
    var pattern by remember { mutableStateOf("") }
    var nextHop by remember { mutableStateOf("") }

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
                Text("ADD ROUTING RULE", fontWeight = FontWeight.Bold, color = NeonPurple, fontSize = 12.sp)

                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Destination EID Pattern (ex: dtn://node-b/*)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = nextHop,
                    onValueChange = { nextHop = it },
                    label = { Text("Next Hop Node EID (ex: dtn://gateway)", color = TextGray) },
                    modifier = Modifier.fillMaxWidth(),
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
                                    nextHopEid = nextHop.trim(),
                                ),
                            )
                            pattern = ""
                            nextHop = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
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
