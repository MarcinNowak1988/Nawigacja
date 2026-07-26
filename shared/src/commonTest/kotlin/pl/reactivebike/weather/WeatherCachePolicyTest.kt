package pl.reactivebike.weather

import pl.reactivebike.routing.RoutingWeights
import pl.reactivebike.routing.WeightTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class WeatherCachePolicyTest {

    private val policy = WeatherCachePolicy()
    private val now = 1_800_000_000L
    private val weights = RoutingWeights(surface = WeightTable(mapOf("mud" to 999.0)))

    private fun cachedIssued(secondsAgo: Long) =
        CachedWeatherWeights(weights, forecastIssuedAtEpochSeconds = now - secondsAgo)

    @Test
    fun `pusty bufor jest rozpoznawany`() {
        assertEquals(WeatherCacheStatus.Empty, policy.statusOf(null, now))
    }

    @Test
    fun `swieza prognoza jest wazna`() {
        val status = assertIs<WeatherCacheStatus.Fresh>(policy.statusOf(cachedIssued(60), now))

        assertEquals(weights, status.weights)
        assertEquals(1.minutes, status.age)
    }

    @Test
    fun `prognoza tuz przed progiem jest jeszcze wazna`() {
        val status = policy.statusOf(cachedIssued(2 * 3600 - 1), now)

        assertIs<WeatherCacheStatus.Fresh>(status)
    }

    @Test
    fun `prognoza dokladnie na progu jest jeszcze wazna`() {
        val status = policy.statusOf(cachedIssued(2 * 3600), now)

        assertIs<WeatherCacheStatus.Fresh>(status)
    }

    @Test
    fun `prognoza starsza niz dwie godziny wygasa`() {
        val status = assertIs<WeatherCacheStatus.Expired>(policy.statusOf(cachedIssued(3 * 3600), now))

        assertEquals(3.hours, status.age)
    }

    /**
     * Sedno korekty sekcji 8: ważność liczy się od **wydania prognozy**, nie od utraty
     * zasięgu. Prognoza sprzed pięciu godzin jest przeterminowana niezależnie od tego,
     * kiedy zniknął zasięg.
     */
    @Test
    fun `stara prognoza nie odzyskuje waznosci przez sam fakt utraty zasiegu`() {
        val status = assertIs<WeatherCacheStatus.Expired>(policy.statusOf(cachedIssued(5 * 3600), now))

        assertEquals(5.hours, status.age)
    }

    @Test
    fun `prognoza wydana w przyszlosci jest traktowana jak najswiezsza`() {
        val future = CachedWeatherWeights(weights, forecastIssuedAtEpochSeconds = now + 3_600)

        val status = assertIs<WeatherCacheStatus.Fresh>(policy.statusOf(future, now))

        assertEquals(kotlin.time.Duration.ZERO, status.age)
    }

    @Test
    fun `okno waznosci da sie skonfigurowac`() {
        val strict = WeatherCachePolicy(validity = 30.minutes)

        assertIs<WeatherCacheStatus.Fresh>(strict.statusOf(cachedIssued(29 * 60), now))
        assertIs<WeatherCacheStatus.Expired>(strict.statusOf(cachedIssued(31 * 60), now))
    }
}
