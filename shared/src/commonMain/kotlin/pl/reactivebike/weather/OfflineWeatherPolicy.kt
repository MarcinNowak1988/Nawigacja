package pl.reactivebike.weather

import pl.reactivebike.routing.RoutingWeights

/** Źródło wag obowiązujących w danej chwili, wraz z uzasadnieniem wyboru. */
sealed interface WeatherWeightsSource {

    val weights: RoutingWeights

    /** Wykryto nadciągającą burzę — wagi trybu ucieczki mają pierwszeństwo. */
    data class StormMode(
        override val weights: RoutingWeights,
        val dropHpa: Double,
    ) : WeatherWeightsSource

    /** Brak burzy, bufor pogodowy wciąż ważny. */
    data class Cached(override val weights: RoutingWeights) : WeatherWeightsSource

    /** Bufor pusty albo przeterminowany, barometr nie zgłasza burzy — jedziemy na wagach domyślnych. */
    data class Default(
        override val weights: RoutingWeights = RoutingWeights.DEFAULT,
    ) : WeatherWeightsSource
}

/**
 * Rozstrzyga, jakie wagi obowiązują po utracie zasięgu (sekcja 8 specyfikacji).
 *
 * **Barometr jest nasłuchiwany równolegle z korzystaniem z bufora, a nie dopiero po jego
 * wygaśnięciu.** Diagram w pierwszej wersji specyfikacji uruchamiał nasłuch dopiero po
 * przeterminowaniu prognozy, przez co front nadciągający w pierwszych godzinach offline
 * pozostawał niezauważony. Odczyt barometru nie wymaga sieci i jest tani, więc nie ma
 * powodu, by z niego wtedy rezygnować — patrz ADR-0005.
 *
 * Pierwszeństwo:
 * 1. **burza z barometru** — sygnał z bieżącego pomiaru bije prognozę sprzed godzin,
 * 2. **ważny bufor pogodowy**,
 * 3. **wagi domyślne** — nawigacja jedzie dalej, tylko bez adaptacji do pogody.
 */
class OfflineWeatherPolicy(
    private val cachePolicy: WeatherCachePolicy = WeatherCachePolicy(),
    private val stormDetector: StormDetector = StormDetector(),
) {

    fun resolve(
        cached: CachedWeatherWeights?,
        pressureReadings: List<PressureReading>,
        nowEpochSeconds: Long,
    ): WeatherWeightsSource {
        val verdict = stormDetector.evaluate(pressureReadings, nowEpochSeconds)
        if (verdict is StormVerdict.StormApproaching) {
            return WeatherWeightsSource.StormMode(
                weights = StormMode.weights,
                dropHpa = verdict.dropHpa,
            )
        }

        return when (val status = cachePolicy.statusOf(cached, nowEpochSeconds)) {
            is WeatherCacheStatus.Fresh -> WeatherWeightsSource.Cached(status.weights)
            is WeatherCacheStatus.Expired, WeatherCacheStatus.Empty -> WeatherWeightsSource.Default()
        }
    }
}
