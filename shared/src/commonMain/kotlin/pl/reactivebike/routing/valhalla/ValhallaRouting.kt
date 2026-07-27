package pl.reactivebike.routing.valhalla

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.reactivebike.routing.GeoPoint
import pl.reactivebike.routing.Maneuver
import pl.reactivebike.routing.Polyline
import pl.reactivebike.routing.Route
import pl.reactivebike.routing.RouteFailure
import pl.reactivebike.routing.RouteRequest
import pl.reactivebike.routing.RouteResult
import pl.reactivebike.weather.WeatherConditions

/**
 * Klient publicznej instancji Valhalli, w części niezależnej od platformy.
 *
 * Transport HTTP należy do warstwy natywnej — tutaj mieszka budowanie treści zapytania
 * i parsowanie odpowiedzi, dzięki czemu jedno i drugie da się pokryć testami bez sieci.
 *
 * **To jest rozwiązanie przejściowe.** Trasowanie sieciowe łamie obietnicę offline-first
 * z sekcji 8; docelowy silnik na urządzeniu podmieni implementację `RouteEngine`
 * (ADR-0001), nie ruszając reszty aplikacji.
 */
object ValhallaRouting {

    /** Publiczna instancja utrzymywana przez FOSSGIS dla społeczności OSM. Bez klucza API. */
    const val PUBLIC_ENDPOINT = "https://valhalla1.openstreetmap.de/route"

    /** Treść żądania POST. */
    fun buildRequest(request: RouteRequest, conditions: WeatherConditions? = null): String =
        json.encodeToString(
            ValhallaRequest.serializer(),
            ValhallaRequest(
                locations = request.waypoints.map { ValhallaLocation(it.latitude, it.longitude) },
                costingOptions = ValhallaCostingOptions(BicycleCosting.forConditions(conditions)),
            ),
        )

    fun parse(rawResponse: String?): RouteResult {
        if (rawResponse.isNullOrBlank()) {
            return RouteResult.Failure(RouteFailure.ENGINE_ERROR)
        }

        val response = try {
            json.decodeFromString(ValhallaResponse.serializer(), rawResponse)
        } catch (_: SerializationException) {
            return RouteResult.Failure(RouteFailure.ENGINE_ERROR)
        }

        // Valhalla sygnalizuje brak trasy kodem błędu, nie pustą odpowiedzią.
        response.errorCode?.let { code ->
            return RouteResult.Failure(
                if (code in NO_ROUTE_ERROR_CODES) RouteFailure.NO_ROUTE_FOUND else RouteFailure.ENGINE_ERROR,
            )
        }

        val legs = response.trip?.legs.orEmpty()
        if (legs.isEmpty()) return RouteResult.Failure(RouteFailure.NO_ROUTE_FOUND)

        val geometry = mutableListOf<GeoPoint>()
        val maneuvers = mutableListOf<Maneuver>()

        legs.forEach { leg ->
            val offset = geometry.size
            val shape = leg.shape?.let { Polyline.decode(it) }.orEmpty()
            geometry.addAll(shape)

            leg.maneuvers.orEmpty().forEach { m ->
                maneuvers.add(
                    Maneuver(
                        instruction = m.instruction.orEmpty().ifBlank { "Jedź dalej" },
                        distanceMeters = (m.lengthKm ?: 0.0) * 1_000,
                        durationSeconds = (m.timeSeconds ?: 0.0).toLong(),
                        // Indeksy manewrów są liczone w obrębie odcinka, a geometrię sklejamy
                        // z wielu odcinków — bez przesunięcia manewry z drugiego odcinka
                        // wskazywałyby na początek trasy.
                        beginShapeIndex = offset + (m.beginShapeIndex ?: 0),
                    ),
                )
            }
        }

        if (geometry.size < 2) return RouteResult.Failure(RouteFailure.NO_ROUTE_FOUND)

        val summary = response.trip?.summary
        return RouteResult.Success(
            Route(
                geometry = geometry,
                distanceMeters = (summary?.lengthKm ?: 0.0) * 1_000,
                estimatedDurationSeconds = (summary?.timeSeconds ?: 0.0).toLong(),
                maneuvers = maneuvers,
            ),
        )
    }

    /** Kody, którymi Valhalla mówi „nie ma takiej trasy", a nie „coś się zepsuło". */
    private val NO_ROUTE_ERROR_CODES = setOf(154, 170, 171, 172)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/**
 * Odwzorowanie warunków pogodowych na opcje profilu rowerowego Valhalli.
 *
 * **To jest przybliżenie.** Model wag z sekcji 5 pozwala odstraszać dowolną nawierzchnię
 * z dowolną siłą; publiczna Valhalla przyjmuje wyłącznie kilka predefiniowanych pokręteł.
 * Odwzorowujemy więc intencję, nie wagi: „pada" znaczy tu „unikaj kiepskich nawierzchni",
 * a nie konkretny mnożnik dla błota. Pełny model wag wróci razem z silnikiem na urządzeniu.
 */
object BicycleCosting {

    fun forConditions(conditions: WeatherConditions?): BicycleOptions = when {
        conditions == null -> BicycleOptions(avoidBadSurfaces = 0.25)

        conditions.isHeavyRain -> BicycleOptions(
            bicycleType = "Road",
            avoidBadSurfaces = 1.0,
            useRoads = 0.6,
        )

        conditions.isRaining -> BicycleOptions(
            avoidBadSurfaces = 0.8,
            useRoads = 0.5,
        )

        conditions.isFreezing -> BicycleOptions(
            avoidBadSurfaces = 0.8,
            useRoads = 0.4,
        )

        else -> BicycleOptions(avoidBadSurfaces = 0.25)
    }
}

@Serializable
data class BicycleOptions(
    @SerialName("bicycle_type") val bicycleType: String = "Hybrid",
    @SerialName("avoid_bad_surfaces") val avoidBadSurfaces: Double = 0.25,
    @SerialName("use_roads") val useRoads: Double = 0.4,
    @SerialName("use_hills") val useHills: Double = 0.4,
)

@Serializable
internal data class ValhallaRequest(
    val locations: List<ValhallaLocation>,
    val costing: String = "bicycle",
    @SerialName("costing_options") val costingOptions: ValhallaCostingOptions,
    @SerialName("directions_options") val directionsOptions: ValhallaDirectionsOptions = ValhallaDirectionsOptions(),
)

@Serializable
internal data class ValhallaLocation(val lat: Double, val lon: Double)

@Serializable
internal data class ValhallaCostingOptions(val bicycle: BicycleOptions)

@Serializable
internal data class ValhallaDirectionsOptions(
    val units: String = "kilometers",
    val language: String = "pl-PL",
)

@Serializable
internal data class ValhallaResponse(
    val trip: ValhallaTrip? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val error: String? = null,
)

@Serializable
internal data class ValhallaTrip(
    val legs: List<ValhallaLeg>? = null,
    val summary: ValhallaSummary? = null,
)

@Serializable
internal data class ValhallaLeg(
    val shape: String? = null,
    val maneuvers: List<ValhallaManeuver>? = null,
    val summary: ValhallaSummary? = null,
)

@Serializable
internal data class ValhallaSummary(
    @SerialName("length") val lengthKm: Double? = null,
    @SerialName("time") val timeSeconds: Double? = null,
)

@Serializable
internal data class ValhallaManeuver(
    val instruction: String? = null,
    @SerialName("length") val lengthKm: Double? = null,
    @SerialName("time") val timeSeconds: Double? = null,
    @SerialName("begin_shape_index") val beginShapeIndex: Int? = null,
)
