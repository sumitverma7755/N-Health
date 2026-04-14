package com.nhealth.watchstatus.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhealth.watchstatus.ble.BleLogEntry
import com.nhealth.watchstatus.ble.BleLogLevel
import com.nhealth.watchstatus.ui.components.GlassCard
import com.nhealth.watchstatus.viewmodel.BLEUiState

@Composable
fun DebugScreen(
    state: BLEUiState,
    logs: List<BleLogEntry>,
    onClearLogs: () -> Unit,
    onRead: (serviceUuid: String, characteristicUuid: String) -> Unit,
    onWrite: (
        serviceUuid: String,
        characteristicUuid: String,
        payloadHex: String,
        withoutResponse: Boolean
    ) -> Unit,
    onNotify: (
        serviceUuid: String,
        characteristicUuid: String,
        enabled: Boolean,
        useIndication: Boolean
    ) -> Unit
) {
    val expandedServices = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Live BLE Logs", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClearLogs) {
                        Text("Clear")
                    }
                }

                logs.takeLast(80).forEach { log ->
                    val color = when (log.level) {
                        BleLogLevel.DEBUG -> Color(0xFF9CA3AF)
                        BleLogLevel.INFO -> Color(0xFF7DD3FC)
                        BleLogLevel.WARN -> Color(0xFFFBBF24)
                        BleLogLevel.ERROR -> Color(0xFFFB7185)
                    }
                    Text(
                        text = "[${log.level}] ${log.message}${log.payloadHex?.let { "\n  HEX: $it" } ?: ""}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }

        item {
            GlassCard {
                Text("Service Explorer", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Expand services and interact with any characteristic.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f)
                )
            }
        }

        items(state.services, key = { it.uuid.toString() }) { service ->
            val serviceKey = service.uuid.toString()
            val expanded = expandedServices[serviceKey] == true

            GlassCard(
                modifier = Modifier.clickable {
                    expandedServices[serviceKey] = !expanded
                }
            ) {
                Text(
                    text = "${service.name} (${service.uuid})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = if (expanded) "Tap to collapse" else "Tap to expand")

                if (expanded) {
                    service.characteristics.forEach { characteristic ->
                        CharacteristicPanel(
                            serviceUuid = service.uuid.toString(),
                            characteristicUuid = characteristic.uuid.toString(),
                            characteristicName = characteristic.name,
                            properties = characteristic.propertiesDescription,
                            supportsNotify = characteristic.supportsNotify,
                            supportsIndicate = characteristic.supportsIndicate,
                            supportsWrite = characteristic.supportsWrite,
                            onRead = onRead,
                            onWrite = onWrite,
                            onNotify = onNotify
                        )
                    }
                }
            }
        }

        item {
            GlassCard {
                Text("Persisted GATT Snapshot", style = MaterialTheme.typography.titleMedium)
                Text("Saved locally in Room for offline reverse-engineering reference.")
                state.persistedEntries.take(40).forEach { entry ->
                    Text(
                        text = "${entry.serviceUuid} / ${entry.characteristicUuid} / ${entry.descriptorUuid}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacteristicPanel(
    serviceUuid: String,
    characteristicUuid: String,
    characteristicName: String,
    properties: String,
    supportsNotify: Boolean,
    supportsIndicate: Boolean,
    supportsWrite: Boolean,
    onRead: (serviceUuid: String, characteristicUuid: String) -> Unit,
    onWrite: (
        serviceUuid: String,
        characteristicUuid: String,
        payloadHex: String,
        withoutResponse: Boolean
    ) -> Unit,
    onNotify: (
        serviceUuid: String,
        characteristicUuid: String,
        enabled: Boolean,
        useIndication: Boolean
    ) -> Unit
) {
    var payload by rememberSaveable(serviceUuid, characteristicUuid) { mutableStateOf("") }
    var notifyEnabled by rememberSaveable(serviceUuid, characteristicUuid, "notify") { mutableStateOf(false) }
    var indicateMode by rememberSaveable(serviceUuid, characteristicUuid, "indicate") { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Text(
            text = "$characteristicName ($characteristicUuid)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Text(text = "Properties: $properties", style = MaterialTheme.typography.bodySmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onRead(serviceUuid, characteristicUuid) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Read")
            }

            if (supportsNotify || supportsIndicate) {
                Button(
                    onClick = {
                        notifyEnabled = !notifyEnabled
                        onNotify(serviceUuid, characteristicUuid, notifyEnabled, indicateMode)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (notifyEnabled) "Disable Notify" else "Enable Notify")
                }
            }
        }

        if (supportsNotify || supportsIndicate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Use indication mode")
                Switch(
                    checked = indicateMode,
                    onCheckedChange = {
                        indicateMode = it
                        if (notifyEnabled) {
                            onNotify(serviceUuid, characteristicUuid, true, it)
                        }
                    }
                )
            }
        }

        if (supportsWrite) {
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                label = { Text("Hex payload (example: 01A0FF)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        onWrite(serviceUuid, characteristicUuid, payload, false)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Write Request")
                }
                TextButton(
                    onClick = {
                        onWrite(serviceUuid, characteristicUuid, payload, true)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Write No Response")
                }
            }
        }
    }
}
