package ch.rhosys.sbb.ui.search

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

class SearchErrorTextTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "upstream down".toResponseBody()))

    @Test
    fun serviceUnavailableNamesTheStatusAndWhereTheDetailsAre() {
        assertEquals(
            "transport.opendata.ch answered 503 Service Unavailable. See Settings → Logs for the full response.",
            searchErrorText(http(503)),
        )
    }

    @Test
    fun rateLimitSaysSo() {
        assertEquals(
            "transport.opendata.ch answered 429 Too Many Requests — the API's rate limit (3 searches a minute) " +
                "was hit. See Settings → Logs for the full response.",
            searchErrorText(http(429)),
        )
    }

    @Test
    fun noResponseNamesTheNetworkFailure() {
        assertEquals(
            "Couldn't reach transport.opendata.ch (SocketTimeoutException: timeout).",
            searchErrorText(SocketTimeoutException("timeout")),
        )
    }
}
