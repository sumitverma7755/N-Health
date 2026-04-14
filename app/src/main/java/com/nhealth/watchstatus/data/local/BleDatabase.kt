package com.nhealth.watchstatus.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GattEntryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BleDatabase : RoomDatabase() {

    abstract fun gattDao(): GattDao

    companion object {
        fun create(context: Context): BleDatabase {
            return Room.databaseBuilder(
                context,
                BleDatabase::class.java,
                "ble_explorer.db"
            ).fallbackToDestructiveMigration()
                .build()
        }
    }
}
