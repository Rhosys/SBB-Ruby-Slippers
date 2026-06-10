package ch.rhosys.sbb.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first

// Single DataStore instance for widget data — written by JourneyWidgetSyncer
internal val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_prefs")

val WIDGET_MODE        = stringPreferencesKey("widget_mode")         // "active" | "scorer"
val WIDGET_FROM        = stringPreferencesKey("widget_from")         // departure station (active mode)
val WIDGET_TO          = stringPreferencesKey("widget_to")           // arrival station / destination
val WIDGET_DEPART_TIME = stringPreferencesKey("widget_depart_time")  // e.g. "08:42"
val WIDGET_ARRIVE_TIME = stringPreferencesKey("widget_arrive_time")  // e.g. "09:15"
val WIDGET_LINES       = stringPreferencesKey("widget_lines")        // e.g. "IC 1" or "IC 1 · S5"

class DepartureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.widgetDataStore.data.first()
        val mode       = prefs[WIDGET_MODE]
        val from       = prefs[WIDGET_FROM] ?: ""
        val to         = prefs[WIDGET_TO] ?: ""
        val departTime = prefs[WIDGET_DEPART_TIME] ?: ""
        val arriveTime = prefs[WIDGET_ARRIVE_TIME] ?: ""
        val lines      = prefs[WIDGET_LINES] ?: ""

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(12.dp),
                ) {
                    when (mode) {
                        "active" -> ActiveJourneyContent(from, to, departTime, arriveTime)
                        "scorer" -> ScorerContent(to, departTime, arriveTime, lines)
                        else     -> IdleContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveJourneyContent(from: String, to: String, departTime: String, arriveTime: String) {
    Text(
        "Active journey",
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp),
    )
    Spacer(GlanceModifier.height(4.dp))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Column(modifier = defaultWeight()) {
            Text(from, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp))
            Text(departTime, style = TextStyle(fontSize = 13.sp))
        }
        Column(
            modifier = defaultWeight(),
            horizontalAlignment = Alignment.Horizontal.End,
        ) {
            Text(to, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp))
            Text(arriveTime, style = TextStyle(fontSize = 13.sp))
        }
    }
}

@Composable
private fun ScorerContent(to: String, departTime: String, arriveTime: String, lines: String) {
    Text(
        "→ $to",
        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp),
    )
    Spacer(GlanceModifier.height(6.dp))
    Text(
        buildString {
            append("$departTime → $arriveTime")
            if (lines.isNotBlank()) append("   $lines")
        },
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

@Composable
private fun IdleContent() {
    Text(
        "No active journey",
        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp),
    )
    Spacer(GlanceModifier.height(4.dp))
    Text(
        "Open app to start",
        style = TextStyle(fontSize = 13.sp),
    )
}

class DepartureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DepartureWidget()
}
