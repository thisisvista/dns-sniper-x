package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ResolverEntity::class, ScanHistoryEntity::class, DohHistoryEntity::class], version = 5, exportSchema = false)
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE resolvers ADD COLUMN additionalSources TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        resolverAddress TEXT NOT NULL,
                        resolverPort INTEGER NOT NULL,
                        testedAt INTEGER NOT NULL,
                        udpStatus TEXT NOT NULL,
                        udpLatencyMs INTEGER,
                        tcpStatus TEXT NOT NULL,
                        tcpLatencyMs INTEGER,
                        scanMode TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scan_history_testedAt ON scan_history(testedAt)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS doh_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider TEXT NOT NULL,
                        endpoint TEXT NOT NULL,
                        testedAt INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        latencyMs INTEGER,
                        detail TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_doh_history_testedAt ON doh_history(testedAt)")
            }
        }

        @Volatile private var instance: ResolverDatabase? = null

        fun getInstance(context: Context): ResolverDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ResolverDatabase::class.java,
                    "dns-sniperx.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
