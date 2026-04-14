package com.nhealth.watchstatus.ble

import java.util.UUID

enum class BleConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    BLUETOOTH_OFF,
    ERROR
}

enum class BleLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class BleLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: BleLogLevel,
    val tag: String,
    val message: String,
    val payloadHex: String? = null
)

data class AdvertisementPacket(
    val address: String,
    val name: String,
    val rssi: Int,
    val connectable: Boolean,
    val advertisementHex: String,
    val manufacturerDataHex: Map<Int, String>,
    val serviceUuids: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

data class DescriptorMeta(
    val uuid: UUID,
    val name: String,
    val permissionsDescription: String,
    val valueHex: String
)

data class CharacteristicMeta(
    val uuid: UUID,
    val name: String,
    val propertiesDescription: String,
    val valueHex: String,
    val descriptors: List<DescriptorMeta>,
    val supportsNotify: Boolean,
    val supportsIndicate: Boolean,
    val supportsRead: Boolean,
    val supportsWrite: Boolean
)

data class ServiceMeta(
    val uuid: UUID,
    val name: String,
    val type: Int,
    val characteristics: List<CharacteristicMeta>
)

data class DeviceTelemetry(
    val batteryLevel: Int? = null,
    val manufacturer: String = "Not exposed by watch",
    val model: String = "Not exposed by watch",
    val firmware: String = "Not exposed by watch",
    val rssi: Int? = null,
    val mtu: Int = 23,
    val lastSyncEpochMillis: Long? = null,
    val deviceName: String = "Not connected",
    val deviceAddress: String? = null
)

data class OperationResult(
    val operationName: String,
    val success: Boolean,
    val status: Int = 0,
    val value: ByteArray? = null,
    val message: String = ""
)

data class GattErrorEvent(
    val status: Int,
    val address: String?,
    val timestamp: Long = System.currentTimeMillis()
)

data class NotificationPacket(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val value: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
)
