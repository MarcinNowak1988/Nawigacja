package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManeuverAnnouncerTest {

    private val turn = Maneuver("Skręć w prawo", 200.0, 60, beginShapeIndex = 5)
    private val nextTurn = Maneuver("Skręć w lewo", 150.0, 45, beginShapeIndex = 12)

    private fun progress(distance: Double?, maneuver: Maneuver? = turn) = RouteProgress(
        nearestIndex = 0,
        distanceFromRouteMeters = 3.0,
        nextManeuver = maneuver,
        distanceToNextManeuverMeters = distance,
        remainingDistanceMeters = 1_000.0,
    )

    @Test
    fun `daleko od manewru nie ma czego zapowiadac`() {
        assertNull(ManeuverAnnouncer().announce(progress(800.0)))
    }

    @Test
    fun `brak manewru to brak zapowiedzi`() {
        assertNull(ManeuverAnnouncer().announce(progress(100.0, maneuver = null)))
    }

    @Test
    fun `brak odleglosci to brak zapowiedzi`() {
        assertNull(ManeuverAnnouncer().announce(progress(null)))
    }

    @Test
    fun `pierwszy prog zapowiada dystans i instrukcje`() {
        val announcement = assertNotNull(ManeuverAnnouncer().announce(progress(280.0)))

        assertTrue(announcement.contains("300"), announcement)
        assertTrue(announcement.contains("Skręć w prawo"), announcement)
    }

    @Test
    fun `tuz przed manewrem mowimy sama instrukcje`() {
        val announcer = ManeuverAnnouncer()
        announcer.announce(progress(280.0))
        announcer.announce(progress(90.0))

        val announcement = assertNotNull(announcer.announce(progress(15.0)))

        assertEquals("Skręć w prawo", announcement)
    }

    @Test
    fun `ten sam prog nie jest powtarzany`() {
        val announcer = ManeuverAnnouncer()

        assertNotNull(announcer.announce(progress(280.0)))
        assertNull(announcer.announce(progress(270.0)))
        assertNull(announcer.announce(progress(260.0)))
    }

    @Test
    fun `kazdy prog odzywa sie raz`() {
        val announcer = ManeuverAnnouncer()
        val distances = listOf(400.0, 290.0, 250.0, 150.0, 95.0, 60.0, 30.0, 20.0, 10.0)

        val announcements = distances.mapNotNull { announcer.announce(progress(it)) }

        assertEquals(3, announcements.size, "otrzymano: $announcements")
    }

    /**
     * Przy rzadkich odczytach GPS rowerzysta potrafi przeskoczyć dwa progi między
     * odczytami. Ma wtedy usłyszeć to, co aktualne, a nie zaległą zapowiedź „za 300 metrów”,
     * gdy do manewru zostało 80 m.
     */
    @Test
    fun `przeskoczenie progu nie powoduje zaleglej zapowiedzi`() {
        val announcer = ManeuverAnnouncer()

        val announcement = assertNotNull(announcer.announce(progress(80.0)))

        assertTrue(announcement.contains("100"), announcement)
        assertFalse(announcement.contains("300"), announcement)
    }

    @Test
    fun `po przeskoczeniu progu wyzsze progi juz nie wrocą`() {
        val announcer = ManeuverAnnouncer()
        announcer.announce(progress(80.0))

        assertNull(announcer.announce(progress(75.0)))
    }

    @Test
    fun `nowy manewr zapowiada sie od nowa`() {
        val announcer = ManeuverAnnouncer()
        announcer.announce(progress(280.0))
        announcer.announce(progress(90.0))
        announcer.announce(progress(15.0))

        val announcement = assertNotNull(announcer.announce(progress(280.0, maneuver = nextTurn)))

        assertTrue(announcement.contains("Skręć w lewo"), announcement)
    }

    @Test
    fun `reset pozwala zapowiedziec manewr ponownie`() {
        val announcer = ManeuverAnnouncer()
        announcer.announce(progress(280.0))

        announcer.reset()

        assertNotNull(announcer.announce(progress(280.0)))
    }

    @Test
    fun `progi da sie skonfigurowac`() {
        val announcer = ManeuverAnnouncer(thresholdsMeters = listOf(50.0))

        assertNull(announcer.announce(progress(200.0)))
        assertNotNull(announcer.announce(progress(40.0)))
    }
}

class OffRouteDetectorTest {

    private fun progress(distanceFromRoute: Double) = RouteProgress(
        nearestIndex = 3,
        distanceFromRouteMeters = distanceFromRoute,
        nextManeuver = null,
        distanceToNextManeuverMeters = null,
        remainingDistanceMeters = 500.0,
    )

    private val onRoute = progress(5.0)
    private val offRoute = progress(120.0)

    @Test
    fun `jazda po trasie nie wywoluje przeliczenia`() {
        val detector = OffRouteDetector()

        repeat(10) { assertFalse(detector.update(onRoute)) }
    }

    @Test
    fun `pojedynczy odskok odczytu nie wywoluje przeliczenia`() {
        val detector = OffRouteDetector()

        assertFalse(detector.update(offRoute))
        assertFalse(detector.update(onRoute))
    }

    @Test
    fun `dwa odczyty poza trasa to wciaz za malo`() {
        val detector = OffRouteDetector()

        assertFalse(detector.update(offRoute))
        assertFalse(detector.update(offRoute))
    }

    @Test
    fun `trzy odczyty z rzedu uznajemy za zjechanie z trasy`() {
        val detector = OffRouteDetector()

        detector.update(offRoute)
        detector.update(offRoute)

        assertTrue(detector.update(offRoute))
    }

    @Test
    fun `przeliczenie zglaszane jest tylko raz`() {
        val detector = OffRouteDetector()
        repeat(3) { detector.update(offRoute) }

        assertFalse(detector.update(offRoute))
        assertFalse(detector.update(offRoute))
    }

    @Test
    fun `powrot na trase zeruje licznik`() {
        val detector = OffRouteDetector()
        detector.update(offRoute)
        detector.update(offRoute)

        detector.update(onRoute)

        assertFalse(detector.update(offRoute))
        assertFalse(detector.update(offRoute))
        assertTrue(detector.update(offRoute))
    }

    @Test
    fun `po powrocie na trase mozna zglosic zjechanie ponownie`() {
        val detector = OffRouteDetector()
        repeat(3) { detector.update(offRoute) }
        detector.update(onRoute)

        repeat(2) { detector.update(offRoute) }

        assertTrue(detector.update(offRoute))
    }

    @Test
    fun `reset kasuje stan`() {
        val detector = OffRouteDetector()
        repeat(3) { detector.update(offRoute) }

        detector.reset()

        repeat(2) { assertFalse(detector.update(offRoute)) }
        assertTrue(detector.update(offRoute))
    }

    @Test
    fun `liczba wymaganych odczytow da sie skonfigurowac`() {
        val detector = OffRouteDetector(requiredConsecutive = 1)

        assertTrue(detector.update(offRoute))
    }

    @Test
    fun `prog odleglosci pochodzi z RouteTracker`() {
        val detector = OffRouteDetector(requiredConsecutive = 1)
        val justInside = progress(RouteTracker.OFF_ROUTE_THRESHOLD_METERS - 1)
        val justOutside = progress(RouteTracker.OFF_ROUTE_THRESHOLD_METERS + 1)

        assertFalse(detector.update(justInside))
        assertTrue(detector.update(justOutside))
    }
}
