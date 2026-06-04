package ch.rhosys.sbb.data.local.routing.rt

// Minimal hand-rolled protobuf decoder for the GTFS-RT FeedMessage.
// Only the fields needed for TripUpdates and Alerts are parsed; all others are skipped.
// Wire types: 0=varint, 1=64-bit, 2=length-delimited, 5=32-bit.
// Signed int32 fields (e.g. StopTimeEvent.delay) use zigzag encoding: (n ushr 1) xor -(n and 1).

class GtfsRtDecoder {

    fun decode(bytes: ByteArray): Pair<List<RtTripUpdate>, List<RtAlert>> {
        val reader = ProtoReader(bytes)
        val tripUpdates = mutableListOf<RtTripUpdate>()
        val alerts = mutableListOf<RtAlert>()
        while (!reader.exhausted()) {
            val (field, wire) = reader.readTag()
            when (field) {
                1 -> reader.skip(wire)   // FeedHeader
                2 -> {                   // FeedEntity (repeated)
                    if (wire == 2) parseFeedEntity(reader.readBytes(), tripUpdates, alerts)
                    else reader.skip(wire)
                }
                else -> reader.skip(wire)
            }
        }
        return tripUpdates to alerts
    }

    private fun parseFeedEntity(
        bytes: ByteArray,
        tripUpdates: MutableList<RtTripUpdate>,
        alerts: MutableList<RtAlert>,
    ) {
        val r = ProtoReader(bytes)
        var id = ""
        var tripUpdate: RtTripUpdate? = null
        var alert: RtAlert? = null
        while (!r.exhausted()) {
            val (field, wire) = r.readTag()
            when (field) {
                1 -> id = r.readString()
                5 -> if (wire == 2) tripUpdate = parseTripUpdate(r.readBytes())
                     else r.skip(wire)
                6 -> if (wire == 2) alert = parseAlert(id, r.readBytes())
                     else r.skip(wire)
                else -> r.skip(wire)
            }
        }
        tripUpdate?.let { tripUpdates.add(it) }
        alert?.let { alerts.add(it) }
    }

    private fun parseTripUpdate(bytes: ByteArray): RtTripUpdate {
        val r = ProtoReader(bytes)
        var tripId = ""
        var routeId = ""
        var startDate = ""
        val stops = mutableListOf<RtStopDelay>()
        while (!r.exhausted()) {
            val (field, wire) = r.readTag()
            when (field) {
                1 -> if (wire == 2) {   // TripDescriptor
                    val td = ProtoReader(r.readBytes())
                    while (!td.exhausted()) {
                        val (tf, tw) = td.readTag()
                        when (tf) {
                            1 -> tripId = td.readString()
                            4 -> startDate = td.readString()
                            5 -> routeId = td.readString()
                            else -> td.skip(tw)
                        }
                    }
                } else r.skip(wire)
                2 -> if (wire == 2) stops.add(parseStopTimeUpdate(r.readBytes()))
                     else r.skip(wire)
                else -> r.skip(wire)
            }
        }
        return RtTripUpdate(tripId, routeId, startDate, stops)
    }

    private fun parseStopTimeUpdate(bytes: ByteArray): RtStopDelay {
        val r = ProtoReader(bytes)
        var stopSeq: Int? = null
        var stopId: String? = null
        var arrDelay: Int? = null
        var arrTime: Long? = null
        var depDelay: Int? = null
        var depTime: Long? = null
        while (!r.exhausted()) {
            val (field, wire) = r.readTag()
            when (field) {
                1 -> stopSeq = r.readVarint().toInt()
                2 -> if (wire == 2) {   // arrival StopTimeEvent
                    val e = ProtoReader(r.readBytes())
                    while (!e.exhausted()) {
                        val (ef, ew) = e.readTag()
                        when (ef) {
                            1 -> arrDelay = zigzag(e.readVarint())
                            2 -> arrTime = e.readVarint()
                            else -> e.skip(ew)
                        }
                    }
                } else r.skip(wire)
                3 -> if (wire == 2) {   // departure StopTimeEvent
                    val e = ProtoReader(r.readBytes())
                    while (!e.exhausted()) {
                        val (ef, ew) = e.readTag()
                        when (ef) {
                            1 -> depDelay = zigzag(e.readVarint())
                            2 -> depTime = e.readVarint()
                            else -> e.skip(ew)
                        }
                    }
                } else r.skip(wire)
                4 -> stopId = r.readString()
                else -> r.skip(wire)
            }
        }
        return RtStopDelay(stopId, stopSeq, arrDelay, depDelay, arrTime, depTime)
    }

    private fun parseAlert(entityId: String, bytes: ByteArray): RtAlert {
        val r = ProtoReader(bytes)
        val tripIds = mutableListOf<String>()
        val routeIds = mutableListOf<String>()
        val stopIds = mutableListOf<String>()
        var cause = 0
        var effect = 0
        var header = ""
        var description = ""
        while (!r.exhausted()) {
            val (field, wire) = r.readTag()
            when (field) {
                1 -> r.skip(wire)  // active_period
                5 -> if (wire == 2) {   // informed_entity EntitySelector
                    val sel = ProtoReader(r.readBytes())
                    while (!sel.exhausted()) {
                        val (sf, sw) = sel.readTag()
                        when (sf) {
                            2 -> routeIds.add(sel.readString())
                            4 -> stopIds.add(sel.readString())
                            5 -> if (sw == 2) {   // nested TripDescriptor
                                val td = ProtoReader(sel.readBytes())
                                while (!td.exhausted()) {
                                    val (tf, tw) = td.readTag()
                                    when (tf) {
                                        1 -> tripIds.add(td.readString())
                                        else -> td.skip(tw)
                                    }
                                }
                            } else sel.skip(sw)
                            else -> sel.skip(sw)
                        }
                    }
                } else r.skip(wire)
                6 -> cause = r.readVarint().toInt()
                7 -> effect = r.readVarint().toInt()
                8 -> if (wire == 2) header = parseTranslatedString(r.readBytes())
                     else r.skip(wire)
                9 -> if (wire == 2) description = parseTranslatedString(r.readBytes())
                     else r.skip(wire)
                else -> r.skip(wire)
            }
        }
        return RtAlert(entityId, header, description, tripIds, routeIds, stopIds, cause, effect)
    }

    private fun parseTranslatedString(bytes: ByteArray): String {
        val r = ProtoReader(bytes)
        while (!r.exhausted()) {
            val (field, wire) = r.readTag()
            if (field == 1 && wire == 2) {
                // Translation message — take the first one we encounter
                val tr = ProtoReader(r.readBytes())
                while (!tr.exhausted()) {
                    val (tf, tw) = tr.readTag()
                    if (tf == 1) return tr.readString() else tr.skip(tw)
                }
            } else r.skip(wire)
        }
        return ""
    }

    // Zigzag decode for signed int32 (protobuf sint32 encoding).
    private fun zigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()
}

internal class ProtoReader(private val buf: ByteArray) {
    var pos = 0

    fun exhausted() = pos >= buf.size

    // Returns (fieldNumber, wireType)
    fun readTag(): Pair<Int, Int> {
        val v = readVarint().toInt()
        return (v ushr 3) to (v and 0x7)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> { val len = readVarint().toInt(); pos += len }
            5 -> pos += 4
            // wire type 3/4 (groups) are deprecated in proto3 — ignore
        }
    }
}
