package ch.rhosys.sbb.data.remote

import ch.rhosys.sbb.data.remote.dto.ConnectionsResponseDto
import ch.rhosys.sbb.domain.model.Leg
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TransportDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes minimal connections response`() {
        val raw = """
            {
              "connections": [
                {
                  "from": { "departure": "17:03", "station": { "name": "Zürich HB" } },
                  "to":   { "arrival": "18:01",  "station": { "name": "Bern" } },
                  "duration": "00d00:58:00",
                  "transfers": 0,
                  "products": ["IC"]
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ConnectionsResponseDto>(raw)

        assertEquals(1, response.connections.size)
        val conn = response.connections[0]
        assertEquals("17:03", conn.from?.departure)
        assertEquals("18:01", conn.to?.arrival)
        assertEquals("00d00:58:00", conn.duration)
        assertEquals(0, conn.transfers)
        assertEquals(listOf("IC"), conn.products)
        assertNotNull(conn.from?.station)
        assertEquals("Zürich HB", conn.from?.station?.name)
    }

    @Test
    fun `deserializes empty connections list`() {
        val raw = """{"connections": []}"""
        val response = json.decodeFromString<ConnectionsResponseDto>(raw)
        assertEquals(0, response.connections.size)
    }

    @Test
    fun `ignores unknown keys`() {
        val raw = """
            {
              "connections": [],
              "unknownField": "should be ignored",
              "meta": { "extra": true }
            }
        """.trimIndent()
        val response = json.decodeFromString<ConnectionsResponseDto>(raw)
        assertEquals(0, response.connections.size)
    }

    @Test
    fun `address origin walk is split off the trip times`() {
        // Mirrors Wülflingerstr. 261b → Oberfeld: a 3-min walk (no walk.duration) to the
        // 19:30 bus. Trip times must be the bus's, with the walk carried separately.
        val raw = """
            {
              "connections": [
                {
                  "from": { "departure": "2026-09-23T19:27:00+0200", "station": { "name": "8408 Winterthur, Wülflingerstr. 261b" } },
                  "to":   { "arrival": "2026-09-23T19:32:00+0200", "station": { "name": "Winterthur, Oberfeld" } },
                  "transfers": 0,
                  "sections": [
                    {
                      "walk": { "duration": null },
                      "departure": { "departure": "2026-09-23T19:27:00+0200", "station": { "name": "8408 Winterthur, Wülflingerstr. 261b" } },
                      "arrival":   { "arrival": "2026-09-23T19:30:00+0200", "station": { "name": "Winterthur, Lindenplatz" } }
                    },
                    {
                      "journey": { "name": "021644", "category": "B", "to": "Winterthur, Seen" },
                      "departure": { "departure": "2026-09-23T19:30:00+0200", "station": { "name": "Winterthur, Lindenplatz" } },
                      "arrival":   { "arrival": "2026-09-23T19:32:00+0200", "station": { "name": "Winterthur, Oberfeld" } }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val connection = json.decodeFromString<ConnectionsResponseDto>(raw).connections[0].toDomainConnection()

        assertEquals(Instant.parse("2026-09-23T17:30:00Z"), connection.departure.scheduledTime)
        assertEquals("Winterthur, Lindenplatz", connection.departure.stationName)
        assertEquals(Instant.parse("2026-09-23T17:32:00Z"), connection.arrival.scheduledTime)
        assertEquals(Duration.ofMinutes(3), connection.walkToFirstStop)
        assertEquals(Duration.ZERO, connection.walkFromLastStop)
        assertEquals(1, connection.legs.size)
        assertTrue(connection.legs[0] is Leg.Transit)
    }
}
