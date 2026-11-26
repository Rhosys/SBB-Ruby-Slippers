package ch.rhosys.sbb.data.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// Recent API calls and searches, shown in Settings → Logs. Memory only, by design: it's
// gone when the app process ends.
@Singleton
class AppLog @Inject constructor() {

    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    // Newest first.
    val entries: StateFlow<List<AppLogEntry>> = _entries

    fun add(entry: AppLogEntry) {
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val MAX_ENTRIES = 300
    }
}

sealed class AppLogEntry {
    abstract val at: Instant
    abstract val isError: Boolean

    data class Http(
        override val at: Instant,
        val method: String,
        val url: String,
        // Null when no response arrived at all (timeout, no network, DNS, TLS).
        val status: Int?,
        val durationMs: Long,
        // Response body for failed calls (capped), or the exception for calls with none.
        val detail: String? = null,
    ) : AppLogEntry() {
        override val isError: Boolean get() = status == null || status >= 400
    }

    data class Search(
        override val at: Instant,
        val from: String,
        val to: String,
        // "timetable" (on-device route planner) or "API".
        val source: String,
        val resultCount: Int,
        val durationMs: Long,
        val error: String? = null,
    ) : AppLogEntry() {
        override val isError: Boolean get() = error != null
    }
}
