package pl.reactivebike.weather

import pl.reactivebike.routing.RoutingWeights
import pl.reactivebike.routing.WeightTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OfflineWeatherPolicyTest {

    private val policy = OfflineWeatherPolicy()
    private val now = 1_800_000_000L

    private val cachedWeights = RoutingWeights(surface = WeightTable(mapOf("gravel" to 3.0)))

    private fun cachedIssued(hoursAgo: Double) = CachedWeatherWeights(
        weights = cachedWeights,
        forecastIssuedAtEpochSeconds = now - (hoursAgo * 3600).toLong(),
    )

    private fun reading(hpa: Double, hoursAgo: Double) =
        PressureReading(hpa, atEpochSeconds = now - (hoursAgo * 3600).toLong())

    private val calmPressure = listOf(reading(1013.0, 2.0), reading(1013.2, 0.0))
    private val fallingPressure = listOf(reading(1013.0, 2.0), reading(1009.0, 0.0))

    @Test
    fun `wazny bufor bez burzy daje wagi zbuforowane`() {
        val source = assertIs<WeatherWeightsSource.Cached>(
            policy.resolve(cachedIssued(1.0), calmPressure, now),
        )

        assertEquals(cachedWeights, source.weights)
    }

    @Test
    fun `wygasly bufor bez burzy daje wagi domyslne`() {
        val source = assertIs<WeatherWeightsSource.Default>(
            policy.resolve(cachedIssued(5.0), calmPressure, now),
        )

        assertEquals(RoutingWeights.DEFAULT, source.weights)
    }

    @Test
    fun `pusty bufor bez burzy daje wagi domyslne`() {
        assertIs<WeatherWeightsSource.Default>(policy.resolve(null, calmPressure, now))
    }

    @Test
    fun `brak odczytow barometru nie przeszkadza korzystac z bufora`() {
        assertIs<WeatherWeightsSource.Cached>(policy.resolve(cachedIssued(1.0), emptyList(), now))
    }

    /**
     * Sedno ADR-0005: barometr jest nasłuchiwany **równolegle** z korzystaniem z bufora.
     * Front nadciągający w pierwszej godzinie offline musi zostać wykryty, mimo że
     * prognoza jest jeszcze ważna.
     */
    @Test
    fun `burza jest wykrywana mimo wciaz waznego bufora`() {
        val source = assertIs<WeatherWeightsSource.StormMode>(
            policy.resolve(cachedIssued(0.5), fallingPressure, now),
        )

        assertEquals(StormMode.weights, source.weights)
        assertEquals(4.0, source.dropHpa, absoluteTolerance = 1e-9)
    }

    @Test
    fun `burza ma pierwszenstwo takze przy wygaslym buforze`() {
        assertIs<WeatherWeightsSource.StormMode>(
            policy.resolve(cachedIssued(9.0), fallingPressure, now),
        )
    }

    @Test
    fun `burza ma pierwszenstwo przy pustym buforze`() {
        assertIs<WeatherWeightsSource.StormMode>(policy.resolve(null, fallingPressure, now))
    }

    @Test
    fun `kazde rozstrzygniecie daje wagi gotowe do uzycia`() {
        val scenarios = listOf(
            Triple(cachedIssued(1.0), calmPressure, "swiezy bufor"),
            Triple(cachedIssued(9.0), calmPressure, "wygasly bufor"),
            Triple(null, emptyList(), "brak danych"),
            Triple(null, fallingPressure, "burza"),
        )

        scenarios.forEach { (cache, readings, opis) ->
            val weights = policy.resolve(cache, readings, now).weights
            val all = listOf(weights.surface, weights.infrastructure)
                .flatMap { it.overrides.values + it.default }

            assertEquals(all, all.filter { it >= 1.0 }, "scenariusz: $opis")
        }
    }
}
