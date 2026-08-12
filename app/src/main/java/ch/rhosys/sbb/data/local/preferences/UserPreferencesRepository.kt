package ch.rhosys.sbb.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PersistedJourney(
    val fromName: String,
    val toName: String,
    val arrivalEpoch: Long,
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        val WALKING_PACE_KMH          = floatPreferencesKey("walking_pace_kmh")
        val RUNNING_PACE_KMH          = floatPreferencesKey("running_pace_kmh")
        val SWITCH_THRESHOLD_MINUTES  = intPreferencesKey("switch_threshold_minutes")
        val CALENDAR_SYNC_ENABLED     = booleanPreferencesKey("calendar_sync_enabled")
        val CALENDAR_SYNC_INTERVAL_HOURS = intPreferencesKey("calendar_sync_interval_hours")
        val HAS_COMPLETED_ONBOARDING  = booleanPreferencesKey("has_completed_onboarding")
        val ACTIVE_JOURNEY            = stringPreferencesKey("active_journey")
        val RT_TOKEN                  = stringPreferencesKey("rt_token")
        val RT_LAST_SUCCESS_EPOCH     = longPreferencesKey("rt_last_success_epoch")
        val RT_LAST_ERROR_EPOCH       = longPreferencesKey("rt_last_error_epoch")
        val RT_LAST_ERROR_MESSAGE     = stringPreferencesKey("rt_last_error_message")
    }

    val walkingPaceKmh: Flow<Float>   = dataStore.data.map { it[WALKING_PACE_KMH] ?: 6f }
    val runningPaceKmh: Flow<Float>   = dataStore.data.map { it[RUNNING_PACE_KMH] ?: 10f }
    val switchThresholdMinutes: Flow<Int> = dataStore.data.map { it[SWITCH_THRESHOLD_MINUTES] ?: 1 }
    val calendarSyncEnabled: Flow<Boolean> = dataStore.data.map { it[CALENDAR_SYNC_ENABLED] ?: false }
    val calendarSyncIntervalHours: Flow<Int> = dataStore.data.map { it[CALENDAR_SYNC_INTERVAL_HOURS] ?: 4 }
    val hasCompletedOnboarding: Flow<Boolean> = dataStore.data.map { it[HAS_COMPLETED_ONBOARDING] ?: false }
    val rtToken: Flow<String>         = dataStore.data.map { it[RT_TOKEN] ?: "" }
    val rtLastSuccessEpoch: Flow<Long?> = dataStore.data.map { it[RT_LAST_SUCCESS_EPOCH] }
    val rtLastErrorEpoch: Flow<Long?> = dataStore.data.map { it[RT_LAST_ERROR_EPOCH] }
    val rtLastErrorMessage: Flow<String?> = dataStore.data.map { it[RT_LAST_ERROR_MESSAGE] }
    val activeJourney: Flow<PersistedJourney?> = dataStore.data.map { prefs ->
        prefs[ACTIVE_JOURNEY]?.let { runCatching { Json.decodeFromString<PersistedJourney>(it) }.getOrNull() }
    }

    suspend fun setWalkingPace(kmh: Float)       = dataStore.edit { it[WALKING_PACE_KMH] = kmh }
    suspend fun setRunningPace(kmh: Float)        = dataStore.edit { it[RUNNING_PACE_KMH] = kmh }
    suspend fun setSwitchThreshold(minutes: Int)  = dataStore.edit { it[SWITCH_THRESHOLD_MINUTES] = minutes }
    suspend fun setCalendarSyncEnabled(enabled: Boolean) = dataStore.edit { it[CALENDAR_SYNC_ENABLED] = enabled }
    suspend fun setCalendarSyncIntervalHours(hours: Int) = dataStore.edit { it[CALENDAR_SYNC_INTERVAL_HOURS] = hours }
    suspend fun setHasCompletedOnboarding(done: Boolean) = dataStore.edit { it[HAS_COMPLETED_ONBOARDING] = done }
    suspend fun setRtToken(token: String) = dataStore.edit { it[RT_TOKEN] = token }
    suspend fun recordRtSuccess(epochSecond: Long) = dataStore.edit { it[RT_LAST_SUCCESS_EPOCH] = epochSecond }
    suspend fun recordRtError(epochSecond: Long, message: String) = dataStore.edit {
        it[RT_LAST_ERROR_EPOCH] = epochSecond
        it[RT_LAST_ERROR_MESSAGE] = message
    }

    suspend fun persistActiveJourney(journey: PersistedJourney) = dataStore.edit { prefs ->
        prefs[ACTIVE_JOURNEY] = Json.encodeToString(journey)
    }

    suspend fun clearActiveJourney() = dataStore.edit { prefs ->
        prefs -= ACTIVE_JOURNEY
    }
}
