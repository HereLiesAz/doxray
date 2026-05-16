package com.hereliesaz.doxray.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [IdentityRecord::class, Encounter::class, AuditEvent::class, AnchorImage::class], version = 5, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun identityDao(): IdentityDao
    abstract fun encounterDao(): EncounterDao
    abstract fun auditDao(): AuditDao
    abstract fun anchorImageDao(): AnchorImageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "doxray_database"
                )
                .addMigrations(Migration_2_3, Migration_3_4, Migration_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
