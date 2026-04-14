package com.nhealth.watchstatus.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhealth.watchstatus.ble.BleConnectionState
import com.nhealth.watchstatus.ui.components.GlassCard
import com.nhealth.watchstatus.viewmodel.BLEUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: BLEUiState,
    rssiHistory: List<Int>
) {
    val transition = rememberInfiniteTransition(label = "connectionPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val connected = state.connectionState == BleConnectionState.CONNECTED
    val statusColor = if (connected) Color(0xFF2DD4BF) else Color(0xFFFF6B6B)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Smartwatch Dashboard",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.telemetry.deviceName} • ${state.connectionState}",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
                Canvas(modifier = Modifier.height(36.dp).fillMaxWidth(0.18f)) {
                    drawCircle(
                        color = statusColor.copy(alpha = pulse),
                        radius = size.minDimension / 2f
                    )
                }
            }
        }

        GlassCard {
            DashboardRow("Battery", state.telemetry.batteryLevel?.let { "$it%" } ?: "Not available")
            DashboardRow("Signal", state.telemetry.rssi?.let { "$it dBm" } ?: "Not available")
            DashboardRow("Manufacturer", state.telemetry.manufacturer)
            DashboardRow("Model", state.telemetry.model)
            DashboardRow("Firmware", state.telemetry.firmware)
            DashboardRow("MTU", state.telemetry.mtu.toString())
            DashboardRow("Last Sync", formatTime(state.telemetry.lastSyncEpochMillis))
        }

        GlassCard {
            Text("Signal Strength Graph", style = MaterialTheme.typography.titleMedium)
            RssiGraph(values = rssiHistory)
        }
    }
}

@Composable
private fun DashboardRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        Text(text = value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RssiGraph(values: List<Int>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(top = 8.dp)
    ) {
        if (values.size < 2) {
            return@Canvas
        }

        val minRssi = -110f
        val maxRssi = -20f
        val stepX = size.width / (values.size - 1)

        val points = values.mapIndexed { index, rssi ->
            val normalized = ((rssi - minRssi) / (maxRssi - minRssi)).coerceIn(0f, 1f)
            val x = index * stepX
            val y = size.height - (normalized * size.height)
            androidx.compose.ui.geometry.Offset(x, y)
        }

        for (i in 0 until points.lastIndex) {
            drawLine(
                color = Color(0xFF2DD4BF),
                start = points[i],
                end = points[i + 1],
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }

        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(points.first().x, size.height)
                points.forEach { point -> lineTo(point.x, point.y) }
                lineTo(points.last().x, size.height)
                close()
            },
            color = Color(0xFF2DD4BF).copy(alpha = 0.16f),
            style = Stroke(width = 1f)
        )
    }
}

private fun formatTime(epochMillis: Long?): String {
    if (epochMillis == null) return "Never"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
}
