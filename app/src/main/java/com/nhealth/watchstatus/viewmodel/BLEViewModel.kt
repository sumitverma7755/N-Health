package com.nhealth.watchstatus.viewmodel

import android.bluetooth.BluetoothGattCharacteristic
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nhealth.watchstatus.ble.AdvertisementPacket
import com.nhealth.watchstatus.ble.BleConnectionState
import com.nhealth.watchstatus.ble.BleLogEntry
import com.nhealth.watchstatus.ble.BleLogLevel
import com.nhealth.watchstatus.ble.DeviceTelemetry
import com.nhealth.watchstatus.ble.ServiceMeta
import com.nhealth.watchstatus.ble.hexToByteArrayOrNull
import com.nhealth.watchstatus.data.BLERepository
import com.nhealth.watchstatus.data.local.GattEntryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class BLEUiState(
    val connectionState: BleConnectionState = BleConnectionState.IDLE,
    val connectedAddress: String? = null,
    val telemetry: DeviceTelemetry = DeviceTelemetry(),
    val scanResults: List<AdvertisementPacket> = emptyList(),
    val filteredScanResults: List<AdvertisementPacket> = emptyList(),
    val services: List<ServiceMeta> = emptyList(),
    val persistedEntries: List<GattEntryEntity> = emptyList(),
    val nameFilter: String = "",
    val minRssi: Int = -95,
    val autoReconnectEnabled: Boolean = true,
    val backgroundReconnectEnabled: Boolean = false,
    val mtuRequest: Int = 247,
    val latestError: String? = null
)

class BLEViewModel(
    private val repository: BLERepository
) : ViewModel() {

    private data class PrimaryState(
        val connectionState: BleConnectionState,
        val connectedAddress: String?,
        val telemetry: DeviceTelemetry,
        val scanResults: List<AdvertisementPacket>
    )

    private data class SecondaryState(
        val services: List<ServiceMeta>,
        val persistedEntries: List<GattEntryEntity>
    )

    private data class ControlState(
        val autoReconnectEnabled: Boolean,
        val backgroundReconnectEnabled: Boolean,
        val mtuRequest: Int,
        val latestError: String?
    )

    private val nameFilter = MutableStateFlow("")
    private val minRssiFilter = MutableStateFlow(-95)
    private val backgroundReconnectEnabled = MutableStateFlow(false)
    private val mtuRequest = MutableStateFlow(247)
    private val latestError = MutableStateFlow<String?>(null)

    private val _logs = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val logs: StateFlow<List<BleLogEntry>> = _logs.asStateFlow()

    private val _rssiHistory = MutableStateFlow<List<Int>>(emptyList())
    val rssiHistory: StateFlow<List<Int>> = _rssiHistory.asStateFlow()

    private val primaryState = combine(
        repository.connectionState,
        repository.connectedAddress,
        repository.telemetry,
        repository.scanResults
    ) { connectionState, connectedAddress, telemetry, scanResults ->
        PrimaryState(
            connectionState = connectionState,
            connectedAddress = connectedAddress,
            telemetry = telemetry,
            scanResults = scanResults
        )
    }

    private val secondaryState = combine(
        repository.services,
        repository.persistedGattEntries
    ) { services, persistedEntries ->
        SecondaryState(
            services = services,
            persistedEntries = persistedEntries
        )
    }

    private val filterState = combine(nameFilter, minRssiFilter) { filter, minRssi ->
        filter to minRssi
    }

    private val controlState = combine(
        repository.autoReconnectEnabled,
        backgroundReconnectEnabled,
        mtuRequest,
        latestError
    ) { autoReconnect, backgroundReconnect, mtu, error ->
        ControlState(
            autoReconnectEnabled = autoReconnect,
            backgroundReconnectEnabled = backgroundReconnect,
            mtuRequest = mtu,
            latestError = error
        )
    }

    val uiState: StateFlow<BLEUiState> = combine(
        primaryState,
        secondaryState,
        filterState,
        controlState
    ) { primary, secondary, filters, controls ->
        val (filter, minRssi) = filters

        val filtered = primary.scanResults.filter { packet ->
            val matchesName = filter.isBlank() ||
                packet.name.contains(filter, ignoreCase = true) ||
                packet.address.contains(filter, ignoreCase = true)
            matchesName && packet.rssi >= minRssi
        }

        BLEUiState(
            connectionState = primary.connectionState,
            connectedAddress = primary.connectedAddress,
            telemetry = primary.telemetry,
            scanResults = primary.scanResults,
            filteredScanResults = filtered,
            services = secondary.services,
            persistedEntries = secondary.persistedEntries,
            nameFilter = filter,
            minRssi = minRssi,
            autoReconnectEnabled = controls.autoReconnectEnabled,
            backgroundReconnectEnabled = controls.backgroundReconnectEnabled,
            mtuRequest = controls.mtuRequest,
            latestError = controls.latestError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BLEUiState())

    init {
        viewModelScope.launch {
            repository.logs.collect { entry ->
                appendLog(entry)
                if (entry.level == BleLogLevel.ERROR) {
                    latestError.value = entry.message
                }
            }
        }

        viewModelScope.launch {
            repository.operationResults.collect { result ->
                val level = if (result.success) BleLogLevel.INFO else BleLogLevel.ERROR
                appendLog(
                    BleLogEntry(
                        level = level,
                        tag = "ConnectionManager",
                        message = "${result.operationName}: ${result.message}",
                        payloadHex = result.value?.joinToString(" ") { byte -> "%02X".format(byte) }
                    )
                )

                if (!result.success) {
                    latestError.value = result.message
                }
            }
        }

        viewModelScope.launch {
            repository.telemetry.collect { telemetry ->
                val rssi = telemetry.rssi ?: return@collect
                _rssiHistory.update { history ->
                    (history + rssi).takeLast(40)
                }
            }
        }
    }

    fun onNameFilterChanged(value: String) {
        nameFilter.value = value
    }

    fun onMinRssiChanged(value: Int) {
        minRssiFilter.value = value
    }

    fun onMtuChanged(value: Int) {
        mtuRequest.value = value.coerceIn(23, 517)
    }

    fun setAutoReconnect(enabled: Boolean) {
        repository.setAutoReconnect(enabled)
    }

    fun setBackgroundReconnect(enabled: Boolean) {
        backgroundReconnectEnabled.value = enabled
        if (enabled) {
            repository.startBackgroundReconnect(uiState.value.connectedAddress)
        } else {
            repository.stopBackgroundReconnect()
        }
    }

    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
    }

    fun connect(address: String, ensureBond: Boolean = true) {
        viewModelScope.launch {
            val success = repository.connect(address, ensureBond)
            if (!success) {
                latestError.value = "Connection start failed"
            }
        }
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun discoverServices() {
        repository.discoverServices()
    }

    fun requestMtu() {
        repository.requestMtu(mtuRequest.value)
    }

    fun readRssi() {
        repository.readRssi()
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun refreshGattCache() {
        val refreshed = repository.refreshGattCache()
        appendLog(
            BleLogEntry(
                level = if (refreshed) BleLogLevel.INFO else BleLogLevel.WARN,
                tag = "BLEViewModel",
                message = if (refreshed) "GATT cache refreshed" else "GATT cache refresh not available"
            )
        )
    }

    fun readCharacteristic(serviceUuidText: String, characteristicUuidText: String) {
        val serviceUuid = parseUuid(serviceUuidText) ?: return
        val characteristicUuid = parseUuid(characteristicUuidText) ?: return
        repository.readCharacteristic(serviceUuid, characteristicUuid)
    }

    fun writeCharacteristic(
        serviceUuidText: String,
        characteristicUuidText: String,
        payloadHex: String,
        withoutResponse: Boolean
    ) {
        val serviceUuid = parseUuid(serviceUuidText) ?: return
        val characteristicUuid = parseUuid(characteristicUuidText) ?: return
        val payload = payloadHex.hexToByteArrayOrNull()
        if (payload == null) {
            latestError.value = "Invalid hex payload"
            return
        }

        val writeType = if (withoutResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        repository.writeCharacteristic(serviceUuid, characteristicUuid, payload, writeType)
    }

    fun setNotifications(
        serviceUuidText: String,
        characteristicUuidText: String,
        enabled: Boolean,
        useIndication: Boolean
    ) {
        val serviceUuid = parseUuid(serviceUuidText) ?: return
        val characteristicUuid = parseUuid(characteristicUuidText) ?: return
        repository.setNotifications(serviceUuid, characteristicUuid, enabled, useIndication)
    }

    private fun parseUuid(text: String): UUID? {
        return try {
            UUID.fromString(text)
        } catch (_: IllegalArgumentException) {
            latestError.value = "Invalid UUID: $text"
            null
        }
    }

    private fun appendLog(entry: BleLogEntry) {
        _logs.update { history ->
            (history + entry).takeLast(600)
        }
    }
}
