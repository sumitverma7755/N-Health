package com.nhealth.watchstatus.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class BLEManager(private val context: Context) {

    companion object {
        private const val TAG = "BLEManager"
        private const val OP_TIMEOUT_MS = 12000L
        private const val RSSI_POLL_INTERVAL_MS = 5000L

        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private data class PendingOperation(
        val operation: BleOperation,
        val deferred: CompletableDeferred<OperationResult>
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val pendingOperationRef = AtomicReference<PendingOperation?>(null)

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var manualDisconnect = false
    private var rssiJob: Job? = null
    private var bondReceiverRegistered = false

    private val _scanResults = MutableStateFlow<Map<String, AdvertisementPacket>>(emptyMap())
    val scanResults: StateFlow<Map<String, AdvertisementPacket>> = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.IDLE)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _services = MutableStateFlow<List<ServiceMeta>>(emptyList())
    val services: StateFlow<List<ServiceMeta>> = _services.asStateFlow()

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _logs = MutableSharedFlow<BleLogEntry>(
        extraBufferCapacity = 600,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logs: SharedFlow<BleLogEntry> = _logs.asSharedFlow()

    private val _gattErrors = MutableSharedFlow<GattErrorEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gattErrors: SharedFlow<GattErrorEvent> = _gattErrors.asSharedFlow()

    private val _notifications = MutableSharedFlow<NotificationPacket>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifications: SharedFlow<NotificationPacket> = _notifications.asSharedFlow()

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                return
            }

            val device = getBondDevice(intent) ?: return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            emitLog(
                level = BleLogLevel.INFO,
                message = "Bond state changed ${device.address}: $prevState -> $bondState"
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            emitLog(
                level = BleLogLevel.INFO,
                message = "Connection state changed status=$status newState=$newState address=${gatt.device.address}"
            )

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                bluetoothGatt = gatt
                _connectedAddress.value = gatt.device.address
                _connectionState.value = BleConnectionState.CONNECTED
                _telemetry.update {
                    it.copy(
                        deviceName = gatt.device.name ?: gatt.device.address,
                        deviceAddress = gatt.device.address,
                        batteryLevel = null,
                        lastSyncEpochMillis = System.currentTimeMillis()
                    )
                }
                applyMetadataFallbacks()
                startRssiMonitoring()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(247)
                }
                gatt.discoverServices()
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopRssiMonitoring()
                val wasManualDisconnect = manualDisconnect
                _connectionState.value = BleConnectionState.IDLE
                _services.value = emptyList()
                _connectedAddress.value = null
                _telemetry.update {
                    it.copy(
                        rssi = null,
                        deviceAddress = null,
                        deviceName = "Not connected"
                    )
                }

                if (!wasManualDisconnect && status != BluetoothGatt.GATT_SUCCESS) {
                    _gattErrors.tryEmit(GattErrorEvent(status = status, address = gatt.device.address))
                }

                failPendingOperation("Disconnected before operation completed", status)
                gatt.close()
                bluetoothGatt = null
                manualDisconnect = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog(BleLogLevel.ERROR, "Service discovery failed with status=$status")
                completePendingOperation(
                    predicate = { it is DiscoverServicesOp },
                    result = OperationResult(
                        operationName = DiscoverServicesOp.operationName,
                        success = false,
                        status = status,
                        message = "Service discovery failed"
                    )
                )
                return
            }

            val discoveredServices = mapServices(gatt)
            _services.value = discoveredServices
            emitLog(
                level = BleLogLevel.INFO,
                message = "Discovered ${discoveredServices.size} services"
            )

            discoveredServices.forEach { service ->
                emitLog(
                    level = BleLogLevel.DEBUG,
                    message = "Service ${service.uuid} (${service.name})"
                )
                if (!isStandardService(service.uuid)) {
                    emitLog(
                        level = BleLogLevel.WARN,
                        message = "Custom or hidden service discovered: ${service.uuid}"
                    )
                }

                service.characteristics.forEach { characteristic ->
                    emitLog(
                        level = BleLogLevel.DEBUG,
                        message = "  Characteristic ${characteristic.uuid} props=${characteristic.propertiesDescription}"
                    )
                    characteristic.descriptors.forEach { descriptor ->
                        emitLog(
                            level = BleLogLevel.DEBUG,
                            message = "    Descriptor ${descriptor.uuid} perms=${descriptor.permissionsDescription}"
                        )
                    }
                }
            }

            completePendingOperation(
                predicate = { it is DiscoverServicesOp },
                result = OperationResult(
                    operationName = DiscoverServicesOp.operationName,
                    success = true,
                    status = status,
                    message = "Services discovered"
                )
            )

            // Standard profile readouts help quickly populate dashboard data when exposed.
            managerScope.launch {
                performOperation(ReadCharacteristicOp(BATTERY_SERVICE_UUID, BATTERY_LEVEL_UUID))
                performOperation(ReadCharacteristicOp(DEVICE_INFO_SERVICE_UUID, MANUFACTURER_NAME_UUID))
                performOperation(ReadCharacteristicOp(DEVICE_INFO_SERVICE_UUID, MODEL_NUMBER_UUID))
                performOperation(ReadCharacteristicOp(DEVICE_INFO_SERVICE_UUID, FIRMWARE_REV_UUID))
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(
                characteristic = characteristic,
                value = characteristic.value ?: byteArrayOf(),
                status = status
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(
                characteristic = characteristic,
                value = value,
                status = status
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completePendingOperation(
                predicate = {
                    it is WriteCharacteristicOp &&
                        it.serviceUuid == characteristic.service.uuid &&
                        it.characteristicUuid == characteristic.uuid
                },
                result = OperationResult(
                    operationName = "Write Characteristic",
                    success = status == BluetoothGatt.GATT_SUCCESS,
                    status = status,
                    message = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Write success"
                    } else {
                        "Write failed"
                    }
                )
            )
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChanged(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(characteristic, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completePendingOperation(
                predicate = {
                    it is SetNotifyOp &&
                        it.serviceUuid == descriptor.characteristic.service.uuid &&
                        it.characteristicUuid == descriptor.characteristic.uuid
                },
                result = OperationResult(
                    operationName = "Set Notification",
                    success = status == BluetoothGatt.GATT_SUCCESS,
                    status = status,
                    message = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Notification config updated"
                    } else {
                        "Notification config failed"
                    }
                )
            )
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _telemetry.update { it.copy(rssi = rssi) }
            }

            completePendingOperation(
                predicate = { it is ReadRssiOp },
                result = OperationResult(
                    operationName = ReadRssiOp.operationName,
                    success = status == BluetoothGatt.GATT_SUCCESS,
                    status = status,
                    value = null,
                    message = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "RSSI updated: $rssi"
                    } else {
                        "RSSI read failed"
                    }
                )
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _telemetry.update { it.copy(mtu = mtu) }
            }

            completePendingOperation(
                predicate = { it is RequestMtuOp },
                result = OperationResult(
                    operationName = "Request MTU",
                    success = status == BluetoothGatt.GATT_SUCCESS,
                    status = status,
                    message = if (status == BluetoothGatt.GATT_SUCCESS) "MTU=$mtu" else "MTU failed"
                )
            )
        }
    }

    init {
        registerBondReceiver()
    }

    fun isBleSupported(): Boolean {
        return adapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun clearScanResults() {
        _scanResults.value = emptyMap()
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!isBleSupported()) {
            _connectionState.value = BleConnectionState.ERROR
            emitLog(BleLogLevel.ERROR, "BLE not supported")
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = BleConnectionState.BLUETOOTH_OFF
            emitLog(BleLogLevel.WARN, "Bluetooth is disabled")
            return
        }
        if (scanCallback != null) {
            emitLog(BleLogLevel.DEBUG, "Scan already running")
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            emitLog(BleLogLevel.ERROR, "BluetoothLeScanner is null")
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onAdvertisement(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::onAdvertisement)
            }

            override fun onScanFailed(errorCode: Int) {
                emitLog(BleLogLevel.ERROR, "Scan failed errorCode=$errorCode")
                _connectionState.value = BleConnectionState.ERROR
                scanCallback = null
            }
        }

        scanCallback = callback
        _connectionState.value = BleConnectionState.SCANNING

        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            emitLog(BleLogLevel.INFO, "BLE scan started")
        } catch (security: SecurityException) {
            scanCallback = null
            _connectionState.value = BleConnectionState.ERROR
            emitLog(BleLogLevel.ERROR, "Missing scan permission: ${security.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val callback = scanCallback ?: return
        try {
            scanner.stopScan(callback)
        } catch (security: SecurityException) {
            emitLog(BleLogLevel.ERROR, "Missing stopScan permission: ${security.message}")
        }
        scanCallback = null

        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.IDLE
        }
        emitLog(BleLogLevel.INFO, "BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    fun ensureBond(address: String): Boolean {
        val device = getDevice(address) ?: return false
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return true
        }

        return try {
            val started = device.createBond()
            emitLog(BleLogLevel.INFO, "Bond request started for ${device.address}: $started")
            started
        } catch (security: SecurityException) {
            emitLog(BleLogLevel.ERROR, "Missing permission for createBond: ${security.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, autoConnect: Boolean = false): Boolean {
        val device = getDevice(address) ?: return false
        stopScan()
        manualDisconnect = false
        _connectionState.value = BleConnectionState.CONNECTING

        bluetoothGatt?.close()
        bluetoothGatt = null

        return try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, autoConnect, gattCallback)
            }
            emitLog(BleLogLevel.INFO, "Connecting to ${device.address}")
            bluetoothGatt != null
        } catch (security: SecurityException) {
            _connectionState.value = BleConnectionState.ERROR
            emitLog(BleLogLevel.ERROR, "Connect permission denied: ${security.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(manual: Boolean = true) {
        manualDisconnect = manual
        _connectionState.value = BleConnectionState.DISCONNECTING
        stopScan()
        try {
            bluetoothGatt?.disconnect()
        } catch (security: SecurityException) {
            emitLog(BleLogLevel.ERROR, "Disconnect permission denied: ${security.message}")
        }
    }

    fun refreshGattCache(): Boolean {
        val gatt = bluetoothGatt ?: return false
        return try {
            // Reflection is required because refresh() is hidden in the Android API.
            val refresh = gatt.javaClass.getMethod("refresh")
            val result = (refresh.invoke(gatt) as? Boolean) == true
            emitLog(BleLogLevel.INFO, "Gatt cache refresh result=$result")
            result
        } catch (error: Exception) {
            emitLog(BleLogLevel.WARN, "Gatt cache refresh unavailable: ${error.message}")
            false
        }
    }

    suspend fun performOperation(operation: BleOperation): OperationResult {
        val gatt = bluetoothGatt
            ?: return OperationResult(
                operationName = operation.operationName,
                success = false,
                message = "No connected GATT"
            )

        val deferred = CompletableDeferred<OperationResult>()
        val pending = PendingOperation(operation, deferred)

        val started = operationMutex.withLock {
            if (pendingOperationRef.get() != null) {
                return@withLock false
            }
            pendingOperationRef.set(pending)
            startOperation(gatt, operation)
        }

        if (!started) {
            pendingOperationRef.compareAndSet(pending, null)
            return OperationResult(
                operationName = operation.operationName,
                success = false,
                message = "Operation could not start"
            )
        }

        val result = withTimeoutOrNull(OP_TIMEOUT_MS) {
            deferred.await()
        }

        if (result == null) {
            pendingOperationRef.compareAndSet(pending, null)
            return OperationResult(
                operationName = operation.operationName,
                success = false,
                message = "Operation timed out"
            )
        }

        return result
    }

    fun close() {
        stopRssiMonitoring()
        stopScan()
        disconnect(manual = true)
        bluetoothGatt?.close()
        bluetoothGatt = null
        unregisterBondReceiver()
        managerScope.coroutineContext[Job]?.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun onAdvertisement(result: ScanResult) {
        val record = result.scanRecord
        val payload = record?.bytes ?: byteArrayOf()
        val manufacturerMap = parseManufacturerData(record?.manufacturerSpecificData)

        val packet = AdvertisementPacket(
            address = result.device.address,
            name = result.device.name ?: record?.deviceName ?: "Unknown",
            rssi = result.rssi,
            connectable = result.isConnectable,
            advertisementHex = payload.toHexString(),
            manufacturerDataHex = manufacturerMap,
            serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
            timestamp = System.currentTimeMillis()
        )

        _scanResults.update { previous ->
            val mutable = previous.toMutableMap()
            mutable[result.device.address] = packet
            mutable
        }

        emitLog(
            level = BleLogLevel.DEBUG,
            message = "ADV ${packet.name} (${packet.address}) RSSI=${packet.rssi}",
            payloadHex = packet.advertisementHex
        )
    }

    private fun mapServices(gatt: BluetoothGatt): List<ServiceMeta> {
        return gatt.services.map { service ->
            ServiceMeta(
                uuid = service.uuid,
                name = service.uuid.friendlyServiceName(),
                type = service.type,
                characteristics = service.characteristics.map { characteristic ->
                    CharacteristicMeta(
                        uuid = characteristic.uuid,
                        name = characteristic.uuid.friendlyCharacteristicName(),
                        propertiesDescription = characteristicPropertiesToString(characteristic.properties),
                        valueHex = characteristic.value?.toHexString().orEmpty(),
                        descriptors = characteristic.descriptors.map { descriptor ->
                            DescriptorMeta(
                                uuid = descriptor.uuid,
                                name = descriptor.uuid.friendlyDescriptorName(),
                                permissionsDescription = descriptorPermissionsToString(descriptor.permissions),
                                valueHex = descriptor.value?.toHexString().orEmpty()
                            )
                        },
                        supportsNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                        supportsIndicate = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                        supportsRead = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                        supportsWrite = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    )
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOperation(gatt: BluetoothGatt, operation: BleOperation): Boolean {
        // All operations are routed through this single gateway to keep GATT sequencing safe.
        return when (operation) {
            is DiscoverServicesOp -> gatt.discoverServices()
            is RequestMtuOp -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(operation.mtu)
                } else {
                    false
                }
            }
            is ReadCharacteristicOp -> {
                val characteristic = findCharacteristic(
                    gatt,
                    operation.serviceUuid,
                    operation.characteristicUuid
                ) ?: return false
                gatt.readCharacteristic(characteristic)
            }
            is WriteCharacteristicOp -> {
                val characteristic = findCharacteristic(
                    gatt,
                    operation.serviceUuid,
                    operation.characteristicUuid
                ) ?: return false
                writeCharacteristicCompat(
                    gatt = gatt,
                    characteristic = characteristic,
                    value = operation.payload,
                    writeType = operation.writeType
                )
            }
            is SetNotifyOp -> {
                val characteristic = findCharacteristic(
                    gatt,
                    operation.serviceUuid,
                    operation.characteristicUuid
                ) ?: return false

                val setOk = gatt.setCharacteristicNotification(characteristic, operation.enabled)
                if (!setOk) {
                    return false
                }

                val cccd = characteristic.getDescriptor(CLIENT_CONFIG_UUID) ?: return setOk
                val descriptorValue = when {
                    !operation.enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    operation.useIndication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                writeDescriptorCompat(gatt, cccd, descriptorValue)
            }
            is ReadRssiOp -> gatt.readRemoteRssi()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BluetoothGattCharacteristic? {
        return gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
    }

    private fun completePendingOperation(
        predicate: (BleOperation) -> Boolean,
        result: OperationResult
    ) {
        val pending = pendingOperationRef.get() ?: return
        if (!predicate(pending.operation)) {
            return
        }

        if (pendingOperationRef.compareAndSet(pending, null)) {
            pending.deferred.complete(result)
        }
    }

    private fun failPendingOperation(message: String, status: Int = -1) {
        val pending = pendingOperationRef.getAndSet(null) ?: return
        pending.deferred.complete(
            OperationResult(
                operationName = pending.operation.operationName,
                success = false,
                status = status,
                message = message
            )
        )
    }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        val valueHex = value.toHexString()
        val parsed = parseUtf8OrHex(value)

        if (status == BluetoothGatt.GATT_SUCCESS) {
            emitLog(
                level = BleLogLevel.INFO,
                message = "Read ${characteristic.uuid} -> $parsed",
                payloadHex = valueHex
            )

            updateTelemetryFromCharacteristic(characteristic.uuid, value)
            applyMetadataFallbacks()
            _telemetry.update { it.copy(lastSyncEpochMillis = System.currentTimeMillis()) }
        } else {
            emitLog(
                level = BleLogLevel.WARN,
                message = "Read failed ${characteristic.uuid} status=$status"
            )
        }

        completePendingOperation(
            predicate = {
                it is ReadCharacteristicOp &&
                    it.serviceUuid == characteristic.service.uuid &&
                    it.characteristicUuid == characteristic.uuid
            },
            result = OperationResult(
                operationName = "Read Characteristic",
                success = status == BluetoothGatt.GATT_SUCCESS,
                status = status,
                value = value,
                message = if (status == BluetoothGatt.GATT_SUCCESS) "Read success" else "Read failed"
            )
        )
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        emitLog(
            level = BleLogLevel.INFO,
            message = "Notify ${characteristic.uuid} value=${parseUtf8OrHex(value)}",
            payloadHex = value.toHexString()
        )

        _notifications.tryEmit(
            NotificationPacket(
                serviceUuid = characteristic.service.uuid,
                characteristicUuid = characteristic.uuid,
                value = value
            )
        )

        updateTelemetryFromCharacteristic(characteristic.uuid, value)
        applyMetadataFallbacks()
        _telemetry.update { it.copy(lastSyncEpochMillis = System.currentTimeMillis()) }
    }

    private fun updateTelemetryFromCharacteristic(
        characteristicUuid: UUID,
        value: ByteArray
    ) {
        when (characteristicUuid) {
            BATTERY_LEVEL_UUID -> {
                val battery = value.firstOrNull()?.toInt()?.and(0xFF)
                _telemetry.update { it.copy(batteryLevel = battery) }
            }

            MANUFACTURER_NAME_UUID -> {
                val text = value.toString(Charsets.UTF_8).replace("\u0000", "").trim()
                if (text.isNotBlank()) {
                    _telemetry.update { it.copy(manufacturer = text) }
                }
            }

            MODEL_NUMBER_UUID -> {
                val text = value.toString(Charsets.UTF_8).replace("\u0000", "").trim()
                if (text.isNotBlank()) {
                    _telemetry.update { it.copy(model = text) }
                }
            }

            FIRMWARE_REV_UUID -> {
                val text = value.toString(Charsets.UTF_8).replace("\u0000", "").trim()
                if (text.isNotBlank()) {
                    _telemetry.update { it.copy(firmware = text) }
                }
            }
        }
    }

    private fun applyMetadataFallbacks() {
        val snapshot = _telemetry.value
        val deviceName = snapshot.deviceName

        val inferredManufacturer = when {
            deviceName.lowercase(Locale.US).contains("oneplus") ||
                deviceName.lowercase(Locale.US).contains("nord") -> "OnePlus"
            else -> "Not exposed by watch"
        }

        val inferredModel = when {
            deviceName.isBlank() || deviceName == "Not connected" -> "Not exposed by watch"
            Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(deviceName) -> "Not exposed by watch"
            else -> deviceName
        }

        _telemetry.update {
            it.copy(
                manufacturer = if (it.manufacturer == "Not exposed by watch") {
                    inferredManufacturer
                } else {
                    it.manufacturer
                },
                model = if (it.model == "Not exposed by watch") inferredModel else it.model,
                firmware = if (it.firmware.isBlank()) "Not exposed by watch" else it.firmware
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRssiMonitoring() {
        stopRssiMonitoring()
        rssiJob = managerScope.launch {
            while (isActive && bluetoothGatt != null) {
                try {
                    bluetoothGatt?.readRemoteRssi()
                } catch (security: SecurityException) {
                    emitLog(BleLogLevel.WARN, "RSSI read denied: ${security.message}")
                }
                delay(RSSI_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopRssiMonitoring() {
        rssiJob?.cancel()
        rssiJob = null
    }

    private fun parseManufacturerData(data: android.util.SparseArray<ByteArray>?): Map<Int, String> {
        if (data == null || data.size() == 0) {
            return emptyMap()
        }

        val output = mutableMapOf<Int, String>()
        for (index in 0 until data.size()) {
            val id = data.keyAt(index)
            val value = data.valueAt(index)
            output[id] = value?.toHexString().orEmpty()
        }
        return output
    }

    private fun emitLog(level: BleLogLevel, message: String, payloadHex: String? = null) {
        _logs.tryEmit(
            BleLogEntry(
                level = level,
                tag = TAG,
                message = message,
                payloadHex = payloadHex
            )
        )
    }

    private fun getBondDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) {
            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) {
            return
        }

        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered by Android.
        }
        bondReceiverRegistered = false
    }

    private fun getDevice(address: String): BluetoothDevice? {
        val btAdapter = adapter ?: return null
        return try {
            btAdapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            emitLog(BleLogLevel.ERROR, "Invalid Bluetooth MAC address: $address")
            null
        }
    }
}
