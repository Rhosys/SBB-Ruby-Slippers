package ch.rhosys.sbb.ui.search

import ch.rhosys.sbb.domain.model.Connection
import java.time.Instant

/**
 * Search results laid out in a fixed row of [CAPACITY] slots, one list row per slot. A new
 * search fills from [START]; earlier connections go into the empty slots directly above
 * the first result, later ones into the empty slots directly below the last. Nothing that
 * is already placed ever moves, so the list never jumps while more results load.
 */
class ConnectionSlots private constructor(
    private val slots: Array<Connection?>,
    /** Slot of the earliest connection; `first > last` when there are none. */
    val first: Int,
    /** Slot of the latest connection. */
    val last: Int,
) {
    val isEmpty: Boolean get() = first > last

    operator fun get(index: Int): Connection? = slots.getOrNull(index)

    val connections: List<Connection> get() = (first..last).mapNotNull { slots[it] }

    /** Places [found] connections that depart before the first one directly above it. */
    fun withEarlier(found: List<Connection>): ConnectionSlots {
        if (isEmpty) return startingWith(found)
        val firstDeparture = slots[first]?.departure?.scheduledTime ?: return this
        val fresh = unseen(found)
            .filter { it.departure.scheduledTime!!.isBefore(firstDeparture) }
            .takeLast(first)
        if (fresh.isEmpty()) return this
        val copy = slots.copyOf()
        val newFirst = first - fresh.size
        fresh.forEachIndexed { i, connection -> copy[newFirst + i] = connection }
        return ConnectionSlots(copy, newFirst, last)
    }

    /** Places [found] connections that depart at or after the last one directly below it. */
    fun withLater(found: List<Connection>): ConnectionSlots {
        if (isEmpty) return startingWith(found)
        val lastDeparture = slots[last]?.departure?.scheduledTime ?: return this
        val fresh = unseen(found)
            .filter { !it.departure.scheduledTime!!.isBefore(lastDeparture) }
            .take(CAPACITY - 1 - last)
        if (fresh.isEmpty()) return this
        val copy = slots.copyOf()
        fresh.forEachIndexed { i, connection -> copy[last + 1 + i] = connection }
        return ConnectionSlots(copy, first, last + fresh.size)
    }

    /**
     * Slot of the first connection departing at or after [now] — where the "Now" line goes.
     * `last + 1` when every connection has already left.
     */
    fun nowIndex(now: Instant): Int =
        (first..last).firstOrNull { index ->
            val departure = slots[index]?.departure?.let { it.effectiveTime ?: it.scheduledTime }
            departure != null && !departure.isBefore(now)
        } ?: (last + 1)

    private fun unseen(found: List<Connection>): List<Connection> {
        val present = connections.mapTo(HashSet()) { it.stableKey }
        return found
            .filter { it.departure.scheduledTime != null && it.stableKey !in present }
            .distinctBy { it.stableKey }
            .sortedBy { it.departure.scheduledTime }
    }

    companion object {
        const val CAPACITY = 2000
        const val START = 1000

        val EMPTY = ConnectionSlots(arrayOfNulls(CAPACITY), START, START - 1)

        fun startingWith(found: List<Connection>): ConnectionSlots {
            val sorted = found
                .distinctBy { it.stableKey }
                .sortedBy { it.departure.scheduledTime }
                .take(CAPACITY - START)
            val slots = arrayOfNulls<Connection>(CAPACITY)
            sorted.forEachIndexed { i, connection -> slots[START + i] = connection }
            return ConnectionSlots(slots, START, START + sorted.size - 1)
        }
    }
}
