package pl.reactivebike.weather

import pl.reactivebike.routing.RoutingWeights
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Zbuforowany wynik ostatniego udanego zapytania o pogodę.
 *
 * Buforujemy **wagi**, a nie surowe dane pogodowe, bo model AI tłumaczący pogodę na wagi
 * też jest usługą sieciową (sekcja 4). Offline niedostępne są oba, więc jedyne, co da się
 * wtedy wykorzystać, to ostatni policzony komplet wag.
 *
 * @property weights wagi przyjęte przez [WeatherWeightsValidator], już znormalizowane
 * @property forecastIssuedAtEpochSeconds moment, **na który wydano prognozę** — nie moment
 *   jej pobrania ani utraty zasięgu; patrz [WeatherCachePolicy]
 */
data class CachedWeatherWeights(
    val weights: RoutingWeights,
    val forecastIssuedAtEpochSeconds: Long,
)

/** Stan bufora pogodowego w danej chwili. */
sealed interface WeatherCacheStatus {

    /** Bufor ważny — [weights] nadają się do użycia, [age] mówi, jak stara jest prognoza. */
    data class Fresh(val weights: RoutingWeights, val age: Duration) : WeatherCacheStatus

    /** Prognoza przeterminowana; [age] podane dla celów diagnostycznych. */
    data class Expired(val age: Duration) : WeatherCacheStatus

    /** Bufor pusty — nie było jeszcze żadnego udanego zapytania. */
    data object Empty : WeatherCacheStatus
}

/**
 * Polityka ważności zbuforowanej prognozy (sekcja 8 specyfikacji).
 *
 * **Ważność liczona jest od momentu wydania prognozy, nie od utraty zasięgu.** Pierwsza
 * wersja specyfikacji mówiła o „2 godzinach od utraty sieci", co dawało prognozie sprzed
 * wielu godzin świeży dwugodzinny kredyt zaufania w chwili wjazdu w las. Przy liczeniu od
 * wydania dane użyte offline nigdy nie są starsze niż [validity], niezależnie od tego,
 * kiedy zniknął zasięg.
 */
class WeatherCachePolicy(
    private val validity: Duration = DEFAULT_VALIDITY,
) {

    fun statusOf(entry: CachedWeatherWeights?, nowEpochSeconds: Long): WeatherCacheStatus {
        if (entry == null) return WeatherCacheStatus.Empty

        val age = (nowEpochSeconds - entry.forecastIssuedAtEpochSeconds).seconds

        // Prognoza wydana „w przyszłości" oznacza rozjechany zegar urządzenia albo
        // prognozę na później. W obu wypadkach jest to najświeższe, co mamy — wiek zerowy.
        val effectiveAge = if (age.isNegative()) Duration.ZERO else age

        return if (effectiveAge <= validity) {
            WeatherCacheStatus.Fresh(entry.weights, effectiveAge)
        } else {
            WeatherCacheStatus.Expired(effectiveAge)
        }
    }

    companion object {
        /** Okno ważności prognozy wskazane w sekcji 8 specyfikacji. */
        val DEFAULT_VALIDITY: Duration = 2.hours
    }
}
