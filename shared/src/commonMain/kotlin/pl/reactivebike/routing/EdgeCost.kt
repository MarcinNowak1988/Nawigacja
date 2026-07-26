package pl.reactivebike.routing

/**
 * Krawędź grafu OpenStreetMap sprowadzona do atrybutów, których wymaga funkcja kosztu.
 *
 * @property distanceMeters długość krawędzi w metrach
 * @property surface typ nawierzchni (np. `asphalt`, `gravel`, `mud`); `null` gdy nieotagowana
 * @property infrastructure typ infrastruktury (np. `cycleway`); `null` gdy nieotagowana
 * @property topography współczynnik ukształtowania terenu; [EdgeWeight.NEUTRAL] dla terenu płaskiego
 */
data class Edge(
    val distanceMeters: Double,
    val surface: String? = null,
    val infrastructure: String? = null,
    val topography: Double = EdgeWeight.NEUTRAL,
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Dystans musi byc nieujemny i skonczony, otrzymano: $distanceMeters"
        }
        require(topography.isFinite() && topography > 0.0) {
            "Wspolczynnik topografii musi byc dodatni i skonczony, otrzymano: $topography"
        }
    }
}

/** Wynik wyceny krawędzi. */
sealed interface EdgeCost {

    /** Krawędź przejezdna o wyliczonym [cost]. */
    data class Passable(val cost: Double) : EdgeCost

    /**
     * Krawędź wykluczona z trasowania.
     *
     * Celowo osobny wariant, a nie „bardzo wysoki koszt": [EdgeWeight.IMPASSABLE] jest
     * sentinelem oznaczającym zakaz wjazdu. Gdyby traktować go jak zwykłą liczbę,
     * algorytm poprowadziłby przez zakazany segment, o ile objazd byłby jeszcze droższy.
     */
    data object Impassable : EdgeCost
}

/**
 * Funkcja kosztu krawędzi z sekcji 5.1 specyfikacji:
 *
 * ```
 * Koszt Krawedzi = Dystans * Waga Nawierzchni * Waga Infrastruktury * Topografia
 * ```
 */
class EdgeCostCalculator(
    private val weights: RoutingWeights = RoutingWeights.DEFAULT,
) {

    fun costOf(edge: Edge): EdgeCost {
        val surfaceWeight = weights.surface.weightFor(edge.surface)
        val infrastructureWeight = weights.infrastructure.weightFor(edge.infrastructure)

        if (surfaceWeight >= EdgeWeight.IMPASSABLE || infrastructureWeight >= EdgeWeight.IMPASSABLE) {
            return EdgeCost.Impassable
        }

        return EdgeCost.Passable(
            edge.distanceMeters * surfaceWeight * infrastructureWeight * edge.topography,
        )
    }
}
