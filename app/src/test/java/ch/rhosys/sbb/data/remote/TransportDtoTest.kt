package ch.rhosys.sbb.data.remote

import ch.rhosys.sbb.data.remote.dto.ConnectionsResponseDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
}
