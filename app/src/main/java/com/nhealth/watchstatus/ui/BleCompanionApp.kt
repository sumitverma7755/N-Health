package com.nhealth.watchstatus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.SettingsBluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nhealth.watchstatus.ui.components.GlassCard
import com.nhealth.watchstatus.ui.screens.ConnectionScreen
import com.nhealth.watchstatus.ui.screens.DashboardScreen
import com.nhealth.watchstatus.ui.screens.DebugScreen
import com.nhealth.watchstatus.ui.screens.ScanScreen
import com.nhealth.watchstatus.viewmodel.BLEViewModel

enum class AppTab(val label: String) {
    Scan("Scan"),
    Connection("Connection"),
    Dashboard("Dashboard"),
    Debug("Debug")
}

@Composable
fun BleCompanionApp(
    viewModel: BLEViewModel,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val rssiHistory by viewModel.rssiHistory.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Scan) }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF081128),
            Color(0xFF0E1A33),
            Color(0xFF04070E)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = Color.Black.copy(alpha = 0.26f),
                    tonalElevation = 0.dp
                ) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            icon = {
                                val icon = when (tab) {
                                    AppTab.Scan -> Icons.AutoMirrored.Outlined.BluetoothSearching
                                    AppTab.Connection -> Icons.Outlined.SettingsBluetooth
                                    AppTab.Dashboard -> Icons.Outlined.Dashboard
                                    AppTab.Debug -> Icons.Outlined.BugReport
                                }
                                Icon(imageVector = icon, contentDescription = tab.label)
                            },
                            label = {
                                Text(tab.label)
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (!hasPermissions) {
                    GlassCard {
                        Text(text = "Bluetooth and location permissions are required.")
                        androidx.compose.material3.TextButton(onClick = onRequestPermissions) {
                            Text("Grant Permissions")
                        }
                    }
                } else {
                    when (selectedTab) {
                        AppTab.Scan -> {
                            ScanScreen(
                                state = uiState,
                                onNameFilterChange = viewModel::onNameFilterChanged,
                                onMinRssiChange = viewModel::onMinRssiChanged,
                                onStartScan = viewModel::startScan,
                                onStopScan = viewModel::stopScan,
                                onConnect = { address -> viewModel.connect(address) }
                            )
                        }

                        AppTab.Connection -> {
                            ConnectionScreen(
                                state = uiState,
                                onConnect = { address -> viewModel.connect(address) },
                                onDisconnect = viewModel::disconnect,
                                onDiscoverServices = viewModel::discoverServices,
                                onRequestMtu = viewModel::requestMtu,
                                onMtuChanged = viewModel::onMtuChanged,
                                onAutoReconnectChange = viewModel::setAutoReconnect,
                                onBackgroundReconnectChange = viewModel::setBackgroundReconnect,
                                onReadRssi = viewModel::readRssi,
                                onRefreshGattCache = viewModel::refreshGattCache
                            )
                        }

                        AppTab.Dashboard -> {
                            DashboardScreen(
                                state = uiState,
                                rssiHistory = rssiHistory
                            )
                        }

                        AppTab.Debug -> {
                            DebugScreen(
                                state = uiState,
                                logs = logs,
                                onClearLogs = viewModel::clearLogs,
                                onRead = viewModel::readCharacteristic,
                                onWrite = viewModel::writeCharacteristic,
                                onNotify = viewModel::setNotifications
                            )
                        }
                    }
                }
            }
        }
    }
}
