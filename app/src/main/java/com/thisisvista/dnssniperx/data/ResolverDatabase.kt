package com.thisisvista.dnssniperx.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ResolverEntity::class, ScanHistoryEntity::class, DohHistoryEntity::class, DotHistoryEntity::class, SourceCollectionHistoryEntity::class, TunnelProfileEntity::class, AppLogEntity::class, ProviderEntity::class, ResolverSourceEntity::class, ResolverEvidenceEntity::class, ProviderDiscoveryStateEntity::class], version = 12, exportSchema = false)
abstract class ResolverDatabase : RoomDatabase() {
    abstract fun resolverDao(): ResolverDao
    abstract fun e2eHistoryDao(): E2eHistoryDao

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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS dot_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider TEXT NOT NULL,
                        hostname TEXT NOT NULL,
                        testedAt INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        latencyMs INTEGER,
                        detail TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_dot_history_testedAt ON dot_history(testedAt)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_collection_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        retrievedAt INTEGER NOT NULL,
                        foundCount INTEGER NOT NULL,
                        error TEXT,
                        termsNote TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_collection_history_retrievedAt ON source_collection_history(retrievedAt)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS tunnel_profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL, serverHost TEXT NOT NULL, serverPort INTEGER NOT NULL, dnsttServerPublicKey TEXT, slipstreamTlsServerName TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        level TEXT NOT NULL,
                        module TEXT NOT NULL,
                        message TEXT NOT NULL,
                        details TEXT,
                        stage TEXT,
                        identifier TEXT,
                        networkType TEXT NOT NULL,
                        appVersion TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_app_logs_timestamp ON app_logs(timestamp)")
            }
        }


        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_catalog (
                        providerId TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        providerType TEXT NOT NULL,
                        countryCode TEXT NOT NULL,
                        aliases TEXT NOT NULL,
                        enabled INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS resolver_sources (
                        sourceId TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceUrl TEXT,
                        providerId TEXT,
                        countryCode TEXT NOT NULL,
                        trustLevel TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        lastFetchedAt INTEGER,
                        lastError TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_resolver_sources_providerId ON resolver_sources(providerId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS resolver_evidence (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        address TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        sourceId TEXT NOT NULL,
                        providerId TEXT,
                        collectedAt INTEGER NOT NULL,
                        evidenceType TEXT NOT NULL,
                        ownershipStatus TEXT NOT NULL,
                        evidenceDetails TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_resolver_evidence_address_port_sourceId ON resolver_evidence(address, port, sourceId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_resolver_evidence_providerId ON resolver_evidence(providerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_resolver_evidence_ownershipStatus ON resolver_evidence(ownershipStatus)")

                val providers = listOf(
                    listOf("mci", "همراه اول", "MOBILE", "IR", "MCI;Hamrah-e Aval;Mobile Communications Company of Iran"),
                    listOf("irancell", "ایرانسل", "MOBILE", "IR", "MTN Irancell;Irancell"),
                    listOf("rightel", "رایتل", "MOBILE", "IR", "RighTel"),
                    listOf("shatel-mobile", "شاتل موبایل", "MOBILE", "IR", "Shatel Mobile"),
                    listOf("tci", "مخابرات ایران", "FIXED", "IR", "Telecommunication Company of Iran;TCI"),
                    listOf("pars-online", "پارس‌آنلاین", "FIXED", "IR", "Pars Online"),
                    listOf("shatel", "شاتل", "FIXED", "IR", "Shatel"),
                    listOf("asiatech", "آسیاتک", "FIXED", "IR", "Asiatech"),
                    listOf("mobinnet", "مبین‌نت", "FIXED_WIRELESS", "IR", "Mobinnet"),
                    listOf("hiweb", "های‌وب", "FIXED", "IR", "HiWeb"),
                    listOf("pishgaman", "پیشگامان", "FIXED", "IR", "Pishgaman"),
                    listOf("fanap", "فناپ تلکام", "FIXED", "IR", "Fanap Telecom"),
                    listOf("afranet", "افرانت", "FIXED", "IR", "Afranet"),
                    listOf("sabanet", "صبانت", "FIXED", "IR", "Sabanet"),
                    listOf("respina", "رسپینا", "FIXED", "IR", "Respina")
                )
                providers.forEach { row ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO provider_catalog (providerId, displayName, providerType, countryCode, aliases, enabled) VALUES (?, ?, ?, ?, ?, 1)",
                        row.toTypedArray()
                    )
                }

                val sources = listOf(
                    arrayOf<Any?>("iran-public-dns-directory", "Iran public DNS directory", "PUBLIC_DIRECTORY", "https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/by-country/ir.txt", "IR", "COMMUNITY"),
                    arrayOf<Any?>("public-dns-info", "Public DNS Info", "PUBLIC_DIRECTORY", "https://public-dns.info/nameservers.csv", "ZZ", "COMMUNITY"),
                    arrayOf<Any?>("trickest-resolvers", "Trickest Resolvers", "PUBLIC_DIRECTORY", "https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt", "ZZ", "COMMUNITY"),
                    arrayOf<Any?>("global-public-dns-directory", "Global public DNS directory", "PUBLIC_DIRECTORY", "https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/resolvers/global/all.txt", "ZZ", "COMMUNITY"),
                    arrayOf<Any?>("current-network", "DNS servers exposed by the active Android network", "NETWORK_DISCOVERY", null, "ZZ", "OBSERVED"),
                    arrayOf<Any?>("manual", "Manual DNS entry", "USER_INPUT", null, "ZZ", "USER_PROVIDED"),
                    arrayOf<Any?>("txt-import", "Imported TXT file", "USER_INPUT", null, "ZZ", "USER_PROVIDED")
                )
                sources.forEach { row ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO resolver_sources (sourceId, displayName, sourceType, sourceUrl, countryCode, trustLevel, enabled) VALUES (?, ?, ?, ?, ?, ?, 1)",
                        row
                    )
                }
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_discovery_state (
                        selectionKey TEXT NOT NULL PRIMARY KEY,
                        providerIds TEXT NOT NULL,
                        nextOffset INTEGER NOT NULL,
                        totalCandidates INTEGER NOT NULL,
                        complete INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE provider_discovery_state ADD COLUMN prefixSnapshotJson TEXT")
                database.execSQL("ALTER TABLE provider_discovery_state ADD COLUMN snapshotFetchedAt INTEGER")
                database.execSQL("ALTER TABLE provider_discovery_state ADD COLUMN iranAllocationRanges INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS e2e_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        resolverAddress TEXT NOT NULL,
                        resolverPort INTEGER NOT NULL,
                        testedAt INTEGER NOT NULL,
                        networkKey TEXT NOT NULL,
                        networkTransport TEXT NOT NULL,
                        networkLabel TEXT,
                        operatorName TEXT,
                        providerId TEXT,
                        profileId TEXT NOT NULL,
                        tunnelProtocol TEXT NOT NULL,
                        verificationMode TEXT NOT NULL,
                        status TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        elapsedMs INTEGER NOT NULL,
                        httpStatusCode INTEGER,
                        errorMessage TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_e2e_history_resolverAddress_resolverPort_testedAt ON e2e_history(resolverAddress, resolverPort, testedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_e2e_history_networkKey_testedAt ON e2e_history(networkKey, testedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_e2e_history_status_testedAt ON e2e_history(status, testedAt)")
            }
        }

        @Volatile private var instance: ResolverDatabase? = null

        fun getInstance(context: Context): ResolverDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ResolverDatabase::class.java,
                    "dns-sniperx.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13).build().also { instance = it }
            }
    }
}
