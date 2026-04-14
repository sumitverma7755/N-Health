package com.nhealth.blesdk

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import java.util.Locale
import java.util.UUID

data class BleDeviceSnapshot(
    val name: String?,
    val address: String,
    val rssi: Int,
    val txPower: Int?,
    val serviceUuids: List<UUID>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<UUID, ByteArray>,
    val rawAdvertisement: ByteArray?
)

object NHealthBleSdk {
    fun snapshot(scanResult: ScanResult): BleDeviceSnapshot {
        val record = scanResult.scanRecord
        val serviceUuids = record?.serviceUuids?.mapNotNull { it?.uuid }.orEmpty()

        return BleDeviceSnapshot(
            name = scanResult.device.name ?: record?.deviceName,
            address = scanResult.device.address,
            rssi = scanResult.rssi,
            txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
            serviceUuids = serviceUuids,
            manufacturerData = toManufacturerMap(record?.manufacturerSpecificData),
            serviceData = record?.serviceData?.entries
                ?.associate { (parcelUuid, value) -> parcelUuid.uuid to value }
                .orEmpty(),
            rawAdvertisement = record?.bytes
        )
    }

    fun decodeUtf8OrHex(value: ByteArray?): String? {
        if (value == null) return null
        val text = value.toString(Charsets.UTF_8).replace("\u0000", "").trim()
        return if (text.isBlank()) value.toHexString() else "$text (${value.toHexString()})"
    }

    fun batteryPercent(value: ByteArray?): Int? {
        val battery = value?.firstOrNull()?.toInt() ?: return null
        return battery.takeIf { it in 0..100 }
    }

    fun looksLikeOnePlusWatch(snapshot: BleDeviceSnapshot): Boolean {
        val name = snapshot.name?.lowercase(Locale.US).orEmpty()
        val hasExpectedService = snapshot.serviceUuids.any {
            it == BleGattNames.BATTERY_SERVICE || it == BleGattNames.DEVICE_INFO_SERVICE
        }
        return name.contains("oneplus") || name.contains("nord") || hasExpectedService
    }

    private fun toManufacturerMap(data: SparseArray<ByteArray>?): Map<Int, ByteArray> {
        if (data == null || data.size() == 0) return emptyMap()
        val map = LinkedHashMap<Int, ByteArray>(data.size())
        for (index in 0 until data.size()) {
            map[data.keyAt(index)] = data.valueAt(index)
        }
        return map
    }
}

fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte -> String.format(Locale.US, "%02X", byte) }
