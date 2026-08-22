package ch.rhosys.sbb.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Reconstructed from the entity diffs at each version bump (exportSchema was
// never turned on, so there are no schema JSON snapshots to generate these
// from). Without these, Room's fallbackToDestructiveMigration() drops every
// table — saved places included — on any version change.

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_history ADD COLUMN departureEpoch INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_history ADD COLUMN arrivalEpoch INTEGER")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // isHome dropped, label + photoUri added — SQLite can't drop a column
        // in place, so rebuild the table.
        db.execSQL(
            """
            CREATE TABLE places_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                label TEXT,
                photoUri TEXT,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO places_new (id, name, lat, lng, sortOrder, createdAt)
            SELECT id, name, lat, lng, sortOrder, createdAt FROM places
            """.trimIndent()
        )
        db.execSQL("DROP TABLE places")
        db.execSQL("ALTER TABLE places_new RENAME TO places")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_history ADD COLUMN wasCancelled INTEGER NOT NULL DEFAULT 0")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
