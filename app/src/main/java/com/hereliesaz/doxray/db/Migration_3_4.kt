package com.hereliesaz.doxray.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `visibleText` column to `identity_records` for Phase 5 OCR.
 * Existing rows get NULL — no backfill.
 */
object Migration_3_4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE identity_records ADD COLUMN visibleText TEXT")
    }
}
