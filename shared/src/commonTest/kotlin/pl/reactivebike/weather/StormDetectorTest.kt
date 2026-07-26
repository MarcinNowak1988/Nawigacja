package pl.reactivebike.weather

import pl.reactivebike.routing.EdgeWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class StormDetectorTest {

    private val detector = StormDetector()
    private val now = 1_800_000_000L

    private fun reading(hpa: Double, hoursAgo: Double) =
        PressureReading(hpa, atEpochSeconds = now - (hoursAgo * 3600).toLong())

    @Test
    fun `brak odczytow nie pozwala nic orzec`() {
        assertEquals(StormVerdict.InsufficientData, detector.evaluate(emptyList(), now))
    }

    @Test
    fun `pojedynczy odczyt nie pozwala nic orzec`() {
        val readings = listOf(reading(1013.0, 0.0))

        assertEquals(StormVerdict.InsufficientData, detector.evaluate(readings, now))
    }

    @Test
    fun `stabilne cisnienie to spokoj`() {
        val readings = listOf(
            reading(1013.0, 2.0),
            reading(1012.8, 1.0),
            reading(1013.1, 0.0),
        )

        assertEquals(StormVerdict.Calm, detector.evaluate(readings, now))
    }

    @Test
    fun `rosnace cisnienie to spokoj`() {
        val readings = listOf(
            reading(1005.0, 2.0),
            reading(1010.0, 1.0),
            reading(1015.0, 0.0),
        )

        assertEquals(StormVerdict.Calm, detector.evaluate(readings, now))
    }

    @Test
    fun `spadek ponad dwa hPa uruchamia alarm`() {
        val readings = listOf(
            reading(1013.0, 2.5),
            reading(1011.0, 1.0),
            reading(1010.5, 0.0),
        )

        val verdict = assertIs<StormVerdict.StormApproaching>(detector.evaluate(readings, now))

        assertEquals(2.5, verdict.dropHpa, absoluteTolerance = 1e-9)
    }

    @Test
    fun `spadek dokladnie o prog nie uruchamia alarmu`() {
        val readings = listOf(
            reading(1013.0, 2.0),
            reading(1011.0, 0.0),
        )

        assertEquals(StormVerdict.Calm, detector.evaluate(readings, now))
    }

    /** Spadek liczymy od szczytu w oknie, nie od odczytu najstarszego. */
    @Test
    fun `wzrost a potem gwaltowny spadek jest wykrywany`() {
        val readings = listOf(
            reading(1010.0, 2.5),
            reading(1014.0, 1.5),
            reading(1011.0, 0.0),
        )

        val verdict = assertIs<StormVerdict.StormApproaching>(detector.evaluate(readings, now))

        assertEquals(3.0, verdict.dropHpa, absoluteTolerance = 1e-9)
    }

    @Test
    fun `odczyty spoza okna sa pomijane`() {
        val readings = listOf(
            reading(1020.0, 10.0), // dawno temu, poza oknem 3h
            reading(1012.0, 1.0),
            reading(1011.5, 0.0),
        )

        assertEquals(StormVerdict.Calm, detector.evaluate(readings, now))
    }

    @Test
    fun `odczyty z przyszlosci sa pomijane`() {
        val readings = listOf(
            PressureReading(1000.0, atEpochSeconds = now + 3_600),
            reading(1013.0, 1.0),
            reading(1012.9, 0.0),
        )

        assertEquals(StormVerdict.Calm, detector.evaluate(readings, now))
    }

    @Test
    fun `kolejnosc odczytow na liscie nie ma znaczenia`() {
        val chronological = listOf(
            reading(1013.0, 2.0),
            reading(1011.0, 1.0),
            reading(1009.0, 0.0),
        )

        assertEquals(
            detector.evaluate(chronological, now),
            detector.evaluate(chronological.reversed(), now),
        )
    }

    @Test
    fun `prog i okno da sie skonfigurowac`() {
        val sensitive = StormDetector(dropThresholdHpa = 0.5, window = 1.hours)
        val readings = listOf(
            reading(1013.0, 0.5),
            reading(1012.0, 0.0),
        )

        assertIs<StormVerdict.StormApproaching>(sensitive.evaluate(readings, now))
        assertEquals(StormVerdict.Calm, detector.evaluate(readings, now))
    }

    @Test
    fun `wagi trybu burzowego sa wylacznie podwyzszajace`() {
        val all = StormMode.weights.surface.overrides.values + StormMode.weights.surface.default

        assertTrue(all.all { it >= EdgeWeight.NEUTRAL }, "otrzymano: $all")
    }

    @Test
    fun `tryb burzowy wyklucza bloto`() {
        assertTrue(StormMode.weights.surface.weightFor("mud").isImpassable())
    }

    @Test
    fun `tryb burzowy karze nawierzchnie podatne na rozmokniecie mocniej niz utwardzone`() {
        val ground = StormMode.weights.surface.weightFor("ground")
        val compacted = StormMode.weights.surface.weightFor("compacted")
        val asphalt = StormMode.weights.surface.weightFor("asphalt")

        assertTrue(ground > compacted, "ground $ground powinien byc gorszy niz compacted $compacted")
        assertTrue(compacted > asphalt, "compacted $compacted powinien byc gorszy niz asfalt $asphalt")
    }
}
