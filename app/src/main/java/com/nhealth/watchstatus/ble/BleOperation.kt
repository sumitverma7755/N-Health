package com.nhealth.watchstatus.ble

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

sealed interface BleOperation {
    val operationName: String
}

data object DiscoverServicesOp : BleOperation {
    override val operationName: String = "Discover Services"
}

data class RequestMtuOp(val mtu: Int) : BleOperation {
    override val operationName: String = "Request MTU"
}

data class ReadCharacteristicOp(
    val serviceUuid: UUID,
    val characteristicUuid: UUID
) : BleOperation {
    override val operationName: String = "Read Characteristic"
}

data class WriteCharacteristicOp(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val payload: ByteArray,
    val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
) : BleOperation {
    override val operationName: String = "Write Characteristic"
}

data class SetNotifyOp(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val enabled: Boolean,
    val useIndication: Boolean = false
) : BleOperation {
    override val operationName: String = "Set Notification"
}

data object ReadRssiOp : BleOperation {
    override val operationName: String = "Read RSSI"
}
