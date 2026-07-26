package pl.reactivebike.routing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RouteEngineTest {

    private val krakow = GeoPoint(50.0647, 19.9450)
    private val zakopane = GeoPoint(49.2992, 19.9496)

    // --- walidacja modelu ---

    @Test
    fun `szerokosc geograficzna poza zakresem jest odrzucana`() {
        assertFailsWith<IllegalArgumentException> { GeoPoint(latitude = 91.0, longitude = 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(latitude = -90.1, longitude = 0.0) }
    }

    @Test
    fun `dlugosc geograficzna poza zakresem jest odrzucana`() {
        assertFailsWith<IllegalArgumentException> { GeoPoint(latitude = 0.0, longitude = 180.1) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(latitude = 0.0, longitude = -180.1) }
    }

    @Test
    fun `krance zakresow sa dopuszczalne`() {
        GeoPoint(90.0, 180.0)
        GeoPoint(-90.0, -180.0)
    }

    @Test
    fun `trasa z jednym punktem jest odrzucana`() {
        assertFailsWith<IllegalArgumentException> { RouteRequest(waypoints = listOf(krakow)) }
    }

    @Test
    fun `trasa bez punktow jest odrzucana`() {
        assertFailsWith<IllegalArgumentException> { RouteRequest(waypoints = emptyList()) }
    }

    @Test
    fun `trasa z punktami posrednimi jest dopuszczalna`() {
        val request = RouteRequest(waypoints = listOf(krakow, GeoPoint(49.8, 19.9), zakopane))

        assertEquals(3, request.waypoints.size)
    }

    @Test
    fun `ujemna dlugosc trasy jest odrzucana`() {
        assertFailsWith<IllegalArgumentException> {
            Route(geometry = listOf(krakow, zakopane), distanceMeters = -1.0, estimatedDurationSeconds = 0)
        }
    }

    @Test
    fun `ujemny czas przejazdu jest odrzucany`() {
        assertFailsWith<IllegalArgumentException> {
            Route(geometry = listOf(krakow, zakopane), distanceMeters = 1.0, estimatedDurationSeconds = -1)
        }
    }

    // --- kontrakt portu ---

    /** Atrapa silnika — sprawdza, że interfejs da się zaimplementować i przetestować bez sprzętu. */
    private class FakeRouteEngine(private val result: RouteResult) : RouteEngine {
        var lastRequest: RouteRequest? = null

        override suspend fun route(request: RouteRequest): RouteResult {
            lastRequest = request
            return result
        }
    }

    @Test
    fun `silnik dostaje wagi przekazane w zleceniu`() = runTest {
        val weights = RoutingWeights(surface = WeightTable(mapOf("mud" to EdgeWeight.IMPASSABLE)))
        val engine = FakeRouteEngine(RouteResult.Failure(RouteFailure.NO_ROUTE_FOUND))

        engine.route(RouteRequest(listOf(krakow, zakopane), weights))

        assertEquals(weights, engine.lastRequest?.weights)
    }

    @Test
    fun `brak trasy jest zwracany jako wynik a nie wyjatek`() = runTest {
        val engine = FakeRouteEngine(RouteResult.Failure(RouteFailure.NO_ROUTE_FOUND))

        val result = assertIs<RouteResult.Failure>(engine.route(RouteRequest(listOf(krakow, zakopane))))

        assertEquals(RouteFailure.NO_ROUTE_FOUND, result.reason)
    }

    @Test
    fun `brak danych mapowych jest zwracany jako wynik`() = runTest {
        val engine = FakeRouteEngine(RouteResult.Failure(RouteFailure.MISSING_MAP_DATA))

        val result = assertIs<RouteResult.Failure>(engine.route(RouteRequest(listOf(krakow, zakopane))))

        assertEquals(RouteFailure.MISSING_MAP_DATA, result.reason)
    }

    @Test
    fun `udane wyznaczenie zwraca trase`() = runTest {
        val route = Route(
            geometry = listOf(krakow, zakopane),
            distanceMeters = 104_000.0,
            estimatedDurationSeconds = 18_000,
        )
        val engine = FakeRouteEngine(RouteResult.Success(route))

        val result = assertIs<RouteResult.Success>(engine.route(RouteRequest(listOf(krakow, zakopane))))

        assertEquals(route, result.route)
    }

    @Test
    fun `zlecenie bez podanych wag uzywa domyslnych`() {
        val request = RouteRequest(waypoints = listOf(krakow, zakopane))

        assertEquals(RoutingWeights.DEFAULT, request.weights)
    }
}
