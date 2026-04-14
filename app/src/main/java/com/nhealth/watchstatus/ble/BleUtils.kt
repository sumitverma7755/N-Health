package com.nhealth.watchstatus.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.Locale
import java.util.UUID

private val knownServices = mapOf(
    UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb") to "Device Information",
    UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb") to "Battery Service",
    UUID.fromString("00001800-0000-1000-8000-00805f9b34fb") to "Generic Access",
    UUID.fromString("00001801-0000-1000-8000-00805f9b34fb") to "Generic Attribute"
)

private val knownCharacteristics = mapOf(
    UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb") to "Battery Level",
    UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb") to "Manufacturer Name",
    UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb") to "Model Number",
    UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb") to "Firmware Revision"
)

private val knownDescriptors = mapOf(
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") to "Client Characteristic Configuration"
)

fun UUID.friendlyServiceName(): String = knownServices[this] ?: "Custom or hidden service"

fun UUID.friendlyCharacteristicName(): String =
    knownCharacteristics[this] ?: "Custom characteristic"

fun UUID.friendlyDescriptorName(): String = knownDescriptors[this] ?: "Custom descriptor"

fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { String.format(Locale.US, "%02X", it) }

fun String.hexToByteArrayOrNull(): ByteArray? {
    val clean = replace(" ", "")
    if (clean.length % 2 != 0 || clean.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
        return null
    }

    return clean.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun characteristicPropertiesToString(properties: Int): String {
    val flags = mutableListOf<String>()
    if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) flags += "READ"
    if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) flags += "WRITE"
    if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
        flags += "WRITE_NO_RESP"
    }
    if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) flags += "NOTIFY"
    if ((properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) flags += "INDICATE"
    if ((properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) flags += "BROADCAST"
    return if (flags.isEmpty()) "NONE" else flags.joinToString(" | ")
}

fun descriptorPermissionsToString(permissions: Int): String {
    val flags = mutableListOf<String>()
    if ((permissions and BluetoothGattDescriptor.PERMISSION_READ) != 0) flags += "READ"
    if ((permissions and BluetoothGattDescriptor.PERMISSION_WRITE) != 0) flags += "WRITE"
    if (flags.isEmpty()) return "NONE"
    return flags.joinToString(" | ")
}

fun parseUtf8OrHex(value: ByteArray): String {
    val asText = value.toString(Charsets.UTF_8).replace("\u0000", "").trim()
    return if (asText.isBlank()) value.toHexString() else "$asText (${value.toHexString()})"
}

fun isStandardService(uuid: UUID): Boolean = knownServices.containsKey(uuid)
