package dev.yk.offercollector

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.yk.offercollector.data.AppDatabase
import dev.yk.offercollector.data.NotificationEventEntity
import dev.yk.offercollector.data.ScreenEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()

    @Test fun insertQueryCoalesceAndDeleteAll() = runTest {
        val db = db()
        val n = NotificationEventEntity("n1", 1000, "com.example", "h", "POSTED", "[TEXT]", null, "{}", null, "s", 1000)
        db.notificationEvents().insert(n)
        assertEquals("POSTED", db.notificationEvents().latestForKey("h")!!.eventType)
        assertEquals(1000L, db.notificationEvents().latestPostedAt("com.example"))
        val s = ScreenEventEntity("s1", 2000, "com.example", "WINDOW_STATE_CHANGED", "UNKNOWN", null, null, null, "NO_ACCESSIBLE_TEXT", "ROOT_NULL", "p0", null, null, 10, 1, null)
        db.screenEvents().insert(s)
        db.screenEvents().addCoalesced("s1", 4)
        assertEquals(5, db.screenEvents().byId("s1")!!.coalescedEventCount)
        assertNull(db.screenEvents().byId("s1")!!.redactedCandidateFieldsJson)
        db.notificationEvents().deleteAll()
        db.screenEvents().deleteAll()
        db.offerCandidates().deleteAll()
        db.stateMarkerEvents().insert(dev.yk.offercollector.data.StateMarkerEventEntity("m", 1, "SYSTEM", "UNKNOWN", "UNKNOWN", "system.X", "{}", "p", null))
        db.testSessions().insert(dev.yk.offercollector.data.TestSessionEntity("t", "A", 1, null))
        assertEquals("t", db.testSessions().open()!!.id)
        assertEquals(1, db.testSessions().end("t", 2))
        assertNull(db.testSessions().open())
        db.stateMarkerEvents().deleteAll()
        db.testSessions().deleteAll()
        assertEquals(0, db.stateMarkerEvents().all().size)
        assertEquals(0, db.notificationEvents().all().size)
        assertEquals(0, db.screenEvents().all().size)
        db.close()
    }

    @Test fun migration1to2KeepsDataAndAddsStateTables() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL(
                "INSERT INTO notification_events (id, observed_at_utc, source_package, notification_key_hash, event_type, title_redacted, " +
                    "text_redacted, fields_present_json, removal_reason, content_signature_hash, created_at_utc) " +
                    "VALUES ('n1', 1000, 'com.example', 'h', 'POSTED', '[TEXT]', NULL, '{}', NULL, 's', 1000)",
            )
        }
        helper.runMigrationsAndValidate("migration-test", 2, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM notification_events").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM state_marker_events").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM test_sessions").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun migration2to3KeepsDataAndAddsLocationTables() {
        helper.createDatabase("migration-test-3", 2).use { db ->
            db.execSQL("INSERT INTO test_sessions (id, label, started_at_utc, ended_at_utc) VALUES ('t1', 'A', 1000, NULL)")
        }
        helper.runMigrationsAndValidate("migration-test-3", 3, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM test_sessions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM shift_sessions").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM location_samples").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun shiftAndSampleDaos() = kotlinx.coroutines.runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).build()
        db.shiftSessions().insert(dev.yk.offercollector.data.ShiftSessionEntity("s", 1, null, null, true, 1))
        db.shiftSessions().heartbeat("s", 5)
        assertEquals(5L, db.shiftSessions().open()!!.lastAliveUtc)
        db.locationSamples().insert(dev.yk.offercollector.data.LocationSampleEntity("l1", "s", 2, "WAIT_PERIODIC", "UNKNOWN", "UNKNOWN", "NO_FIX", null, null, null, null, null, null))
        db.locationSamples().insert(dev.yk.offercollector.data.LocationSampleEntity("l2", "s", 3, "OFFER_PRESENTED", "OFFER_PRESENTED", "OK", null, "g500:1:1", 1.0, 1.0, 9, 1, "fused"))
        assertEquals(emptyList<String>(), db.locationSamples().eventKeys("s"))
        assertEquals(1, db.shiftSessions().end("s", 9, "USER"))
        assertEquals(0, db.shiftSessions().end("s", 10, "USER")) // already ended
        assertNull(db.shiftSessions().open())
        db.close()
    }

    @Test fun migration3to4KeepsSamplesAndMarksSources() {
        helper.createDatabase("migration-test-4", 3).use { db ->
            db.execSQL("INSERT INTO shift_sessions (id, started_at_utc, ended_at_utc, end_reason, location_consent, last_alive_utc) VALUES ('s', 1, 2, 'USER', 1, 2)")
            db.execSQL("INSERT INTO location_samples (id, shift_id, observed_at_utc, `trigger`, state_candidate, status, unknown_reason, cell_id, cell_center_lat, cell_center_lon, accuracy_m, fix_age_s, provider) VALUES ('a', 's', 1, 'SHIFT_START', 'UNKNOWN', 'OK', NULL, 'g500:1:1', 1.0, 1.0, 5, 0, 'fused')")
            db.execSQL("INSERT INTO location_samples (id, shift_id, observed_at_utc, `trigger`, state_candidate, status, unknown_reason, cell_id, cell_center_lat, cell_center_lon, accuracy_m, fix_age_s, provider) VALUES ('b', 's', 2, 'WAIT_PERIODIC', 'UNKNOWN', 'UNKNOWN', 'NO_FIX', NULL, NULL, NULL, NULL, NULL, NULL)")
        }
        helper.runMigrationsAndValidate("migration-test-4", 4, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT id, source, state_evidence, debug_lat FROM location_samples ORDER BY id").use { c ->
                c.moveToFirst(); assertEquals("SESSION", c.getString(1)); assertEquals("UNKNOWN", c.getString(2)); assertEquals(true, c.isNull(3))
                c.moveToNext(); assertEquals("PERIODIC", c.getString(1))
            }
            db.query("SELECT charging_seen, base_interval_s FROM shift_sessions").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)); assertEquals(true, c.isNull(1)) }
        }
    }

    @Test fun migration4to5AddsPickupPlacesAndKeepsData() {
        helper.createDatabase("migration-test-5", 4).use { db ->
            db.execSQL("INSERT INTO shift_sessions (id, started_at_utc, ended_at_utc, end_reason, location_consent, last_alive_utc, charging_seen) VALUES ('s', 1, 2, 'USER', 1, 2, 0)")
        }
        helper.runMigrationsAndValidate("migration-test-5", 5, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM shift_sessions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM pickup_places").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun migration5to6AddsShadowPredictionsAndPlannedEnd() {
        helper.createDatabase("migration-test-6", 5).use { db ->
            db.execSQL("INSERT INTO shift_sessions (id, started_at_utc, ended_at_utc, end_reason, location_consent, last_alive_utc, charging_seen) VALUES ('s', 1, 2, 'USER', 1, 2, 0)")
        }
        helper.runMigrationsAndValidate("migration-test-6", 6, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT planned_end_utc FROM shift_sessions").use { c -> c.moveToFirst(); assertEquals(true, c.isNull(0)) }
            db.query("SELECT COUNT(*) FROM shadow_predictions").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun migration6to7AddsTrackPointsAndKeepsData() {
        helper.createDatabase("migration-test-7", 6).use { db ->
            db.execSQL("INSERT INTO shift_sessions (id, started_at_utc, ended_at_utc, end_reason, location_consent, last_alive_utc, charging_seen) VALUES ('s', 1, 2, 'USER', 1, 2, 0)")
        }
        helper.runMigrationsAndValidate("migration-test-7", 7, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM shift_sessions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL("INSERT INTO track_points (shift_id, observed_at_utc, lat, lon, accuracy_m, speed_mps) VALUES ('s', 1, 31.97, 34.79, 4.0, 0.0)")
            db.query("SELECT COUNT(*) FROM track_points").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }
}
