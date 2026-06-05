package ch.rhosys.sbb.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val FAVOURITE_STATIONS        = stringSetPreferencesKey("favourite_stations")
        val ACTIVE_JOURNEY            = stringPreferencesKey("active_journey")
    }

    val walkingPaceKmh: Flow<Float>   = dataStore.data.map { it[WALKING_PACE_KMH] ?: 6f }
    val runningPaceKmh: Flow<Float>   = dataStore.data.map { it[RUNNING_PACE_KMH] ?: 10f }
    val switchThresholdMinutes: Flow<Int> = dataStore.data.map { it[SWITCH_THRESHOLD_MINUTES] ?: 1 }
    val calendarSyncEnabled: Flow<Boolean> = dataStore.data.map { it[CALENDAR_SYNC_ENABLED] ?: false }
    val calendarSyncIntervalHours: Flow<Int> = dataStore.data.map { it[CALENDAR_SYNC_INTERVAL_HOURS] ?: 4 }
    val hasCompletedOnboarding: Flow<Boolean> = dataStore.data.map { it[HAS_COMPLETED_ONBOARDING] ?: false }
    val favouriteStations: Flow<Set<String>> = dataStore.data.map { it[FAVOURITE_STATIONS] ?: emptySet() }
    val activeJourney: Flow<PersistedJourney?> = dataStore.data.map { prefs ->
        prefs[ACTIVE_JOURNEY]?.let { runCatching { Json.decodeFromString<PersistedJourney>(it) }.getOrNull() }
    }

    suspend fun setWalkingPace(kmh: Float)       = dataStore.edit { it[WALKING_PACE_KMH] = kmh }
    suspend fun setRunningPace(kmh: Float)        = dataStore.edit { it[RUNNING_PACE_KMH] = kmh }
    suspend fun setSwitchThreshold(minutes: Int)  = dataStore.edit { it[SWITCH_THRESHOLD_MINUTES] = minutes }
    suspend fun setCalendarSyncEnabled(enabled: Boolean) = dataStore.edit { it[CALENDAR_SYNC_ENABLED] = enabled }
    suspend fun setCalendarSyncIntervalHours(hours: Int) = dataStore.edit { it[CALENDAR_SYNC_INTERVAL_HOURS] = hours }
    suspend fun setHasCompletedOnboarding(done: Boolean) = dataStore.edit { it[HAS_COMPLETED_ONBOARDING] = done }
    suspend fun addFavouriteStation(name: String) = dataStore.edit { prefs ->
        prefs[FAVOURITE_STATIONS] = (prefs[FAVOURITE_STATIONS] ?: emptySet()) + name
    }
    suspend fun removeFavouriteStation(name: String) = dataStore.edit { prefs ->
        prefs[FAVOURITE_STATIONS] = (prefs[FAVOURITE_STATIONS] ?: emptySet()) - name
    }

    suspend fun persistActiveJourney(journey: PersistedJourney) = dataStore.edit { prefs ->
        prefs[ACTIVE_JOURNEY] = Json.encodeToString(journey)
    }

    suspend fun clearActiveJourney() = dataStore.edit { prefs ->
        prefs -= ACTIVE_JOURNEY
    }
}
