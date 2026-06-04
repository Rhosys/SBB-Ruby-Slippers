package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.rt.GtfsRtDecoder
import ch.rhosys.sbb.data.local.routing.rt.ProtoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

// Helpers to build minimal valid protobuf bytes for testing.
private fun ByteArrayOutputStream.tag(field: Int, wireType: Int) {
    varint(((field shl 3) or wireType).toLong())
}
private fun ByteArrayOutputStream.varint(v: Long) {
    var n = v
    while (n and 0x7F.toLong().inv() != 0L) {
        write(((n and 0x7F) or 0x80).toInt())
        n = n ushr 7
    }
    write(n.toInt())
}
private fun ByteArrayOutputStream.lengthDelimited(field: Int, bytes: ByteArray) {
    tag(field, 2)
    varint(bytes.size.toLong())
    write(bytes)
}
private fun ByteArrayOutputStream.string(field: Int, s: String) = lengthDelimited(field, s.toByteArray())
private fun ByteArrayOutputStream.varintField(field: Int, v: Long) { tag(field, 0); varint(v) }
private fun zigzagEncode(n: Int): Long = ((n shl 1) xor (n shr 31)).toLong()

private fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().apply(block).toByteArray()

class GtfsRtDecoderTest {

    @Test
    fun `empty feed returns empty lists`() {
        // FeedMessage with just a header (field 1, skipped)
        val feed = bytes {
            lengthDelimited(1, bytes { varintField(3, 1L) })  // header with version
        }
        val (updates, alerts) = GtfsRtDecoder().decode(feed)
        assertEquals(0, updates.size)
        assertEquals(0, alerts.size)
    }

    @Test
    fun `trip update with two stop delays is decoded`() {
        val stopUpdate1 = bytes {
            varintField(1, 2L)          // stop_sequence = 2
            string(4, "stop_A")          // stop_id
            lengthDelimited(3, bytes {   // departure delay = +60s
                varintField(1, zigzagEncode(60))
            })
        }
        val stopUpdate2 = bytes {
            varintField(1, 3L)
            string(4, "stop_B")
            lengthDelimited(2, bytes {   // arrival delay = +120s
                varintField(1, zigzagEncode(120))
            })
        }
        val tripDescriptor = bytes {
            string(1, "trip_42")
            string(5, "route_IC1")
            string(4, "20260604")
        }
        val tripUpdate = bytes {
            lengthDelimited(1, tripDescriptor)
            lengthDelimited(2, stopUpdate1)
            lengthDelimited(2, stopUpdate2)
        }
        val entity = bytes {
            string(1, "entity_1")
            lengthDelimited(5, tripUpdate)
        }
        val feed = bytes { lengthDelimited(2, entity) }

        val (updates, alerts) = GtfsRtDecoder().decode(feed)
        assertEquals(1, updates.size)
        val u = updates[0]
        assertEquals("trip_42", u.tripId)
        assertEquals("route_IC1", u.routeId)
        assertEquals("20260604", u.startDate)
        assertEquals(2, u.stopDelays.size)
        assertEquals("stop_A", u.stopDelays[0].stopId)
        assertEquals(60, u.stopDelays[0].departureDelaySec)
        assertEquals("stop_B", u.stopDelays[1].stopId)
        assertEquals(120, u.stopDelays[1].arrivalDelaySec)
    }

    @Test
    fun `negative delay is decoded correctly via zigzag`() {
        val stopUpdate = bytes {
            string(4, "stop_X")
            lengthDelimited(2, bytes {
                varintField(1, zigzagEncode(-30))  // arrival 30s early
            })
        }
        val tripDescriptor = bytes { string(1, "trip_neg") }
        val tripUpdate = bytes {
            lengthDelimited(1, tripDescriptor)
            lengthDelimited(2, stopUpdate)
        }
        val entity = bytes {
            string(1, "e1")
            lengthDelimited(5, tripUpdate)
        }
        val feed = bytes { lengthDelimited(2, entity) }

        val (updates, _) = GtfsRtDecoder().decode(feed)
        assertEquals(-30, updates[0].stopDelays[0].arrivalDelaySec)
    }

    @Test
    fun `absolute departure time is decoded as epoch seconds`() {
        val stopUpdate = bytes {
            string(4, "stop_Y")
            lengthDelimited(3, bytes {
                varintField(2, 1_748_000_000L)  // departure absolute epoch
            })
        }
        val tripDescriptor = bytes { string(1, "trip_abs") }
        val tripUpdate = bytes {
            lengthDelimited(1, tripDescriptor)
            lengthDelimited(2, stopUpdate)
        }
        val entity = bytes { string(1, "e2"); lengthDelimited(5, tripUpdate) }
        val feed = bytes { lengthDelimited(2, entity) }

        val (updates, _) = GtfsRtDecoder().decode(feed)
        assertEquals(1_748_000_000L, updates[0].stopDelays[0].departureAbsoluteEpoch)
        assertNull(updates[0].stopDelays[0].departureDelaySec)
    }

    @Test
    fun `alert with informed entities and translated text`() {
        val translation = bytes { string(1, "Disruption on line IC1") }
        val translatedString = bytes { lengthDelimited(1, translation) }
        val tripDescriptor = bytes { string(1, "trip_99") }
        val entitySelector = bytes {
            string(2, "route_IC1")
            lengthDelimited(5, tripDescriptor)
        }
        val alert = bytes {
            lengthDelimited(5, entitySelector)
            varintField(6, 2L)   // cause = ACCIDENT
            varintField(7, 1L)   // effect = NO_SERVICE
            lengthDelimited(8, translatedString)
        }
        val entity = bytes { string(1, "alert_1"); lengthDelimited(6, alert) }
        val feed = bytes { lengthDelimited(2, entity) }

        val (_, alerts) = GtfsRtDecoder().decode(feed)
        assertEquals(1, alerts.size)
        val a = alerts[0]
        assertEquals("alert_1", a.id)
        assertEquals("Disruption on line IC1", a.headerText)
        assertEquals(listOf("trip_99"), a.informedTripIds)
        assertEquals(listOf("route_IC1"), a.informedRouteIds)
        assertEquals(2, a.cause)
        assertEquals(1, a.effect)
    }

    @Test
    fun `entity with no trip_update or alert is ignored`() {
        val entity = bytes { string(1, "e_empty") }  // no field 5 or 6
        val feed = bytes { lengthDelimited(2, entity) }
        val (updates, alerts) = GtfsRtDecoder().decode(feed)
        assertEquals(0, updates.size)
        assertEquals(0, alerts.size)
    }

    @Test
    fun `multiple entities in one feed`() {
        fun makeEntity(id: String, tripId: String): ByteArray {
            val td = bytes { string(1, tripId) }
            val tu = bytes { lengthDelimited(1, td) }
            return bytes { string(1, id); lengthDelimited(5, tu) }
        }
        val feed = bytes {
            lengthDelimited(2, makeEntity("e1", "trip_A"))
            lengthDelimited(2, makeEntity("e2", "trip_B"))
        }
        val (updates, _) = GtfsRtDecoder().decode(feed)
        assertEquals(2, updates.size)
        assertEquals("trip_A", updates[0].tripId)
        assertEquals("trip_B", updates[1].tripId)
    }

    @Test
    fun `ProtoReader varint decodes multibyte value`() {
        // 300 = 0xAC 0x02 in protobuf varint
        val buf = byteArrayOf(0xAC.toByte(), 0x02)
        val r = ProtoReader(buf)
        assertEquals(300L, r.readVarint())
    }

    @Test
    fun `ProtoReader skip wire type 1 advances 8 bytes`() {
        val buf = ByteArray(10) { it.toByte() }
        val r = ProtoReader(buf)
        r.skip(1)
        assertEquals(8, r.pos)
    }
}
