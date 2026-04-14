package com.nhealth.watchstatus.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GattDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<GattEntryEntity>)

    @Query("SELECT * FROM gatt_entries WHERE deviceAddress = :address ORDER BY serviceUuid, characteristicUuid, descriptorUuid")
    fun observeForDevice(address: String): Flow<List<GattEntryEntity>>

    @Query("DELETE FROM gatt_entries WHERE deviceAddress = :address")
    suspend fun clearForDevice(address: String)
}
