package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ResolverEntity::class], version = 2, exportSchema = false)
abstract class ResolverDatabase : RoomDatabase() {
    abstract fun resolverDao(): ResolverDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE resolvers ADD COLUMN udpStatus TEXT NOT NULL DEFAULT 'NOT_TESTED'")
                database.execSQL("ALTER TABLE resolvers ADD COLUMN udpLatencyMs INTEGER")
                database.execSQL("ALTER TABLE resolvers ADD COLUMN tcpStatus TEXT NOT NULL DEFAULT 'NOT_TESTED'")
                database.execSQL("ALTER TABLE resolvers ADD COLUMN tcpLatencyMs INTEGER")
            }
        }

        @Volatile private var instance: ResolverDatabase? = null

        fun getInstance(context: Context): ResolverDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ResolverDatabase::class.java,
                    "dns-sniperx.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
