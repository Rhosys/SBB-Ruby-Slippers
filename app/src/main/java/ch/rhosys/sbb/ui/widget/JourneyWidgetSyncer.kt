package ch.rhosys.sbb.ui.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import ch.rhosys.sbb.ui.journey.JourneyStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JourneyWidgetSyncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journeyStateHolder: JourneyStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cachedScorerData: ScorerWidgetData? = null

    private data class ScorerWidgetData(
        val to: String,
        val departTime: String,
        val arriveTime: String,
        val lines: String,
    )

    fun start() {
        scope.launch {
            journeyStateHolder.activeJourney.collect { journey ->
                context.widgetDataStore.edit { prefs ->
                    if (journey != null) {
                        prefs[WIDGET_MODE]        = "active"
                        prefs[WIDGET_FROM]        = journey.connection.departure.stationName
                        prefs[WIDGET_TO]          = journey.connection.arrival.stationName
                        prefs[WIDGET_DEPART_TIME] = journey.connection.departure.displayTime()
                        prefs[WIDGET_ARRIVE_TIME] = journey.connection.arrival.displayTime()
                        prefs -= WIDGET_LINES
                    } else {
                        val scorer = cachedScorerData
                        if (scorer != null) writeScorerPrefs(prefs, scorer)
                        else prefs -= WIDGET_MODE
                    }
                }
                DepartureWidget().updateAll(context)
            }
        }
    }

    // Called by HomeViewModel when the scorer produces a best-candidate connection.
    fun onScorerResult(to: String, departTime: String, arriveTime: String, lines: String) {
        val data = ScorerWidgetData(to, departTime, arriveTime, lines)
        cachedScorerData = data
        if (journeyStateHolder.activeJourney.value == null) {
            scope.launch {
                context.widgetDataStore.edit { prefs -> writeScorerPrefs(prefs, data) }
                DepartureWidget().updateAll(context)
            }
        }
    }

    // Called by HomeViewModel when the scorer produces no result (e.g. no candidate routes found).
    fun clearScorerResult() {
        cachedScorerData = null
        if (journeyStateHolder.activeJourney.value == null) {
            scope.launch {
                context.widgetDataStore.edit { prefs -> prefs -= WIDGET_MODE }
                DepartureWidget().updateAll(context)
            }
        }
    }

    private fun writeScorerPrefs(prefs: MutablePreferences, data: ScorerWidgetData) {
        prefs[WIDGET_MODE]        = "scorer"
        prefs[WIDGET_TO]          = data.to
        prefs[WIDGET_DEPART_TIME] = data.departTime
        prefs[WIDGET_ARRIVE_TIME] = data.arriveTime
        prefs[WIDGET_LINES]       = data.lines
        prefs -= WIDGET_FROM
    }
}
