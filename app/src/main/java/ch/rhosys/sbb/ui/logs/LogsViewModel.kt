package ch.rhosys.sbb.ui.logs

import androidx.lifecycle.ViewModel
import ch.rhosys.sbb.data.log.AppLog
import ch.rhosys.sbb.data.log.AppLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val appLog: AppLog,
) : ViewModel() {

    val entries: StateFlow<List<AppLogEntry>> = appLog.entries

    fun clear() = appLog.clear()

    // Everything shown, as plain text for the clipboard (e.g. to paste into a bug report).
    fun asText(entries: List<AppLogEntry>): String =
        entries.joinToString("\n\n") { entry ->
            listOfNotNull(title(entry), summary(entry), detail(entry)).joinToString("\n")
        }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun title(entry: AppLogEntry): String = when (entry) {
    is AppLogEntry.Http -> "${TIME_FMT.format(entry.at)}  ${entry.method} ${entry.status ?: "no response"}"
    is AppLogEntry.Search -> "${TIME_FMT.format(entry.at)}  Search (${entry.source})"
}

internal fun summary(entry: AppLogEntry): String = when (entry) {
    is AppLogEntry.Http -> "${entry.url}  ·  ${entry.durationMs} ms"
    is AppLogEntry.Search ->
        "${entry.from} → ${entry.to}  ·  ${entry.resultCount} result${if (entry.resultCount == 1) "" else "s"}  ·  ${entry.durationMs} ms"
}

internal fun detail(entry: AppLogEntry): String? = when (entry) {
    is AppLogEntry.Http -> entry.detail
    is AppLogEntry.Search -> entry.error
}
