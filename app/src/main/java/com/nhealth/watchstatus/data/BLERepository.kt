package com.nhealth.watchstatus.data

import android.content.Context
import com.nhealth.watchstatus.ble.BLEManager
import com.nhealth.watchstatus.ble.BleForegroundService
import com.nhealth.watchstatus.ble.BleOperation
import com.nhealth.watchstatus.ble.ConnectionManager
import com.nhealth.watchstatus.ble.DiscoverServicesOp
import com.nhealth.watchstatus.ble.ReadCharacteristicOp
import com.nhealth.watchstatus.ble.RequestMtuOp
import com.nhealth.watchstatus.ble.SetNotifyOp
import com.nhealth.watchstatus.ble.WriteCharacteristicOp
import com.nhealth.watchstatus.data.local.GattDao
import com.nhealth.watchstatus.data.local.GattEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BLERepository(
    private val appContext: Context,
    private val bleManager: BLEManager,
    private val connectionManager: ConnectionManager,
    private val gattDao: GattDao,
    private val appScope: CoroutineScope
) {

    val scanResults = bleManager.scanResults
        .map { map -> map.values.sortedByDescending { it.rssi } }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState = connectionManager.managedState
    val telemetry = bleManager.telemetry
    val services = bleManager.services
    val connectedAddress = bleManager.connectedAddress
    val logs = bleManager.logs
    val notifications = bleManager.notifications
    val operationResults = connectionManager.operationResults
    val autoReconnectEnabled = connectionManager.autoReconnectEnabled

    val persistedGattEntries: StateFlow<List<GattEntryEntity>> = connectedAddress
        .flatMapLatest { address ->
            if (address.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                gattDao.observeForDevice(address)
            }
        }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        appScope.launch {
            combine(connectedAddress, services) { address, discoveredServices ->
                address to discoveredServices
            }.collect { (address, discoveredServices) ->
                if (!address.isNullOrBlank() && discoveredServices.isNotEmpty()) {
                    persistDiscoveredServices(address, discoveredServices)
                }
            }
        }
    }

    fun startScan() {
        bleManager.clearScanResults()
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    suspend fun connect(address: String, ensureBond: Boolean = true): Boolean {
        return connectionManager.connect(address, ensureBond)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun setAutoReconnect(enabled: Boolean) {
        connectionManager.setAutoReconnect(enabled)
    }

    fun discoverServices() {
        connectionManager.enqueueOperation(DiscoverServicesOp)
    }

    fun requestMtu(mtu: Int) {
        connectionManager.enqueueOperation(RequestMtuOp(mtu))
    }

    fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID) {
        connectionManager.enqueueOperation(ReadCharacteristicOp(serviceUuid, characteristicUuid))
    }

    fun writeCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray,
        writeType: Int
    ) {
        connectionManager.enqueueOperation(
            WriteCharacteristicOp(
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                payload = payload,
                writeType = writeType
            )
        )
    }

    fun setNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enabled: Boolean,
        useIndication: Boolean
    ) {
        connectionManager.enqueueOperation(
            SetNotifyOp(
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                enabled = enabled,
                useIndication = useIndication
            )
        )
    }

    fun readRssi() {
        connectionManager.enqueueOperation(com.nhealth.watchstatus.ble.ReadRssiOp)
    }

    fun enqueueOperation(operation: BleOperation) {
        connectionManager.enqueueOperation(operation)
    }

    fun refreshGattCache(): Boolean {
        return bleManager.refreshGattCache()
    }

    fun startBackgroundReconnect(deviceAddress: String?) {
        BleForegroundService.start(appContext, deviceAddress)
    }

    fun stopBackgroundReconnect() {
        BleForegroundService.stop(appContext)
    }

    private suspend fun persistDiscoveredServices(address: String, services: List<com.nhealth.watchstatus.ble.ServiceMeta>) {
        val now = System.currentTimeMillis()
        val entries = mutableListOf<GattEntryEntity>()

        services.forEach { service ->
            entries += GattEntryEntity(
                deviceAddress = address,
                serviceUuid = service.uuid.toString(),
                serviceName = service.name,
                updatedAt = now
            )

            service.characteristics.forEach { characteristic ->
                entries += GattEntryEntity(
                    deviceAddress = address,
                    serviceUuid = service.uuid.toString(),
                    serviceName = service.name,
                    characteristicUuid = characteristic.uuid.toString(),
                    characteristicName = characteristic.name,
                    properties = characteristic.propertiesDescription,
                    valueHex = characteristic.valueHex,
                    updatedAt = now
                )

                characteristic.descriptors.forEach { descriptor ->
                    entries += GattEntryEntity(
                        deviceAddress = address,
                        serviceUuid = service.uuid.toString(),
                        serviceName = service.name,
                        characteristicUuid = characteristic.uuid.toString(),
                        characteristicName = characteristic.name,
                        descriptorUuid = descriptor.uuid.toString(),
                        descriptorName = descriptor.name,
                        properties = descriptor.permissionsDescription,
                        valueHex = descriptor.valueHex,
                        updatedAt = now
                    )
                }
            }
        }

        gattDao.upsert(entries)
    }
}
