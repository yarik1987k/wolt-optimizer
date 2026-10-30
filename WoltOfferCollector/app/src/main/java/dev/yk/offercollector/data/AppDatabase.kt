package dev.yk.offercollector.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationEventEntity::class, ScreenEventEntity::class, OfferCandidateEntity::class,
        StateMarkerEventEntity::class, TestSessionEntity::class, ShiftSessionEntity::class, LocationSampleEntity::class, PickupPlaceEntity::class, ShadowPredictionEntity::class,
        TrackPointEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationEvents(): NotificationEventDao
    abstract fun screenEvents(): ScreenEventDao
    abstract fun offerCandidates(): OfferCandidateDao
    abstract fun stateMarkerEvents(): StateMarkerEventDao
    abstract fun testSessions(): TestSessionDao
    abstract fun shiftSessions(): ShiftSessionDao
    abstract fun locationSamples(): LocationSampleDao
    abstract fun pickupPlaces(): PickupPlaceDao
    abstract fun shadowPredictions(): ShadowPredictionDao
    abstract fun trackPoints(): TrackPointDao

    companion object {
        /** v1 → v2: adds the courier-state feasibility tables. Additive only; existing data is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `state_marker_events` (`id` TEXT NOT NULL, `observed_at_utc` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `candidate_state` TEXT NOT NULL, `evidence` TEXT NOT NULL, `rule` TEXT NOT NULL, " +
                        "`markers_json` TEXT NOT NULL, `parse_version` TEXT NOT NULL, `test_session_id` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_state_marker_events_observed_at_utc` ON `state_marker_events` (`observed_at_utc`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_state_marker_events_test_session_id` ON `state_marker_events` (`test_session_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `test_sessions` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`started_at_utc` INTEGER NOT NULL, `ended_at_utc` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_test_sessions_started_at_utc` ON `test_sessions` (`started_at_utc`)")
            }
        }

        /** v2 → v3: shifts + coarse location samples (Hotspot MVP). Additive only. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shift_sessions` (`id` TEXT NOT NULL, `started_at_utc` INTEGER NOT NULL, " +
                        "`ended_at_utc` INTEGER, `end_reason` TEXT, `location_consent` INTEGER NOT NULL, `last_alive_utc` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shift_sessions_started_at_utc` ON `shift_sessions` (`started_at_utc`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_samples` (`id` TEXT NOT NULL, `shift_id` TEXT NOT NULL, " +
                        "`observed_at_utc` INTEGER NOT NULL, `trigger` TEXT NOT NULL, `state_candidate` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `unknown_reason` TEXT, `cell_id` TEXT, `cell_center_lat` REAL, `cell_center_lon` REAL, " +
                        "`accuracy_m` INTEGER, `fix_age_s` INTEGER, `provider` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_samples_observed_at_utc` ON `location_samples` (`observed_at_utc`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_samples_shift_id` ON `location_samples` (`shift_id`)")
            }
        }

        /** v3 → v4: Phase 3 location fields. ALTER TABLE ADD COLUMN only; existing rows keep their values. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `shift_sessions` ADD COLUMN `base_interval_s` INTEGER",
                    "ALTER TABLE `shift_sessions` ADD COLUMN `stationary_interval_s` INTEGER",
                    "ALTER TABLE `shift_sessions` ADD COLUMN `battery_start_pct` INTEGER",
                    "ALTER TABLE `shift_sessions` ADD COLUMN `battery_end_pct` INTEGER",
                    "ALTER TABLE `shift_sessions` ADD COLUMN `charging_seen` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `location_samples` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'EVENT'",
                    "ALTER TABLE `location_samples` ADD COLUMN `state_evidence` TEXT NOT NULL DEFAULT 'UNKNOWN'",
                    "ALTER TABLE `location_samples` ADD COLUMN `speed_mps` REAL",
                    "ALTER TABLE `location_samples` ADD COLUMN `location_age_ms` INTEGER",
                    "ALTER TABLE `location_samples` ADD COLUMN `event_at_utc` INTEGER",
                    "ALTER TABLE `location_samples` ADD COLUMN `event_key` TEXT",
                    "ALTER TABLE `location_samples` ADD COLUMN `battery_pct` INTEGER",
                    "ALTER TABLE `location_samples` ADD COLUMN `debug_lat` REAL",
                    "ALTER TABLE `location_samples` ADD COLUMN `debug_lon` REAL",
                ).forEach(db::execSQL)
                // 0.3.0 rows: SHIFT_START/SHIFT_END were session samples, WAIT_PERIODIC periodic.
                db.execSQL("UPDATE `location_samples` SET `source` = 'SESSION' WHERE `trigger` IN ('SHIFT_START', 'SHIFT_END')")
                db.execSQL("UPDATE `location_samples` SET `source` = 'PERIODIC' WHERE `trigger` = 'WAIT_PERIODIC'")
            }
        }

        /** v4 → v5: pickup businesses (name + address) and their cached geocode. Additive only. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pickup_places` (`key` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, " +
                        "`first_seen_utc` INTEGER NOT NULL, `geo_status` TEXT NOT NULL, `cell_id` TEXT, `center_lat` REAL, `center_lon` REAL, " +
                        "`geo_at_utc` INTEGER, `geo_note` TEXT, PRIMARY KEY(`key`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pickup_places_geo_status` ON `pickup_places` (`geo_status`)")
            }
        }

        /** v5 → v6: immutable shadow predictions + planned shift end. Additive only. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shadow_predictions` (`id` TEXT NOT NULL, `created_at_utc` INTEGER NOT NULL, " +
                        "`offer_at_utc` INTEGER NOT NULL, `rule_version` TEXT NOT NULL, `margin_pct` INTEGER NOT NULL, `amount_agorot` INTEGER NOT NULL, " +
                        "`distance_km` REAL, `courier_cell` TEXT, `pickup_cell` TEXT, `pickup_basis` TEXT NOT NULL, `shift_id` TEXT, " +
                        "`planned_shift_end_utc` INTEGER, `remaining_shift_min` REAL, `duration_median_min` REAL, `duration_low_min` REAL, " +
                        "`duration_high_min` REAL, `duration_confidence` TEXT, `duration_distance_matched` INTEGER, `vehicle_cost_per_km_agorot` INTEGER, " +
                        "`vehicle_cost_agorot` INTEGER, `net_per_hour_median_agorot` INTEGER, `net_per_hour_slow_agorot` INTEGER, " +
                        "`net_per_hour_fast_agorot` INTEGER, `historical_shift_hourly_rate_agorot` INTEGER, `baseline_time_value_agorot` INTEGER, " +
                        "`decision` TEXT NOT NULL, `reasons_json` TEXT NOT NULL, `inputs_json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shadow_predictions_offer_at_utc` ON `shadow_predictions` (`offer_at_utc`)")
                db.execSQL("ALTER TABLE `shift_sessions` ADD COLUMN `planned_end_utc` INTEGER")
            }
        }

        /** v6 → v7: precise shift track (~5 m) for the route map. Additive only. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `shift_id` TEXT NOT NULL, " +
                        "`observed_at_utc` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracy_m` REAL, `speed_mps` REAL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_shift_id` ON `track_points` (`shift_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_observed_at_utc` ON `track_points` (`observed_at_utc`)")
            }
        }

        /** Explicit migrations only (schemas/ holds the exported JSON). Never destructive. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "offer_collector.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
