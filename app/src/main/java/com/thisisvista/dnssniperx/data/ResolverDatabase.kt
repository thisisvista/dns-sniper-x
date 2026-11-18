package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ResolverEntity::class], version = 1, exportSchema = false)
abstract class ResolverDatabase : RoomDatabase() {
    abstract fun resolverDao(): ResolverDao

    companion object {
        @Volatile private var instance: ResolverDatabase? = null

        fun getInstance(context: Context): ResolverDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ResolverDatabase::class.java,
                    "dns-sniperx.db"
                ).build().also { instance = it }
            }
    }
}
