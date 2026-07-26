package pl.reactivebike.weather

import pl.reactivebike.routing.Edge
import pl.reactivebike.routing.EdgeCost
import pl.reactivebike.routing.EdgeCostCalculator
import pl.reactivebike.routing.EdgeWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalWeightsTranslatorTest {

    private val dry = WeatherConditions(temperatureCelsius = 18.0, precipitationMm = 0.0)
    private val rain = WeatherConditions(temperatureCelsius = 12.0, precipitationMm = 1.0)
    private val downpour = WeatherConditions(temperatureCelsius = 10.0, precipitationMm = 6.0)
    private val frost = WeatherConditions(temperatureCelsius = -2.0, precipitationMm = 0.0)

    private fun weightOf(conditions: WeatherConditions, surface: String) =
        LocalWeightsTranslator.translate(conditions).surface.weightFor(surface)

    @Test
    fun `kazdy zestaw wag jest wylacznie podwyzszajacy`() {
        listOf(dry, rain, downpour, frost).forEach { conditions ->
            val weights = LocalWeightsTranslator.translate(conditions)
            val all = weights.surface.overrides.values + weights.surface.default

            assertTrue(all.all { it >= EdgeWeight.NEUTRAL }, "warunki $conditions daly wagi $all")
        }
    }

    @Test
    fun `sucho nie odstrasza nawierzchni utwardzonych`() {
        assertEquals(EdgeWeight.NEUTRAL, weightOf(dry, "asphalt"))
        assertEquals(EdgeWeight.NEUTRAL, weightOf(dry, "gravel"))
    }

    @Test
    fun `bloto jest odstraszane nawet przy suchej pogodzie`() {
        assertTrue(weightOf(dry, "mud") > EdgeWeight.NEUTRAL)
    }

    @Test
    fun `deszcz odstrasza miekkie nawierzchnie`() {
        assertTrue(weightOf(rain, "ground") > weightOf(dry, "ground"))
        assertTrue(weightOf(rain, "grass") > EdgeWeight.NEUTRAL)
    }

    @Test
    fun `deszcz nie odstrasza asfaltu`() {
        assertEquals(EdgeWeight.NEUTRAL, weightOf(rain, "asphalt"))
    }

    @Test
    fun `ulewa wyklucza bloto`() {
        assertTrue(weightOf(downpour, "mud").isImpassable())
    }

    @Test
    fun `podczas ulewy bloto jest nieprzejezdne dla kalkulatora kosztu`() {
        val calculator = EdgeCostCalculator(LocalWeightsTranslator.translate(downpour))

        val cost = calculator.costOf(Edge(distanceMeters = 100.0, surface = "mud"))

        assertIs<EdgeCost.Impassable>(cost)
    }

    @Test
    fun `ulewa odstrasza mocniej niz zwykly deszcz`() {
        assertTrue(weightOf(downpour, "ground") > weightOf(rain, "ground"))
        assertTrue(weightOf(downpour, "gravel") > weightOf(rain, "gravel"))
    }

    @Test
    fun `podczas deszczu bloto jest kosztowne ale wciaz przejezdne`() {
        val calculator = EdgeCostCalculator(LocalWeightsTranslator.translate(rain))

        val cost = calculator.costOf(Edge(distanceMeters = 100.0, surface = "mud"))

        assertIs<EdgeCost.Passable>(cost)
    }

    @Test
    fun `mroz odstrasza sliskie nawierzchnie`() {
        assertTrue(weightOf(frost, "sett") > EdgeWeight.NEUTRAL)
        assertTrue(weightOf(frost, "metal") > EdgeWeight.NEUTRAL)
        assertTrue(weightOf(frost, "wood") > EdgeWeight.NEUTRAL)
    }

    @Test
    fun `mroz nie odstrasza asfaltu`() {
        assertEquals(EdgeWeight.NEUTRAL, weightOf(frost, "asphalt"))
    }

    @Test
    fun `mroz z deszczem laczy oba zestawy regul`() {
        val icyRain = WeatherConditions(temperatureCelsius = 0.0, precipitationMm = 1.0)

        assertTrue(weightOf(icyRain, "ground") > EdgeWeight.NEUTRAL)
        assertTrue(weightOf(icyRain, "sett") > EdgeWeight.NEUTRAL)
    }

    @Test
    fun `brak danych pogodowych daje wagi jak przy suchej pogodzie`() {
        val unknown = WeatherConditions()

        assertEquals(EdgeWeight.NEUTRAL, weightOf(unknown, "asphalt"))
        assertTrue(weightOf(unknown, "mud") > EdgeWeight.NEUTRAL)
    }

    @Test
    fun `tlumaczenie jest deterministyczne`() {
        val first = LocalWeightsTranslator.translate(downpour)
        val second = LocalWeightsTranslator.translate(downpour)

        assertEquals(first, second)
    }

    // --- uzasadnienia ---

    @Test
    fun `kazde warunki maja uzasadnienie`() {
        listOf(dry, rain, downpour, frost, WeatherConditions()).forEach { conditions ->
            assertTrue(LocalWeightsTranslator.explain(conditions).isNotBlank(), "$conditions")
        }
    }

    @Test
    fun `uzasadnienia roznia sie miedzy warunkami`() {
        val explanations = listOf(dry, rain, downpour, frost).map { LocalWeightsTranslator.explain(it) }

        assertEquals(explanations.size, explanations.toSet().size, "otrzymano: $explanations")
    }
}
