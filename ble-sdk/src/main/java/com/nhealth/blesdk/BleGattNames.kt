package com.nhealth.blesdk

import java.util.UUID

object BleGattNames {
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val GENERIC_ACCESS_SERVICE: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val GENERIC_ATTRIBUTE_SERVICE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")

    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    fun friendlyServiceName(uuid: UUID): String {
        return when (uuid) {
            DEVICE_INFO_SERVICE -> "Device Information"
            BATTERY_SERVICE -> "Battery Service"
            GENERIC_ACCESS_SERVICE -> "Generic Access"
            GENERIC_ATTRIBUTE_SERVICE -> "Generic Attribute"
            else -> "Custom or hidden service"
        }
    }

    fun friendlyCharacteristicName(uuid: UUID): String {
        return when (uuid) {
            BATTERY_LEVEL -> "Battery Level"
            MANUFACTURER_NAME -> "Manufacturer Name"
            MODEL_NUMBER -> "Model Number"
            FIRMWARE_REVISION -> "Firmware Revision"
            else -> "Custom characteristic"
        }
    }
}
