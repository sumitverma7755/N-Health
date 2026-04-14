package com.nhealth.watchstatus.data.local

import androidx.room.Entity

@Entity(
    tableName = "gatt_entries",
    primaryKeys = ["deviceAddress", "serviceUuid", "characteristicUuid", "descriptorUuid"]
)
data class GattEntryEntity(
    val deviceAddress: String,
    val serviceUuid: String,
    val serviceName: String,
    val characteristicUuid: String = "",
    val characteristicName: String = "",
    val descriptorUuid: String = "",
    val descriptorName: String = "",
    val properties: String = "",
    val valueHex: String = "",
    val updatedAt: Long
)
