package ch.rhosys.sbb.ui.search

import ch.rhosys.sbb.domain.model.Connection
import java.time.Instant

/**
 * [current] plus whatever in [found] isn't already shown, sorted by departure. Rows are
 * keyed by [Connection.stableKey], so connections added above the visible ones don't move
 * what's on screen.
 */
internal fun mergeConnections(current: List<Connection>, found: List<Connection>): List<Connection> =
    (current + found)
        .distinctBy { it.stableKey }
        .sortedBy { it.departure.scheduledTime }

/** Index of the first connection departing at or after [now] — where the NOW line goes. `size` when all have left. */
internal fun nowIndex(connections: List<Connection>, now: Instant): Int =
    connections.indexOfFirst { connection ->
        val departure = connection.departure.let { it.effectiveTime ?: it.scheduledTime }
        departure != null && !departure.isBefore(now)
    }.let { if (it == -1) connections.size else it }
