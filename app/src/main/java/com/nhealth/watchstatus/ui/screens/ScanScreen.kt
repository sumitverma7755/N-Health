package com.nhealth.watchstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhealth.watchstatus.ui.components.GlassCard
import com.nhealth.watchstatus.viewmodel.BLEUiState

@Composable
fun ScanScreen(
    state: BLEUiState,
    onNameFilterChange: (String) -> Unit,
    onMinRssiChange: (Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard {
                Text(
                    text = "Device Scan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Raw advertisements, RSSI filtering, and one-tap connect for reverse engineering.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f)
                )

                OutlinedTextField(
                    value = state.nameFilter,
                    onValueChange = onNameFilterChange,
                    label = { Text("Name or address filter") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    singleLine = true
                )

                Text(
                    text = "Minimum RSSI: ${state.minRssi} dBm",
                    modifier = Modifier.padding(top = 6.dp)
                )
                Slider(
                    value = state.minRssi.toFloat(),
                    valueRange = -100f..-30f,
                    onValueChange = { onMinRssiChange(it.toInt()) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onStartScan, modifier = Modifier.weight(1f)) {
                        Text("Start Scan")
                    }
                    TextButton(onClick = onStopScan, modifier = Modifier.weight(1f)) {
                        Text("Stop Scan")
                    }
                }
            }
        }

        items(state.filteredScanResults, key = { it.address }) { packet ->
            GlassCard(
                modifier = Modifier.clickable { onConnect(packet.address) }
            ) {
                Text(
                    text = packet.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(text = packet.address, style = MaterialTheme.typography.bodyMedium)
                Text(text = "RSSI ${packet.rssi} dBm")
                SignalBar(packet.rssi)

                Text(
                    text = "ADV ${packet.advertisementHex.take(88)}${if (packet.advertisementHex.length > 88) "..." else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
private fun SignalBar(rssi: Int) {
    val normalized = ((rssi + 100).coerceIn(0, 70)) / 70f
    Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { index ->
            val threshold = (index + 1) / 5f
            val active = normalized >= threshold
            val color = if (active) Color(0xFF2DD4BF) else Color.White.copy(alpha = 0.18f)
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .height((8 + index * 4).dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(color)
            )
        }
    }
}
