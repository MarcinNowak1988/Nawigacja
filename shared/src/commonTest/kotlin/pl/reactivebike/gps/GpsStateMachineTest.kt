package pl.reactivebike.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsStateMachineTest {

    /** Jazda w tempie marszowym, ekran włączony, brak manewru w zasięgu. */
    private val riding = RideSignals(
        distanceToManeuverMeters = 5_000.0,
        speedKmh = 20.0,
        screenOn = true,
        motionDetected = true,
    )

    @Test
    fun `nawigacja startuje w stanie CRUISE`() {
        assertEquals(GpsState.CRUISE, GpsStateMachine.INITIAL_STATE)
    }

    // --- częstotliwości z tabeli w sekcji 7 ---

    @Test
    fun `interwaly probkowania zgadzaja sie ze specyfikacja`() {
        assertEquals(1, GpsState.CRITICAL.samplingIntervalSeconds)
        assertEquals(5, GpsState.CRUISE.samplingIntervalSeconds)
        assertEquals(20, GpsState.SLEEP.samplingIntervalSeconds)
        assertNull(GpsState.STATIONARY.samplingIntervalSeconds)
    }

    @Test
    fun `czestotliwosci zgadzaja sie ze specyfikacja`() {
        assertEquals(1.0, GpsState.CRITICAL.frequencyHz)
        assertEquals(0.2, GpsState.CRUISE.frequencyHz)
        assertEquals(0.05, GpsState.SLEEP.frequencyHz)
        assertEquals(0.0, GpsState.STATIONARY.frequencyHz)
    }

    // --- przejścia z diagramu w sekcji 7 ---

    @Test
    fun `CRUISE przechodzi w CRITICAL gdy manewr jest blizej niz 300 m`() {
        val signals = riding.copy(distanceToManeuverMeters = 299.0)

        assertEquals(GpsState.CRITICAL, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    @Test
    fun `prog 300 m nie jest jeszcze manewrem w zasiegu`() {
        val signals = riding.copy(distanceToManeuverMeters = 300.0)

        assertEquals(GpsState.CRUISE, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    @Test
    fun `CRITICAL wraca do CRUISE po wykonaniu manewru`() {
        val signals = riding.copy(distanceToManeuverMeters = 1_200.0)

        assertEquals(GpsState.CRUISE, GpsStateMachine.next(GpsState.CRITICAL, signals))
    }

    @Test
    fun `CRITICAL wraca do CRUISE gdy nie ma juz zadnego manewru`() {
        val signals = riding.copy(distanceToManeuverMeters = null)

        assertEquals(GpsState.CRUISE, GpsStateMachine.next(GpsState.CRITICAL, signals))
    }

    @Test
    fun `CRITICAL utrzymuje sie dopoki manewr jest przed nami`() {
        val signals = riding.copy(distanceToManeuverMeters = 120.0)

        assertEquals(GpsState.CRITICAL, GpsStateMachine.next(GpsState.CRITICAL, signals))
    }

    @Test
    fun `CRUISE przechodzi w SLEEP po wygaszeniu ekranu`() {
        val signals = riding.copy(screenOn = false)

        assertEquals(GpsState.SLEEP, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    @Test
    fun `SLEEP wraca do CRUISE po wlaczeniu ekranu`() {
        val signals = riding.copy(screenOn = true)

        assertEquals(GpsState.CRUISE, GpsStateMachine.next(GpsState.SLEEP, signals))
    }

    @Test
    fun `SLEEP przechodzi w CRITICAL gdy predykcja ETA wskazuje zblizajacy sie manewr`() {
        val signals = riding.copy(screenOn = false, distanceToManeuverMeters = 250.0)

        assertEquals(GpsState.CRITICAL, GpsStateMachine.next(GpsState.SLEEP, signals))
    }

    @Test
    fun `CRUISE przechodzi w STATIONARY po zatrzymaniu`() {
        val signals = riding.copy(speedKmh = 0.0, motionDetected = false)

        assertEquals(GpsState.STATIONARY, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    @Test
    fun `SLEEP przechodzi w STATIONARY po zatrzymaniu`() {
        val signals = riding.copy(screenOn = false, speedKmh = 0.0, motionDetected = false)

        assertEquals(GpsState.STATIONARY, GpsStateMachine.next(GpsState.SLEEP, signals))
    }

    @Test
    fun `STATIONARY budzi sie do CRUISE na sygnal akcelerometru`() {
        val signals = RideSignals(speedKmh = 0.0, motionDetected = true, screenOn = false)

        assertEquals(GpsState.CRUISE, GpsStateMachine.next(GpsState.STATIONARY, signals))
    }

    @Test
    fun `STATIONARY trwa dopoki akcelerometr milczy`() {
        val signals = RideSignals(speedKmh = 0.0, motionDetected = false)

        assertEquals(GpsState.STATIONARY, GpsStateMachine.next(GpsState.STATIONARY, signals))
    }

    @Test
    fun `postoj nie budzi sie na sam odczyt predkosci z GPS`() {
        val signals = RideSignals(speedKmh = 25.0, motionDetected = false)

        assertEquals(GpsState.STATIONARY, GpsStateMachine.next(GpsState.STATIONARY, signals))
    }

    // --- pierwszeństwo reguł i ADR-0004 ---

    @Test
    fun `zblizajacy sie manewr ma pierwszenstwo przed wygaszonym ekranem`() {
        val signals = riding.copy(screenOn = false, distanceToManeuverMeters = 100.0)

        assertEquals(GpsState.CRITICAL, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    @Test
    fun `zblizajacy sie manewr ma pierwszenstwo przed postojem`() {
        val signals = riding.copy(
            distanceToManeuverMeters = 100.0,
            speedKmh = 0.0,
            motionDetected = false,
        )

        assertEquals(GpsState.CRITICAL, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    /**
     * ADR-0004: sama prędkość poniżej 3 km/h nie oznacza postoju. Rowerzysta na stromym
     * podjeździe jedzie wolniej niż próg, a wyłączenie GPS urwałoby mu nawigację.
     */
    @Test
    fun `wolny podjazd nie jest traktowany jak postoj`() {
        val signals = riding.copy(speedKmh = 2.5, motionDetected = true)

        assertEquals(GpsState.CRUISE, GpsStateMachine.next(GpsState.CRUISE, signals))
    }

    @Test
    fun `wolny podjazd przy wygaszonym ekranie zostaje w SLEEP`() {
        val signals = riding.copy(speedKmh = 2.5, motionDetected = true, screenOn = false)

        assertEquals(GpsState.SLEEP, GpsStateMachine.next(GpsState.SLEEP, signals))
    }

    // --- własności ogólne ---

    @Test
    fun `funkcja przejscia jest okreslona dla kazdego stanu`() {
        val signalVariants = listOf(
            RideSignals(),
            riding,
            riding.copy(screenOn = false),
            riding.copy(speedKmh = 0.0, motionDetected = false),
            riding.copy(distanceToManeuverMeters = 10.0),
            riding.copy(distanceToManeuverMeters = null, speedKmh = 0.0),
        )

        GpsState.entries.forEach { state ->
            signalVariants.forEach { signals ->
                GpsStateMachine.next(state, signals)
            }
        }
    }

    @Test
    fun `funkcja przejscia jest deterministyczna`() {
        val signals = riding.copy(distanceToManeuverMeters = 150.0)

        val results = List(10) { GpsStateMachine.next(GpsState.CRUISE, signals) }

        assertTrue(results.all { it == results.first() }, "otrzymano: $results")
    }
}
