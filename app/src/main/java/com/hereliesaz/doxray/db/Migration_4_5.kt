package com.hereliesaz.doxray.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `anchor_images` table for Phase 6a. One row per identity.
 * Cascade delete keeps the table in sync with the parent.
 */
object Migration_4_5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS anchor_images (
                faceId TEXT NOT NULL PRIMARY KEY,
                imageBytes BLOB NOT NULL,
                qualityScore REAL NOT NULL,
                capturedAt INTEGER NOT NULL,
                FOREIGN KEY(faceId) REFERENCES identity_records(faceId) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_anchor_images_faceId ON anchor_images(faceId)")
    }
}
