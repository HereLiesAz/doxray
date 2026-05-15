package com.hereliesaz.doxray.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `encounters` and `audit_events` tables (Phase 1 dossier surface).
 * Existing `identity_records` rows are untouched.
 */
object Migration_2_3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS encounters (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                faceId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                locationAccuracyMeters REAL,
                FOREIGN KEY(faceId) REFERENCES identity_records(faceId) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_encounters_faceId ON encounters(faceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_encounters_timestamp ON encounters(timestamp)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                summary TEXT NOT NULL,
                detailsJson TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_timestamp ON audit_events(timestamp)")
    }
}
