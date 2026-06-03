package ch.rhosys.sbb.ui.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first

// Single DataStore instance for widget data — defined here, imported by JourneyWidgetSyncer
internal val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_prefs")

val WIDGET_FROM = stringPreferencesKey("widget_from")
val WIDGET_LINE = stringPreferencesKey("widget_line")
val WIDGET_TIME = stringPreferencesKey("widget_time")

class DepartureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.widgetDataStore.data.first()
        val from = prefs[WIDGET_FROM]
        val line = prefs[WIDGET_LINE]
        val time = prefs[WIDGET_TIME]

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(androidx.glance.unit.Dp(12f)),
                ) {
                    if (from != null && time != null) {
                        Text(
                            text = if (line?.isNotBlank() == true) "$line  $time" else time,
                            style = TextStyle(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = "from $from",
                            style = TextStyle(),
                        )
                    } else {
                        Text(
                            text = "No active journey",
                            style = TextStyle(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = "Open app to lock in a trip",
                            style = TextStyle(),
                        )
                    }
                }
            }
        }
    }
}

class DepartureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DepartureWidget()
}
