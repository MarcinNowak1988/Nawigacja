package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteProgressTest {

    /** Prosta trasa na południe, punkty co ok. 111 m. */
    private val geometry = (0..10).map { GeoPoint(50.0 + it * 0.001, 20.0) }

    private val route = Route(
        geometry = geometry,
        distanceMeters = geometry.pathLengthMeters(),
        estimatedDurationSeconds = 600,
        maneuvers = listOf(
            Maneuver("Jedź prosto", 500.0, 120, beginShapeIndex = 0),
            Maneuver("Skręć w prawo", 300.0, 90, beginShapeIndex = 5),
            Maneuver("Cel po lewej", 0.0, 0, beginShapeIndex = 10),
        ),
    )

    @Test
    fun `trasa bez geometrii nie daje postepu`() {
        val empty = Route(emptyList(), 0.0, 0)

        assertNull(RouteTracker.progress(empty, GeoPoint(50.0, 20.0)))
    }

    @Test
    fun `na starcie najblizszy jest punkt zerowy`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry.first()))

        assertEquals(0, progress.nearestIndex)
        assertEquals(0.0, progress.distanceFromRouteMeters, absoluteTolerance = 1e-6)
    }

    @Test
    fun `w polowie trasy najblizszy jest punkt srodkowy`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry[5]))

        assertEquals(5, progress.nearestIndex)
    }

    @Test
    fun `nastepny manewr to pierwszy przed nami`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry[2]))

        assertEquals("Skręć w prawo", progress.nextManeuver?.instruction)
    }

    @Test
    fun `manewr za nami nie jest juz nastepnym`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry[6]))

        assertEquals("Cel po lewej", progress.nextManeuver?.instruction)
    }

    @Test
    fun `na koncu trasy nie ma juz manewru`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry.last()))

        assertNull(progress.nextManeuver)
        assertNull(progress.distanceToNextManeuverMeters)
    }

    /**
     * Odległość do manewru liczona jest wzdłuż trasy. Tu trasa jest prosta, więc powinna
     * odpowiadać sumie trzech odcinków między punktem 2 a 5.
     */
    @Test
    fun `odleglosc do manewru jest liczona wzdluz trasy`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry[2]))
        val expected = geometry.subList(2, 6).pathLengthMeters()

        assertEquals(expected, progress.distanceToNextManeuverMeters!!, absoluteTolerance = 1e-6)
    }

    @Test
    fun `odleglosc do manewru maleje w miare zblizania sie`() {
        val far = RouteTracker.progress(route, geometry[1])!!.distanceToNextManeuverMeters!!
        val near = RouteTracker.progress(route, geometry[4])!!.distanceToNextManeuverMeters!!

        assertTrue(near < far, "blizej: $near, dalej: $far")
    }

    @Test
    fun `pozostaly dystans maleje wzdluz trasy`() {
        val start = RouteTracker.progress(route, geometry.first())!!.remainingDistanceMeters
        val middle = RouteTracker.progress(route, geometry[5])!!.remainingDistanceMeters
        val end = RouteTracker.progress(route, geometry.last())!!.remainingDistanceMeters

        assertTrue(start > middle && middle > end, "$start > $middle > $end")
        assertEquals(0.0, end, absoluteTolerance = 1e-6)
    }

    @Test
    fun `pozostaly dystans na starcie odpowiada dlugosci trasy`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry.first()))

        assertEquals(geometry.pathLengthMeters(), progress.remainingDistanceMeters, absoluteTolerance = 1e-6)
    }

    // --- zjechanie z trasy ---

    @Test
    fun `jazda po trasie to nie zjechanie z niej`() {
        val progress = assertNotNull(RouteTracker.progress(route, geometry[3]))

        assertFalse(RouteTracker.isOffRoute(progress))
    }

    @Test
    fun `odsuniecie o kilkaset metrow to zjechanie z trasy`() {
        val away = GeoPoint(50.003, 20.01)

        val progress = assertNotNull(RouteTracker.progress(route, away))

        assertTrue(RouteTracker.isOffRoute(progress), "odleglosc: ${progress.distanceFromRouteMeters}")
    }

    @Test
    fun `niewielkie odchylenie miesci sie w tolerancji`() {
        // ok. 20 m na wschód od trasy
        val slightlyOff = GeoPoint(50.003, 20.00028)

        val progress = assertNotNull(RouteTracker.progress(route, slightlyOff))

        assertFalse(RouteTracker.isOffRoute(progress), "odleglosc: ${progress.distanceFromRouteMeters}")
    }

    @Test
    fun `trasa bez manewrow daje postep bez nastepnego manewru`() {
        val plain = Route(geometry, geometry.pathLengthMeters(), 600)

        val progress = assertNotNull(RouteTracker.progress(plain, geometry[2]))

        assertNull(progress.nextManeuver)
        assertTrue(progress.remainingDistanceMeters > 0)
    }
}
