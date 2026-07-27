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

    /**
     * Treść żądania POST.
     *
     * Wszystkie punkty jadą jako `break`: każdy dostaje własny odcinek i własne manewry,
     * a Valhalla może w nich zawrócić. Alternatywa (`through`) prowadzi trasę przez punkt
     * bez możliwości zawrócenia, co przy punkcie wskazanym palcem na drodze jednokierunkowej
     * potrafi wygenerować wielokilometrową pętlę zamiast powiedzieć „tędy nie da się".
     */
    fun buildRequest(
        request: RouteRequest,
        profile: BicycleProfile = BicycleProfile.TREKKING,
        conditions: WeatherConditions? = null,
    ): String =
        json.encodeToString(
            ValhallaRequest.serializer(),
            ValhallaRequest(
                locations = request.waypoints.map { ValhallaLocation(it.latitude, it.longitude) },
                costingOptions = ValhallaCostingOptions(BicycleCosting.forRide(profile, conditions)),
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
 * Rower, na którym jedzie użytkownik.
 *
 * Trasa rowerowa to nie jedna kategoria: szosówka i rower górski jadące między tymi samymi
 * punktami powinny dostać różne trasy, bo co dla jednego jest skrótem, dla drugiego jest
 * końcem przejazdu. Profil wybiera użytkownik i to on ustala punkt wyjścia dla wag.
 *
 * @property valhallaType wartość pola `bicycle_type` w profilu rowerowym Valhalli
 * @property label nazwa do pokazania użytkownikowi
 */
enum class BicycleProfile(
    val valhallaType: String,
    val label: String,
    internal val avoidBadSurfaces: Double,
    internal val useRoads: Double,
    internal val useHills: Double,
) {
    /** Miejski: gładka nawierzchnia, drogi dla rowerów, płasko. */
    CITY("City", "Miejski", avoidBadSurfaces = 0.7, useRoads = 0.3, useHills = 0.25),

    /** Trekkingowy: kompromis — domyślny, bo znosi wszystko po trochu. */
    TREKKING("Hybrid", "Trekkingowy", avoidBadSurfaces = 0.25, useRoads = 0.4, useHills = 0.4),

    /** Górski: szuter i ścieżki są zaletą, podjazdy nie odstraszają. */
    MOUNTAIN("Mountain", "Górski", avoidBadSurfaces = 0.05, useRoads = 0.2, useHills = 0.6),

    /** Szosowy: asfalt albo nic. */
    ROAD("Road", "Szosowy", avoidBadSurfaces = 1.0, useRoads = 0.6, useHills = 0.4),
}

/**
 * Odwzorowanie profilu roweru i warunków pogodowych na opcje trasowania Valhalli.
 *
 * **To jest przybliżenie.** Model wag z sekcji 5 pozwala odstraszać dowolną nawierzchnię
 * z dowolną siłą; publiczna Valhalla przyjmuje wyłącznie kilka predefiniowanych pokręteł.
 * Odwzorowujemy więc intencję, nie wagi: „pada" znaczy tu „unikaj kiepskich nawierzchni",
 * a nie konkretny mnożnik dla błota. Pełny model wag wróci razem z silnikiem na urządzeniu.
 */
object BicycleCosting {

    /**
     * Łączy wybór użytkownika z pogodą.
     *
     * Pogoda działa **wyłącznie podwyższająco** — tak samo jak wagi krawędzi z ADR-0002.
     * Deszcz może kazać mocniej omijać błoto, ale nigdy nie rozluźni wymagań roweru
     * szosowego, bo to nie pogoda decyduje, jakie opony ma użytkownik.
     *
     * Nie zmieniamy też `bicycle_type`: wcześniej ulewa przestawiała go na `Road`, przez co
     * rower górski dostawał trasę dla szosówki. Rower użytkownika nie zmienia się od tego,
     * że zaczęło padać.
     */
    fun forRide(profile: BicycleProfile, conditions: WeatherConditions?): BicycleOptions {
        val floor = weatherFloor(conditions)
        return BicycleOptions(
            bicycleType = profile.valhallaType,
            avoidBadSurfaces = maxOf(profile.avoidBadSurfaces, floor.avoidBadSurfaces),
            useRoads = maxOf(profile.useRoads, floor.useRoads),
            useHills = profile.useHills,
        )
    }

    /** Dolna granica, poniżej której pogoda nie pozwala zejść. Brak danych = brak granicy. */
    private fun weatherFloor(conditions: WeatherConditions?): WeatherFloor = when {
        conditions == null -> WeatherFloor(0.0, 0.0)
        conditions.isHeavyRain -> WeatherFloor(avoidBadSurfaces = 1.0, useRoads = 0.6)
        conditions.isRaining -> WeatherFloor(avoidBadSurfaces = 0.8, useRoads = 0.5)
        conditions.isFreezing -> WeatherFloor(avoidBadSurfaces = 0.8, useRoads = 0.4)
        else -> WeatherFloor(0.0, 0.0)
    }

    private data class WeatherFloor(val avoidBadSurfaces: Double, val useRoads: Double)
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
