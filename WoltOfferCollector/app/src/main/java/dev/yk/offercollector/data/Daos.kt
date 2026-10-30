package dev.yk.offercollector.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationEventDao {
    @Insert
    suspend fun insert(e: NotificationEventEntity)

    @Query("SELECT * FROM notification_events ORDER BY observed_at_utc DESC")
    fun observeAll(): Flow<List<NotificationEventEntity>>

    @Query("SELECT * FROM notification_events ORDER BY observed_at_utc ASC")
    suspend fun all(): List<NotificationEventEntity>

    @Query("SELECT * FROM notification_events WHERE id = :id")
    suspend fun byId(id: String): NotificationEventEntity?

    @Query("SELECT * FROM notification_events WHERE notification_key_hash = :keyHash ORDER BY observed_at_utc DESC LIMIT 1")
    suspend fun latestForKey(keyHash: String): NotificationEventEntity?

    @Query("SELECT MAX(observed_at_utc) FROM notification_events WHERE source_package = :pkg AND event_type IN ('POSTED','UPDATED')")
    suspend fun latestPostedAt(pkg: String): Long?

    @Query("DELETE FROM notification_events")
    suspend fun deleteAll()
}

@Dao
interface ScreenEventDao {
    @Insert
    suspend fun insert(e: ScreenEventEntity)

    /** @return rows updated; 0 when the row no longer exists (e.g. after Delete All). */
    @Query("UPDATE screen_events SET coalesced_event_count = coalesced_event_count + :n WHERE id = :id")
    suspend fun addCoalesced(id: String, n: Int): Int

    @Query("SELECT * FROM screen_events ORDER BY observed_at_utc DESC")
    fun observeAll(): Flow<List<ScreenEventEntity>>

    @Query("SELECT * FROM screen_events ORDER BY observed_at_utc ASC")
    suspend fun all(): List<ScreenEventEntity>

    @Query("SELECT * FROM screen_events WHERE id = :id")
    suspend fun byId(id: String): ScreenEventEntity?

    @Query("DELETE FROM screen_events")
    suspend fun deleteAll()
}

@Dao
interface OfferCandidateDao {
    @Query("SELECT * FROM offer_candidates ORDER BY first_observed_at_utc ASC")
    suspend fun all(): List<OfferCandidateEntity>

    @Query("DELETE FROM offer_candidates")
    suspend fun deleteAll()
}

@Dao
interface StateMarkerEventDao {
    @Insert
    suspend fun insert(e: StateMarkerEventEntity)

    @Query("SELECT * FROM state_marker_events ORDER BY observed_at_utc DESC")
    fun observeAll(): Flow<List<StateMarkerEventEntity>>

    @Query("SELECT * FROM state_marker_events ORDER BY observed_at_utc ASC")
    suspend fun all(): List<StateMarkerEventEntity>

    @Query("SELECT * FROM state_marker_events WHERE observed_at_utc >= :from ORDER BY observed_at_utc ASC")
    suspend fun since(from: Long): List<StateMarkerEventEntity>

    @Query("SELECT * FROM state_marker_events WHERE source = :source ORDER BY observed_at_utc DESC LIMIT 1")
    suspend fun latestFor(source: String): StateMarkerEventEntity?

    @Query("DELETE FROM state_marker_events")
    suspend fun deleteAll()
}

@Dao
interface TestSessionDao {
    @Insert
    suspend fun insert(s: TestSessionEntity)

    @Query("UPDATE test_sessions SET ended_at_utc = :endedAt WHERE id = :id AND ended_at_utc IS NULL")
    suspend fun end(id: String, endedAt: Long): Int

    @Query("SELECT * FROM test_sessions WHERE ended_at_utc IS NULL ORDER BY started_at_utc DESC LIMIT 1")
    suspend fun open(): TestSessionEntity?

    @Query("SELECT * FROM test_sessions ORDER BY started_at_utc DESC")
    fun observeAll(): Flow<List<TestSessionEntity>>

    @Query("SELECT * FROM test_sessions ORDER BY started_at_utc ASC")
    suspend fun all(): List<TestSessionEntity>

    @Query("DELETE FROM test_sessions")
    suspend fun deleteAll()
}

@Dao
interface ShiftSessionDao {
    @Insert
    suspend fun insert(s: ShiftSessionEntity)

    @Query("UPDATE shift_sessions SET ended_at_utc = :endedAt, end_reason = :reason WHERE id = :id AND ended_at_utc IS NULL")
    suspend fun end(id: String, endedAt: Long, reason: String): Int

    @Query("UPDATE shift_sessions SET last_alive_utc = :at, charging_seen = charging_seen OR :charging WHERE id = :id AND ended_at_utc IS NULL")
    suspend fun heartbeat(id: String, at: Long, charging: Boolean = false)

    @Query("UPDATE shift_sessions SET battery_end_pct = :pct WHERE id = :id")
    suspend fun setBatteryEnd(id: String, pct: Int?)

    @Query("SELECT * FROM shift_sessions WHERE ended_at_utc IS NULL ORDER BY started_at_utc DESC LIMIT 1")
    suspend fun open(): ShiftSessionEntity?

    @Query("SELECT * FROM shift_sessions ORDER BY started_at_utc DESC")
    fun observeAll(): Flow<List<ShiftSessionEntity>>

    @Query("SELECT * FROM shift_sessions ORDER BY started_at_utc ASC")
    suspend fun all(): List<ShiftSessionEntity>

    @Query("DELETE FROM shift_sessions")
    suspend fun deleteAll()
}

@Dao
interface LocationSampleDao {
    @Insert
    suspend fun insert(s: LocationSampleEntity)

    @Query("SELECT * FROM location_samples ORDER BY observed_at_utc DESC")
    fun observeAll(): Flow<List<LocationSampleEntity>>

    @Query("SELECT * FROM location_samples ORDER BY observed_at_utc ASC")
    suspend fun all(): List<LocationSampleEntity>

    /** The courier's (coarse, ~500 m cell) position closest to [at], within [from]..[to]. Local only. */
    @Query("SELECT * FROM location_samples WHERE status = 'OK' AND cell_center_lat IS NOT NULL AND observed_at_utc BETWEEN :from AND :to ORDER BY ABS(observed_at_utc - :at) LIMIT 1")
    suspend fun nearest(at: Long, from: Long, to: Long): LocationSampleEntity?

    @Query("SELECT event_key FROM location_samples WHERE shift_id = :shiftId AND event_key IS NOT NULL")
    suspend fun eventKeys(shiftId: String): List<String>

    @Query("UPDATE location_samples SET debug_lat = NULL, debug_lon = NULL WHERE debug_lat IS NOT NULL AND observed_at_utc < :before")
    suspend fun purgeDebugBefore(before: Long): Int

    @Query("UPDATE location_samples SET debug_lat = NULL, debug_lon = NULL WHERE debug_lat IS NOT NULL")
    suspend fun purgeAllDebug(): Int

    @Query("SELECT COUNT(*) FROM location_samples WHERE debug_lat IS NOT NULL")
    suspend fun debugCount(): Int

    @Query("DELETE FROM location_samples")
    suspend fun deleteAll()
}

@Dao
interface PickupPlaceDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(p: PickupPlaceEntity): Long

    @Query("SELECT * FROM pickup_places WHERE geo_status = 'PENDING' OR (geo_status = 'ERROR' AND (geo_at_utc IS NULL OR geo_at_utc < :retryBefore)) OR (geo_status IN ('NOT_FOUND', 'OK') AND (geo_note IS NULL OR geo_note != :queryVersion)) ORDER BY first_seen_utc LIMIT 20")
    suspend fun toGeocode(retryBefore: Long, queryVersion: String): List<PickupPlaceEntity>

    @Query("UPDATE pickup_places SET geo_status = :status, cell_id = :cellId, center_lat = :lat, center_lon = :lon, geo_at_utc = :at, geo_note = :note WHERE `key` = :key")
    suspend fun setResult(key: String, status: String, cellId: String?, lat: Double?, lon: Double?, at: Long, note: String?)

    @Query("SELECT * FROM pickup_places ORDER BY first_seen_utc ASC")
    fun observeAll(): Flow<List<PickupPlaceEntity>>

    @Query("SELECT * FROM pickup_places ORDER BY first_seen_utc ASC")
    suspend fun all(): List<PickupPlaceEntity>

    @Query("DELETE FROM pickup_places")
    suspend fun deleteAll()
}

/** Insert-only on purpose: predictions are immutable. There is no update method. */
@Dao
interface ShadowPredictionDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insert(p: ShadowPredictionEntity)

    @Query("SELECT * FROM shadow_predictions ORDER BY offer_at_utc DESC")
    fun observeAll(): Flow<List<ShadowPredictionEntity>>

    @Query("SELECT * FROM shadow_predictions ORDER BY offer_at_utc ASC")
    suspend fun all(): List<ShadowPredictionEntity>

    @Query("DELETE FROM shadow_predictions")
    suspend fun deleteAll()
}

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insert(p: TrackPointEntity)

    @Query("SELECT * FROM track_points WHERE shift_id = :shiftId ORDER BY observed_at_utc ASC")
    suspend fun forShift(shiftId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points ORDER BY observed_at_utc ASC")
    suspend fun all(): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_points")
    fun observeCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query("DELETE FROM track_points")
    suspend fun deleteAll()
}
