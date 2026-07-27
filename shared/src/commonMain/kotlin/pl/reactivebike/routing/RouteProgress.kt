package pl.reactivebike.routing

/**
 * Położenie rowerzysty względem wyznaczonej trasy.
 *
 * @property nearestIndex indeks najbliższego punktu geometrii trasy
 * @property distanceFromRouteMeters odległość od trasy — duża wartość oznacza zjechanie z trasy
 * @property nextManeuver najbliższy manewr przed nami; `null` gdy trasa się kończy
 * @property distanceToNextManeuverMeters odległość do tego manewru wzdłuż trasy
 * @property remainingDistanceMeters ile jeszcze zostało do celu
 * @property traveledDistanceMeters ile trasy jest już za nami, licząc wzdłuż niej
 */
data class RouteProgress(
    val nearestIndex: Int,
    val distanceFromRouteMeters: Double,
    val nextManeuver: Maneuver?,
    val distanceToNextManeuverMeters: Double?,
    val remainingDistanceMeters: Double,
    val traveledDistanceMeters: Double = 0.0,
) {

    /** Długość trasy widziana z perspektywy postępu — suma tego, co za nami i przed nami. */
    val totalDistanceMeters: Double get() = traveledDistanceMeters + remainingDistanceMeters

    /**
     * Ułamek trasy za nami, w zakresie 0..1.
     *
     * Trasa zerowej długości daje 0.0, a nie dzielenie przez zero — zdarza się przy trasie
     * do punktu, na którym już stoimy.
     */
    val completedFraction: Double
        get() = if (totalDistanceMeters <= 0.0) 0.0 else traveledDistanceMeters / totalDistanceMeters
}

/**
 * Liczy postęp na trasie — czysta funkcja pozycji i trasy.
 *
 * To stąd bierze się `distanceToManeuverMeters` podawane maszynie stanów GPS, czyli
 * jedyne źródło przejścia w stan `CRITICAL` z sekcji 7. Bez wyznaczonej trasy ten stan
 * jest nieosiągalny, co było widać w wersjach bez trasowania.
 *
 * Odległości liczone są **wzdłuż trasy**, a nie w linii prostej: rowerzysta przed zakrętem
 * pod kątem prostym jest bliżej manewru po prostej niż po drodze, a liczy się to drugie.
 */
object RouteTracker {

    /** Powyżej tej odległości od trasy uznajemy, że rowerzysta z niej zjechał. */
    const val OFF_ROUTE_THRESHOLD_METERS = 50.0

    fun progress(route: Route, position: GeoPoint): RouteProgress? {
        val geometry = route.geometry
        if (geometry.isEmpty()) return null

        var nearestIndex = 0
        var nearestDistance = Double.MAX_VALUE
        geometry.forEachIndexed { index, point ->
            val distance = position.distanceTo(point)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }

        val nextManeuver = route.maneuvers.firstOrNull { it.beginShapeIndex > nearestIndex }
        val distanceToManeuver = nextManeuver?.let {
            distanceAlong(geometry, nearestIndex, it.beginShapeIndex.coerceAtMost(geometry.lastIndex))
        }

        return RouteProgress(
            nearestIndex = nearestIndex,
            distanceFromRouteMeters = nearestDistance,
            nextManeuver = nextManeuver,
            distanceToNextManeuverMeters = distanceToManeuver,
            remainingDistanceMeters = distanceAlong(geometry, nearestIndex, geometry.lastIndex),
            traveledDistanceMeters = distanceAlong(geometry, 0, nearestIndex),
        )
    }

    /** Czy rowerzysta oddalił się od trasy na tyle, że trzeba ją wyznaczyć ponownie. */
    fun isOffRoute(progress: RouteProgress): Boolean =
        progress.distanceFromRouteMeters > OFF_ROUTE_THRESHOLD_METERS

    private fun distanceAlong(geometry: List<GeoPoint>, fromIndex: Int, toIndex: Int): Double {
        if (fromIndex >= toIndex) return 0.0
        var total = 0.0
        for (i in fromIndex until toIndex) {
            total += geometry[i].distanceTo(geometry[i + 1])
        }
        return total
    }
}
