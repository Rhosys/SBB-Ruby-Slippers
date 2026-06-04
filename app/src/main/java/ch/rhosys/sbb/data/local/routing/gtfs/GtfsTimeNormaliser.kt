package ch.rhosys.sbb.data.local.routing.gtfs

object GtfsTimeNormaliser {

    // Converts a GTFS time string (HH:MM:SS, where HH may be ≥ 24 for post-midnight
    // services) to elapsed seconds since service-day start. Never throws; malformed
    // input returns -1 so callers can detect and skip bad rows.
    fun toSeconds(time: String): Int {
        val parts = time.split(":")
        if (parts.size != 3) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        val s = parts[2].toIntOrNull() ?: return -1
        return h * 3600 + m * 60 + s
    }

    // Returns 0 if the time falls within the service calendar date,
    // or 1 if it overflows into the next calendar day (hour ≥ 24).
    fun calendarDayOffset(time: String): Int {
        val parts = time.split(":")
        if (parts.size != 3) return 0
        val h = parts[0].toIntOrNull() ?: return 0
        return if (h >= 24) 1 else 0
    }
}
