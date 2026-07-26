package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EdgeCostTest {

    @Test
    fun `bez wag koszt rowna sie dystansowi`() {
        val cost = EdgeCostCalculator().costOf(Edge(distanceMeters = 1_000.0))

        assertEquals(1_000.0, assertIs<EdgeCost.Passable>(cost).cost)
    }

    @Test
    fun `koszt mnozy wszystkie cztery skladniki wzoru z sekcji 5_1`() {
        val calculator = EdgeCostCalculator(
            RoutingWeights(
                surface = WeightTable(mapOf("gravel" to 2.0)),
                infrastructure = WeightTable(mapOf("path" to 1.5)),
            ),
        )

        val cost = calculator.costOf(
            Edge(
                distanceMeters = 100.0,
                surface = "gravel",
                infrastructure = "path",
                topography = 3.0,
            ),
        )

        // 100 * 2.0 * 1.5 * 3.0
        assertEquals(900.0, assertIs<EdgeCost.Passable>(cost).cost)
    }

    @Test
    fun `nieznana nawierzchnia dostaje wage domyslna`() {
        val calculator = EdgeCostCalculator(
            RoutingWeights(surface = WeightTable(mapOf("mud" to 4.0), default = 2.0)),
        )

        val cost = calculator.costOf(Edge(distanceMeters = 10.0, surface = "kostka-brukowa"))

        assertEquals(20.0, assertIs<EdgeCost.Passable>(cost).cost)
    }

    @Test
    fun `krawedz bez atrybutow dostaje wage domyslna`() {
        val calculator = EdgeCostCalculator(
            RoutingWeights(surface = WeightTable(mapOf("mud" to 4.0), default = 2.5)),
        )

        val cost = calculator.costOf(Edge(distanceMeters = 10.0, surface = null))

        assertEquals(25.0, assertIs<EdgeCost.Passable>(cost).cost)
    }

    @Test
    fun `bloto wyklucza krawedz zamiast czynic ja tylko droga`() {
        val calculator = EdgeCostCalculator(
            RoutingWeights(surface = WeightTable(mapOf("mud" to EdgeWeight.IMPASSABLE))),
        )

        val cost = calculator.costOf(Edge(distanceMeters = 1.0, surface = "mud"))

        assertIs<EdgeCost.Impassable>(cost)
    }

    @Test
    fun `zakaz na infrastrukturze rowniez wyklucza krawedz`() {
        val calculator = EdgeCostCalculator(
            RoutingWeights(infrastructure = WeightTable(mapOf("steps" to EdgeWeight.IMPASSABLE))),
        )

        val cost = calculator.costOf(Edge(distanceMeters = 1.0, infrastructure = "steps"))

        assertIs<EdgeCost.Impassable>(cost)
    }

    /**
     * Przykład z sekcji 5.3 specyfikacji — po korekcie z ADR-0002.
     *
     * Model AI opisuje ulewę jako `mud = 999.0`, `asphalt = 0.5`, `cycleway = 0.5`.
     * Po normalizacji do postaci wyłącznie podwyższającej krawędź asfaltowej ścieżki
     * rowerowej ma być **czterokrotnie tańsza** od krawędzi domyślnej o tej samej długości —
     * dokładnie tak, jak zapowiada oryginalny zapis `0.5 x 0.5 = 0.25`.
     */
    @Test
    fun `po normalizacji asfaltowa sciezka jest czterokrotnie tansza od domyslnej`() {
        val weights = RoutingWeights(
            surface = WeightTable(mapOf("mud" to EdgeWeight.IMPASSABLE, "asphalt" to 0.5)),
            infrastructure = WeightTable(mapOf("cycleway" to 0.5)),
        ).normalizedToIncreaseOnly()

        val calculator = EdgeCostCalculator(weights)

        val cycleway = calculator.costOf(
            Edge(distanceMeters = 1_000.0, surface = "asphalt", infrastructure = "cycleway"),
        )
        val plainRoad = calculator.costOf(Edge(distanceMeters = 1_000.0))

        val cyclewayCost = assertIs<EdgeCost.Passable>(cycleway).cost
        val plainRoadCost = assertIs<EdgeCost.Passable>(plainRoad).cost

        assertEquals(0.25, cyclewayCost / plainRoadCost, absoluteTolerance = 1e-9)
    }

    @Test
    fun `normalizacja nie odblokowuje krawedzi zakazanych`() {
        val weights = RoutingWeights(
            surface = WeightTable(mapOf("mud" to EdgeWeight.IMPASSABLE, "asphalt" to 0.5)),
        ).normalizedToIncreaseOnly()

        val cost = EdgeCostCalculator(weights).costOf(Edge(distanceMeters = 1.0, surface = "mud"))

        assertIs<EdgeCost.Impassable>(cost)
    }

    @Test
    fun `po normalizacji zadna waga nie spada ponizej neutralnej`() {
        val weights = RoutingWeights(
            surface = WeightTable(mapOf("mud" to EdgeWeight.IMPASSABLE, "asphalt" to 0.5)),
            infrastructure = WeightTable(mapOf("cycleway" to 0.25)),
        ).normalizedToIncreaseOnly()

        val allWeights = listOf(weights.surface, weights.infrastructure)
            .flatMap { it.overrides.values + it.default }

        assertTrue(allWeights.all { it >= EdgeWeight.NEUTRAL }, "otrzymano: $allWeights")
    }

    @Test
    fun `normalizacja nie rusza tablicy juz podwyzszajacej`() {
        val table = WeightTable(mapOf("mud" to 5.0, "gravel" to 2.0))

        assertEquals(table, table.normalizedToIncreaseOnly())
    }

    @Test
    fun `normalizacja zachowuje uporzadkowanie wag`() {
        val normalized = WeightTable(
            mapOf("asphalt" to 0.5, "gravel" to 0.8, "sand" to 4.0),
        ).normalizedToIncreaseOnly()

        val asphalt = normalized.weightFor("asphalt")
        val gravel = normalized.weightFor("gravel")
        val sand = normalized.weightFor("sand")

        assertTrue(asphalt < gravel && gravel < sand, "otrzymano: $asphalt, $gravel, $sand")
    }
}
