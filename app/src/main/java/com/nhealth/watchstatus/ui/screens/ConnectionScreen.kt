package com.nhealth.watchstatus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nhealth.watchstatus.ui.components.GlassCard
import com.nhealth.watchstatus.viewmodel.BLEUiState

@Composable
fun ConnectionScreen(
    state: BLEUiState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDiscoverServices: () -> Unit,
    onRequestMtu: () -> Unit,
    onMtuChanged: (Int) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onBackgroundReconnectChange: (Boolean) -> Unit,
    onReadRssi: () -> Unit,
    onRefreshGattCache: () -> Unit
) {
    var manualAddress by rememberSaveable { mutableStateOf("") }
    var mtuInput by rememberSaveable { mutableStateOf(state.mtuRequest.toString()) }

    LaunchedEffect(state.connectedAddress) {
        if (!state.connectedAddress.isNullOrBlank() && manualAddress.isBlank()) {
            manualAddress = state.connectedAddress.orEmpty()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            Text(text = "Connection Manager", style = MaterialTheme.typography.titleLarge)
            Text(text = "State: ${state.connectionState}")
            Text(text = "Connected Address: ${state.connectedAddress ?: "None"}")

            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("Watch MAC address") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onConnect(manualAddress.trim()) }, modifier = Modifier.weight(1f)) {
                    Text("Connect")
                }
                TextButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Text("Disconnect")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Auto reconnect")
                Switch(
                    checked = state.autoReconnectEnabled,
                    onCheckedChange = onAutoReconnectChange
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Background reconnect service")
                Switch(
                    checked = state.backgroundReconnectEnabled,
                    onCheckedChange = onBackgroundReconnectChange
                )
            }
        }

        GlassCard {
            Text(text = "GATT Controls", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = mtuInput,
                onValueChange = {
                    mtuInput = it
                    val parsed = it.toIntOrNull()
                    if (parsed != null) {
                        onMtuChanged(parsed)
                    }
                },
                label = { Text("MTU (23-517)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onRequestMtu, modifier = Modifier.weight(1f)) {
                    Text("Request MTU")
                }
                Button(onClick = onReadRssi, modifier = Modifier.weight(1f)) {
                    Text("Read RSSI")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDiscoverServices, modifier = Modifier.weight(1f)) {
                    Text("Discover Services")
                }
                TextButton(onClick = onRefreshGattCache, modifier = Modifier.weight(1f)) {
                    Text("Refresh Cache")
                }
            }
        }
    }
}
