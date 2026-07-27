package pl.reactivebike.routing.valhalla

import pl.reactivebike.routing.GeoPoint
import pl.reactivebike.routing.RouteFailure
import pl.reactivebike.routing.RouteRequest
import pl.reactivebike.routing.RouteResult
import pl.reactivebike.weather.WeatherConditions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValhallaRoutingTest {

    private val start = GeoPoint(50.0647, 19.9450)
    private val finish = GeoPoint(50.0700, 19.9500)
    private val request = RouteRequest(listOf(start, finish))

    // --- budowanie zapytania ---

    @Test
    fun `zapytanie zawiera punkty trasy i profil rowerowy`() {
        val body = ValhallaRouting.buildRequest(request)

        assertTrue(body.contains("\"costing\":\"bicycle\""), body)
        assertTrue(body.contains("50.0647"), body)
        assertTrue(body.contains("19.945"), body)
    }

    @Test
    fun `zapytanie prosi o instrukcje po polsku i kilometry`() {
        val body = ValhallaRouting.buildRequest(request)

        assertTrue(body.contains("pl-PL"), body)
        assertTrue(body.contains("kilometers"), body)
    }

    @Test
    fun `punkty posrednie trafiaja do zapytania w kolejnosci`() {
        val via = GeoPoint(50.066, 19.947)
        val body = ValhallaRouting.buildRequest(RouteRequest(listOf(start, via, finish)))

        val firstIndex = body.indexOf("50.0647")
        val viaIndex = body.indexOf("50.066")
        val lastIndex = body.indexOf("50.07")

        assertTrue(firstIndex < viaIndex && viaIndex < lastIndex, body)
    }

    // --- odwzorowanie pogody na profil ---

    @Test
    fun `sucha pogoda slabo odstrasza zle nawierzchnie`() {
        val dry = BicycleCosting.forConditions(WeatherConditions(precipitationMm = 0.0))

        assertEquals(0.25, dry.avoidBadSurfaces)
    }

    @Test
    fun `ulewa maksymalnie odstrasza zle nawierzchnie`() {
        val downpour = BicycleCosting.forConditions(WeatherConditions(precipitationMm = 6.0))

        assertEquals(1.0, downpour.avoidBadSurfaces)
        assertEquals("Road", downpour.bicycleType)
    }

    @Test
    fun `im gorsza pogoda tym mocniejsze odstraszanie`() {
        val dry = BicycleCosting.forConditions(WeatherConditions(precipitationMm = 0.0))
        val rain = BicycleCosting.forConditions(WeatherConditions(precipitationMm = 1.0))
        val downpour = BicycleCosting.forConditions(WeatherConditions(precipitationMm = 6.0))

        assertTrue(
            dry.avoidBadSurfaces < rain.avoidBadSurfaces &&
                rain.avoidBadSurfaces < downpour.avoidBadSurfaces,
        )
    }

    @Test
    fun `mroz tez odstrasza zle nawierzchnie`() {
        val frost = BicycleCosting.forConditions(WeatherConditions(temperatureCelsius = -3.0))

        assertTrue(frost.avoidBadSurfaces > 0.25)
    }

    @Test
    fun `brak danych pogodowych daje ustawienia domyslne`() {
        assertEquals(0.25, BicycleCosting.forConditions(null).avoidBadSurfaces)
    }

    // --- parsowanie odpowiedzi ---

    /** Kształt zgodny z odpowiedzią Valhalli dla profilu rowerowego. */
    private val validResponse = """
        {
          "trip": {
            "legs": [
              {
                "maneuvers": [
                  {"type": 1, "instruction": "Jedź na północ ulicą Floriańską.",
                   "length": 0.2, "time": 60.0, "begin_shape_index": 0, "end_shape_index": 2},
                  {"type": 10, "instruction": "Skręć w prawo w Rynek Główny.",
                   "length": 0.35, "time": 90.0, "begin_shape_index": 2, "end_shape_index": 4},
                  {"type": 4, "instruction": "Dotarłeś do celu.",
                   "length": 0.0, "time": 0.0, "begin_shape_index": 4, "end_shape_index": 4}
                ],
                "summary": {"length": 0.55, "time": 150.0},
                "shape": "_gjxbBohbwYoJ_@oJ_@oJ_@oJ_@"
              }
            ],
            "summary": {"length": 0.55, "time": 150.0},
            "status": 0,
            "status_message": "Found route between points"
          }
        }
    """.trimIndent()

    @Test
    fun `poprawna odpowiedz daje trase`() {
        val result = assertIs<RouteResult.Success>(ValhallaRouting.parse(validResponse))

        assertTrue(result.route.geometry.size >= 2, "punktow: ${result.route.geometry.size}")
    }

    @Test
    fun `dlugosc i czas sa przeliczane z kilometrow i sekund`() {
        val result = assertIs<RouteResult.Success>(ValhallaRouting.parse(validResponse))

        assertEquals(550.0, result.route.distanceMeters, absoluteTolerance = 1e-6)
        assertEquals(150L, result.route.estimatedDurationSeconds)
    }

    @Test
    fun `manewry sa przenoszone wraz z trescia`() {
        val result = assertIs<RouteResult.Success>(ValhallaRouting.parse(validResponse))

        assertEquals(3, result.route.maneuvers.size)
        assertEquals("Jedź na północ ulicą Floriańską.", result.route.maneuvers.first().instruction)
        assertEquals("Dotarłeś do celu.", result.route.maneuvers.last().instruction)
    }

    @Test
    fun `dlugosc manewru jest przeliczana na metry`() {
        val result = assertIs<RouteResult.Success>(ValhallaRouting.parse(validResponse))

        assertEquals(200.0, result.route.maneuvers[0].distanceMeters, absoluteTolerance = 1e-6)
        assertEquals(350.0, result.route.maneuvers[1].distanceMeters, absoluteTolerance = 1e-6)
    }

    @Test
    fun `indeksy manewrow sa niemalejace`() {
        val result = assertIs<RouteResult.Success>(ValhallaRouting.parse(validResponse))
        val indices = result.route.maneuvers.map { it.beginShapeIndex }

        assertEquals(indices.sorted(), indices, "otrzymano: $indices")
    }

    /** Przy wielu odcinkach indeksy manewrów muszą być przesunięte o długość poprzednich. */
    @Test
    fun `manewry z drugiego odcinka nie wskazuja na poczatek trasy`() {
        val twoLegs = """
            {
              "trip": {
                "legs": [
                  {"shape": "_gjxbBohbwYoJ_@oJ_@", "summary": {"length": 0.2, "time": 60.0},
                   "maneuvers": [{"instruction": "Start", "length": 0.2, "time": 60.0, "begin_shape_index": 0}]},
                  {"shape": "_gjxbBohbwYoJ_@oJ_@", "summary": {"length": 0.2, "time": 60.0},
                   "maneuvers": [{"instruction": "Drugi odcinek", "length": 0.2, "time": 60.0, "begin_shape_index": 0}]}
                ],
                "summary": {"length": 0.4, "time": 120.0}
              }
            }
        """.trimIndent()

        val result = assertIs<RouteResult.Success>(ValhallaRouting.parse(twoLegs))
        val second = result.route.maneuvers.first { it.instruction == "Drugi odcinek" }

        assertTrue(second.beginShapeIndex > 0, "indeks drugiego odcinka: ${second.beginShapeIndex}")
    }

    @Test
    fun `brak trasy jest rozpoznawany po kodzie bledu`() {
        val noRoute = """{"error_code": 171, "error": "No suitable edges near location", "status_code": 400}"""

        val result = assertIs<RouteResult.Failure>(ValhallaRouting.parse(noRoute))

        assertEquals(RouteFailure.NO_ROUTE_FOUND, result.reason)
    }

    @Test
    fun `inny kod bledu to awaria silnika`() {
        val broken = """{"error_code": 500, "error": "Internal error", "status_code": 500}"""

        val result = assertIs<RouteResult.Failure>(ValhallaRouting.parse(broken))

        assertEquals(RouteFailure.ENGINE_ERROR, result.reason)
    }

    @Test
    fun `pusta odpowiedz to awaria silnika`() {
        assertEquals(
            RouteFailure.ENGINE_ERROR,
            assertIs<RouteResult.Failure>(ValhallaRouting.parse(null)).reason,
        )
        assertEquals(
            RouteFailure.ENGINE_ERROR,
            assertIs<RouteResult.Failure>(ValhallaRouting.parse("   ")).reason,
        )
    }

    @Test
    fun `niepoprawny JSON to awaria silnika`() {
        val result = assertIs<RouteResult.Failure>(ValhallaRouting.parse("<html>502</html>"))

        assertEquals(RouteFailure.ENGINE_ERROR, result.reason)
    }

    @Test
    fun `odpowiedz bez odcinkow to brak trasy`() {
        val result = assertIs<RouteResult.Failure>(ValhallaRouting.parse("""{"trip": {"legs": []}}"""))

        assertEquals(RouteFailure.NO_ROUTE_FOUND, result.reason)
    }

    @Test
    fun `odcinek z jednym punktem geometrii to brak trasy`() {
        val degenerate = """{"trip": {"legs": [{"shape": "_gjxbBohbwY", "summary": {"length": 0.0, "time": 0.0}}]}}"""

        val result = assertIs<RouteResult.Failure>(ValhallaRouting.parse(degenerate))

        assertEquals(RouteFailure.NO_ROUTE_FOUND, result.reason)
    }

    @Test
    fun `publiczny endpoint jest adresem https`() {
        assertTrue(ValhallaRouting.PUBLIC_ENDPOINT.startsWith("https://"), ValhallaRouting.PUBLIC_ENDPOINT)
    }
}
