package ch.rhosys.sbb.ui.widget

import android.content.Context
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

    fun start() {
        scope.launch {
            journeyStateHolder.activeJourney.collect { journey ->
                context.widgetDataStore.edit { prefs ->
                    if (journey != null) {
                        prefs[WIDGET_FROM] = journey.from.displayName()
                        prefs[WIDGET_LINE] = journey.connection.lineNames.firstOrNull() ?: ""
                        prefs[WIDGET_TIME] = journey.connection.departure.displayTime()
                    } else {
                        prefs -= WIDGET_FROM
                        prefs -= WIDGET_LINE
                        prefs -= WIDGET_TIME
                    }
                }
                DepartureWidget().updateAll(context)
            }
        }
    }
}
