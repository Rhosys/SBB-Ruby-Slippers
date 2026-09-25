package ch.rhosys.sbb.ui.search

import java.time.LocalDateTime

/**
 * What time a search is for. [Now] is re-resolved against the clock on every query; [Fixed]
 * is what the user picked in the date/time picker and sticks until a Home trip request
 * resets it back to [Now].
 */
sealed interface SearchTimeMode {
    data object Now : SearchTimeMode
    data class Fixed(val dateTime: LocalDateTime) : SearchTimeMode
}

// Depart-after searches ask for trips from this long before the chosen time, so a trip
// that has only just left (and may still be catchable) is still listed.
const val DEPARTURE_BUFFER_MINUTES = 5L

/** The date/time actually sent to the router/API for a query made at [now]. */
fun SearchTimeMode.queryDateTime(now: LocalDateTime, isArriveBy: Boolean): LocalDateTime = TODO()

/** Label on the time button: "Now", or the picked date and time. */
fun SearchTimeMode.label(): String = TODO()

fun SearchTimeMode.afterTimePicked(dateTime: LocalDateTime): SearchTimeMode = TODO()

fun SearchTimeMode.afterHomeTripRequest(): SearchTimeMode = TODO()
