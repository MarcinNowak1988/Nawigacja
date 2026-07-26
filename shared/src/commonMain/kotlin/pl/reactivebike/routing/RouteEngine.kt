package pl.reactivebike.routing

/** Punkt na mapie w układzie WGS 84. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) {
            "Szerokosc geograficzna poza zakresem <-90, 90>: $latitude"
        }
        require(longitude in -180.0..180.0) {
            "Dlugosc geograficzna poza zakresem <-180, 180>: $longitude"
        }
    }
}

/**
 * Zlecenie wyznaczenia trasy.
 *
 * @property waypoints punkty trasy w kolejności przejazdu — co najmniej start i cel
 * @property weights wagi krawędzi obowiązujące dla tego wyznaczenia; muszą być już
 *   znormalizowane do postaci wyłącznie podwyższającej (ADR-0002)
 */
data class RouteRequest(
    val waypoints: List<GeoPoint>,
    val weights: RoutingWeights = RoutingWeights.DEFAULT,
) {
    init {
        require(waypoints.size >= 2) {
            "Trasa wymaga co najmniej dwoch punktow, otrzymano: ${waypoints.size}"
        }
    }
}

/**
 * Wyznaczona trasa.
 *
 * @property geometry kolejne punkty przebiegu trasy
 * @property distanceMeters łączna długość
 * @property estimatedDurationSeconds szacowany czas przejazdu
 */
data class Route(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long,
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Dlugosc trasy musi byc nieujemna i skonczona, otrzymano: $distanceMeters"
        }
        require(estimatedDurationSeconds >= 0L) {
            "Czas przejazdu musi byc nieujemny, otrzymano: $estimatedDurationSeconds"
        }
    }
}

/** Powód niepowodzenia wyznaczenia trasy. */
enum class RouteFailure {

    /** Graf nie zawiera połączenia spełniającego zadane wagi. */
    NO_ROUTE_FOUND,

    /** Brak danych mapowych dla obszaru — typowo poza pobranym regionem offline. */
    MISSING_MAP_DATA,

    /** Błąd samego silnika trasowania. */
    ENGINE_ERROR,
}

/** Wynik wyznaczania trasy. */
sealed interface RouteResult {
    data class Success(val route: Route) : RouteResult
    data class Failure(val reason: RouteFailure) : RouteResult
}

/**
 * Port silnika trasowania — granica między warstwą wspólną a warstwą natywną.
 *
 * Zgodnie z ADR-0001 (`docs/adr/0001-silnik-trasowania-per-platforma.md`)
 * sam silnik **nie jest kodem wspólnym**: GraphHopper jest biblioteką Javy i nie uruchomi
 * się na iOS. Warstwa wspólna posiada model kosztu jako dane ([RoutingWeights]) i wyznacza
 * przez ten interfejs, a każda platforma dostarcza własną implementację tłumaczącą wagi
 * na format swojego silnika — na Androidzie na custom model GraphHoppera.
 *
 * Implementacje muszą sygnalizować niepowodzenia przez [RouteResult.Failure], a nie przez
 * wyjątki: brak trasy albo brak danych mapowych to w nawigacji offline sytuacje normalne,
 * nie błędy programu.
 */
interface RouteEngine {

    suspend fun route(request: RouteRequest): RouteResult
}
