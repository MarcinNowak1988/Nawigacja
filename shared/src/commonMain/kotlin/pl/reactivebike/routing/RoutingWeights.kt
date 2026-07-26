package pl.reactivebike.routing

/**
 * Stałe modelu wag krawędzi grafu (sekcja 5.2 specyfikacji).
 *
 * Model jest **wyłącznie podwyższający**: każdy mnożnik jest >= [NEUTRAL].
 * Powód w ADR-0002 — GraphHopper przy zmianie custom modelu w runtime z włączonym
 * speedupem Landmarks dopuszcza wyłącznie podwyższanie wag krawędzi, nigdy obniżanie.
 */
object EdgeWeight {

    /** Waga neutralna — segment bez modyfikacji względem dystansu bazowego. */
    const val NEUTRAL: Double = 1.0

    /** Wartość sentinel oznaczająca całkowity zakaz wjazdu. */
    const val IMPASSABLE: Double = 999.0
}

/**
 * Tablica wag dla jednego wymiaru kosztu — nawierzchni albo infrastruktury.
 *
 * @property overrides mnożniki dla rozpoznanych kluczy (np. `mud`, `asphalt`)
 * @property default mnożnik dla kluczy spoza [overrides] oraz dla krawędzi bez danego atrybutu
 */
data class WeightTable(
    val overrides: Map<String, Double> = emptyMap(),
    val default: Double = EdgeWeight.NEUTRAL,
) {
    init {
        require(default.isFinite() && default > 0.0) {
            "Waga domyslna musi byc dodatnia i skonczona, otrzymano: $default"
        }
        require(overrides.values.all { it.isFinite() && it > 0.0 }) {
            "Wszystkie mnozniki musza byc dodatnie i skonczone, otrzymano: $overrides"
        }
    }

    /** Mnożnik dla podanego klucza; [default] gdy klucz jest nieznany lub nieobecny. */
    fun weightFor(key: String?): Double = key?.let { overrides[it] } ?: default

    /**
     * Sprowadza tablicę do postaci wyłącznie podwyższającej wymaganej przez ADR-0002.
     *
     * Skalowanie wszystkich wag jednego wymiaru przez tę samą stałą nie zmienia trasy
     * optymalnej — mnożnik z tego wymiaru wchodzi do kosztu **każdej** krawędzi, więc
     * koszty całych tras rosną proporcjonalnie i ich uporządkowanie zostaje zachowane.
     * Wystarczy więc przeskalować tablicę tak, by jej minimum wynosiło [EdgeWeight.NEUTRAL].
     *
     * Wagi równe [EdgeWeight.IMPASSABLE] są sentinelem, nie liczbą — nie biorą udziału
     * w wyznaczaniu minimum i nie są skalowane.
     */
    fun normalizedToIncreaseOnly(): WeightTable {
        val scalable = overrides.values.filter { it < EdgeWeight.IMPASSABLE } + default
        val minimum = scalable.min()
        if (minimum >= EdgeWeight.NEUTRAL) return this

        val scale = EdgeWeight.NEUTRAL / minimum
        return WeightTable(
            overrides = overrides.mapValues { (_, weight) ->
                if (weight >= EdgeWeight.IMPASSABLE) EdgeWeight.IMPASSABLE else weight * scale
            },
            default = default * scale,
        )
    }
}

/**
 * Komplet wag przekazywany silnikowi trasowania.
 *
 * To jest **kontrakt warstwy wspólnej**: `commonMain` posiada model kosztu jako dane,
 * a każda platforma tłumaczy je na format swojego silnika (ADR-0001). Sam silnik
 * trasowania nie jest kodem wspólnym.
 */
data class RoutingWeights(
    val surface: WeightTable = WeightTable(),
    val infrastructure: WeightTable = WeightTable(),
) {

    /** Sprowadza oba wymiary do postaci wyłącznie podwyższającej (ADR-0002). */
    fun normalizedToIncreaseOnly(): RoutingWeights = RoutingWeights(
        surface = surface.normalizedToIncreaseOnly(),
        infrastructure = infrastructure.normalizedToIncreaseOnly(),
    )

    companion object {
        /** Wagi domyślne — brak modyfikacji, trasa liczona po dystansie i topografii. */
        val DEFAULT: RoutingWeights = RoutingWeights()
    }
}
