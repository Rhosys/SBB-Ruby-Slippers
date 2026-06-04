package ch.rhosys.sbb.routing

import ch.rhosys.sbb.data.local.routing.algorithm.RoutingEngine
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingQuery
import ch.rhosys.sbb.data.local.routing.algorithm.RoutingTime
import ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetworkBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Routing benchmarks — cold (first call) and warm (cached data) query latency.
 * These are not correctness tests; they document performance expectations for CI.
 * Failure thresholds are deliberately generous to avoid flakiness on slow CI runners.
 *
 * Synthetic network: linear chain of 100 stops, 3 routes each with 5 trips.
 * This is smaller than a real national feed but exercises the hot loop meaningfully.
 */
class RoutingBenchmarkTest {

    companion object {
        private const val STOP_COUNT = 100
        private const val TRIPS_PER_ROUTE = 5
        private const val COLD_BUDGET_MS = 500L  // 500 ms: generous for slow CI
        private const val WARM_BUDGET_MS = 50L   // 50 ms: warm query should be fast
    }

    private val network by lazy { buildSyntheticNetwork() }

    @Test
    fun `cold routing query completes within budget`() = runTest {
        val engine = RoutingEngine(buildSyntheticNetwork()) // fresh engine = cold
        val elapsed = measureMs {
            engine.route(fullQuery()).toList()
        }
        println("[BENCHMARK] cold routing: ${elapsed}ms (budget ${COLD_BUDGET_MS}ms)")
        assertTrue("Cold routing query took ${elapsed}ms, budget was ${COLD_BUDGET_MS}ms",
            elapsed < COLD_BUDGET_MS)
    }

    @Test
    fun `warm routing query completes within budget`() = runTest {
        val engine = RoutingEngine(network)
        engine.route(fullQuery()).toList() // warm up

        val elapsed = measureMs {
            engine.route(fullQuery()).toList()
        }
        println("[BENCHMARK] warm routing: ${elapsed}ms (budget ${WARM_BUDGET_MS}ms)")
        assertTrue("Warm routing query took ${elapsed}ms, budget was ${WARM_BUDGET_MS}ms",
            elapsed < WARM_BUDGET_MS)
    }

    @Test
    fun `arrive-by cold query completes within budget`() = runTest {
        val engine = RoutingEngine(buildSyntheticNetwork())
        val elapsed = measureMs {
            engine.route(fullQuery(routingTime = RoutingTime.ArriveBy(LocalTime.of(12, 0)))).toList()
        }
        println("[BENCHMARK] arrive-by cold: ${elapsed}ms (budget ${COLD_BUDGET_MS}ms)")
        assertTrue("Arrive-by cold query took ${elapsed}ms", elapsed < COLD_BUDGET_MS)
    }

    // ---- Helpers -------------------------------------------------------------

    private fun fullQuery(routingTime: RoutingTime = RoutingTime.DepartAfter(LocalTime.of(7, 0))) =
        RoutingQuery(
            originStopIds = listOf(0),
            destinationStopIds = listOf(STOP_COUNT - 1),
            date = LocalDate.of(2026, 6, 1),
            routingTime = routingTime,
            walkToFirstStop = Duration.ZERO,
            walkFromLastStop = Duration.ZERO,
        )

    private fun buildSyntheticNetwork(): ch.rhosys.sbb.data.local.routing.gtfs.GtfsNetwork {
        val builder = GtfsNetworkBuilder()

        for (i in 0 until STOP_COUNT) {
            builder.addStop(i, "Stop$i", 47.0 + i * 0.01, 8.0 + i * 0.01)
        }

        // Route 1: stops 0..49
        builder.addRoute(0, "R1", (0..49).toList())
        for (t in 0 until TRIPS_PER_ROUTE) {
            val baseMs = (7 + t) * 3600
            val times = buildTimesForRoute(50, baseMs, intervalSec = 300)
            builder.addTrip(0, t, times)
        }

        // Route 2: stops 25..74 (overlaps with R1 for transfer at stop 25..49)
        builder.addRoute(1, "R2", (25..74).toList())
        for (t in 0 until TRIPS_PER_ROUTE) {
            val baseMs = (7 + t) * 3600 + 900 // 15 min offset
            val times = buildTimesForRoute(50, baseMs, intervalSec = 300)
            builder.addTrip(1, 100 + t, times)
        }

        // Route 3: stops 50..99 (continuation of R2)
        builder.addRoute(2, "R3", (50..99).toList())
        for (t in 0 until TRIPS_PER_ROUTE) {
            val baseMs = (7 + t) * 3600 + 1800 // 30 min offset
            val times = buildTimesForRoute(50, baseMs, intervalSec = 300)
            builder.addTrip(2, 200 + t, times)
        }

        // Transfers at overlap points
        for (i in 25..49) {
            builder.addTransfer(i, i, 120) // within-stop transfer 2 min
        }

        return builder.build()
    }

    // Builds a time array for a route of `stopCount` stops, starting at `baseSec`,
    // with `intervalSec` seconds between consecutive stops.
    // Layout: [dep0, arr1, dep1, arr2, ..., arr(N-1)] — 2*(N-1) elements
    private fun buildTimesForRoute(stopCount: Int, baseSec: Int, intervalSec: Int): List<Int> {
        val n = stopCount
        val times = IntArray(2 * (n - 1))
        for (i in 0 until n) {
            val t = baseSec + i * intervalSec
            if (i < n - 1) times[i * 2] = t         // departure at i
            if (i > 0)     times[i * 2 - 1] = t     // arrival at i
        }
        return times.toList()
    }

    private inline fun measureMs(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
}
