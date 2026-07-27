package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePlanTest {

    @Test
    fun empty_plan_cannot_be_routed() {
        val plan = RoutePlan()

        assertTrue(plan.isEmpty)
        assertFalse(plan.isComplete)
        assertNull(plan.waypoints(HERE))
    }

    @Test
    fun first_picked_point_becomes_the_destination() {
        val plan = RoutePlan().withNextStop(A)!!

        assertEquals(Waypoint(A), plan.destination)
        assertTrue(plan.via.isEmpty())
        assertTrue(plan.isComplete)
    }

    /** Sedno gestu „wskaż kolejny punkt": dotychczasowy cel schodzi do pośrednich. */
    @Test
    fun second_picked_point_pushes_the_old_destination_into_via() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!

        assertEquals(listOf(Waypoint(A)), plan.via)
        assertEquals(Waypoint(B), plan.destination)
    }

    @Test
    fun waypoints_use_current_position_when_no_start_was_picked() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!

        assertEquals(listOf(HERE, A, B), plan.waypoints(HERE))
    }

    @Test
    fun waypoints_use_the_picked_start_instead_of_current_position() {
        val plan = RoutePlan().withNextStop(A)!!.withStart(C)

        assertEquals(listOf(C, A), plan.waypoints(HERE))
    }

    /**
     * Start z pozycji rozwija się dopiero tutaj, więc plan zrobiony w domu nie prowadzi
     * z domu, gdy użytkownik ruszył.
     */
    @Test
    fun start_from_current_position_follows_the_rider() {
        val plan = RoutePlan().withNextStop(A)!!

        assertEquals(listOf(HERE, A), plan.waypoints(HERE))
        assertEquals(listOf(C, A), plan.waypoints(C))
    }

    @Test
    fun plan_without_position_and_without_start_cannot_be_routed() {
        val plan = RoutePlan().withNextStop(A)!!

        assertNull(plan.waypoints(null))
    }

    @Test
    fun picked_start_makes_the_plan_routable_without_a_position_fix() {
        val plan = RoutePlan().withNextStop(A)!!.withStart(C)

        assertEquals(listOf(C, A), plan.waypoints(null))
    }

    @Test
    fun undo_returns_the_previous_destination() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!.withoutLastStop()

        assertEquals(Waypoint(A), plan.destination)
        assertTrue(plan.via.isEmpty())
    }

    @Test
    fun undo_walks_all_the_way_back_to_an_empty_plan() {
        var plan = RoutePlan().withStart(C).withNextStop(A)!!.withNextStop(B)!!

        repeat(3) { plan = plan.withoutLastStop() }

        assertTrue(plan.isEmpty, "po cofnieciu wszystkiego plan powinien byc pusty: $plan")
    }

    @Test
    fun undo_on_an_empty_plan_changes_nothing() {
        assertEquals(RoutePlan(), RoutePlan().withoutLastStop())
    }

    @Test
    fun via_point_can_be_removed_by_index() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!.withNextStop(C)!!

        assertEquals(listOf(Waypoint(A), Waypoint(B)), plan.via)
        assertEquals(listOf(Waypoint(B)), plan.withoutVia(0).via)
    }

    @Test
    fun removing_a_via_point_outside_the_range_changes_nothing() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!

        assertEquals(plan, plan.withoutVia(5))
        assertEquals(plan, plan.withoutVia(-1))
    }

    @Test
    fun clearing_leaves_an_empty_plan() {
        val plan = RoutePlan().withStart(C).withNextStop(A)!!.withNextStop(B)!!

        assertTrue(plan.cleared().isEmpty)
    }

    @Test
    fun stops_are_returned_in_travel_order_with_roles() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!.withStart(C)

        assertEquals(
            listOf(
                PlannedStop(Waypoint(C), StopRole.START),
                PlannedStop(Waypoint(A), StopRole.VIA),
                PlannedStop(Waypoint(B), StopRole.DESTINATION),
            ),
            plan.stops(),
        )
    }

    /** Start z bieżącej pozycji nie jest punktem do narysowania — pozycję rysuje GPS. */
    @Test
    fun implicit_start_is_not_listed_as_a_stop() {
        val plan = RoutePlan().withNextStop(A)!!

        assertEquals(listOf(PlannedStop(Waypoint(A), StopRole.DESTINATION)), plan.stops())
    }

    @Test
    fun adding_beyond_the_engine_limit_is_refused_instead_of_ignored() {
        var plan = RoutePlan().withNextStop(A)!!
        // Start z pozycji zajmuje jedno miejsce, więc ręcznie da się dodać o jeden mniej.
        repeat(RoutePlan.MAX_WAYPOINTS - 2) { plan = plan.withNextStop(B)!! }

        assertEquals(RoutePlan.MAX_WAYPOINTS - 1, plan.via.size + 1)
        assertNull(plan.withNextStop(C), "powyzej limitu dodanie punktu ma zwrocic null")
    }

    /** Start zajmuje miejsce zarezerwowane na pozycję, więc nigdy nie przepełnia planu. */
    @Test
    fun a_full_plan_still_accepts_replacing_the_start() {
        var plan = RoutePlan().withNextStop(A)!!
        repeat(RoutePlan.MAX_WAYPOINTS - 2) { plan = plan.withNextStop(B)!! }
        val withStart = plan.withStart(C)

        assertEquals(Waypoint(C), withStart.start)
        assertNull(withStart.withNextStop(A), "kolejny punkt juz sie nie miesci")
    }

    // --- konsumowanie minietych punktow posrednich ---

    @Test
    fun reaching_a_via_point_removes_it_from_the_plan() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!

        val afterPassing = plan.consumingReachedVia(A)

        assertTrue(afterPassing.via.isEmpty(), "minięty punkt powinien zniknąć: ${afterPassing.via}")
        assertEquals(Waypoint(B), afterPassing.destination)
    }

    @Test
    fun staying_away_from_a_via_point_keeps_it() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!

        assertEquals(plan, plan.consumingReachedVia(HERE))
    }

    /** Kolejność planu jest wiążąca — minięcie drugiego punktu nie kasuje pierwszego. */
    @Test
    fun passing_a_later_via_point_does_not_consume_the_earlier_one() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!.withNextStop(C)!!

        val afterPassing = plan.consumingReachedVia(B)

        assertEquals(listOf(Waypoint(A), Waypoint(B)), afterPassing.via)
    }

    @Test
    fun consecutive_via_points_are_consumed_in_one_pass() {
        val nearA = GeoPoint(A.latitude + 0.0001, A.longitude)
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(nearA)!!.withNextStop(C)!!

        val afterPassing = plan.consumingReachedVia(A)

        assertTrue(afterPassing.via.isEmpty(), "oba bliskie punkty powinny zejsc: ${afterPassing.via}")
    }

    /** Cel nie jest punktem pośrednim — dojazd do niego nie może wyczyścić trasy. */
    @Test
    fun reaching_the_destination_does_not_consume_it() {
        val plan = RoutePlan().withNextStop(A)!!

        assertEquals(plan, plan.consumingReachedVia(A))
    }

    @Test
    fun description_names_the_number_of_via_points() {
        val one = RoutePlan().withNextStop(A)!!.withNextStop(B)!!
        val three = one.withNextStop(C)!!.withNextStop(A)!!

        assertTrue(one.describe().contains("1 punkt pośredni"), one.describe())
        assertTrue(three.describe().contains("3 punkty pośrednie"), three.describe())
    }

    @Test
    fun description_says_whether_the_start_is_the_current_position() {
        val fromHere = RoutePlan().withNextStop(A)!!

        assertTrue(fromHere.describe().contains("moja pozycja"), fromHere.describe())
        assertEquals("moja pozycja", fromHere.describeStart())
    }

    /** Sedno zmiany: użytkownik ma widzieć, co wybrał, a nie że „coś" wybrał. */
    @Test
    fun a_named_place_is_described_by_its_name() {
        val plan = RoutePlan()
            .withNextStop(A, label = "Rynek Główny")!!
            .withStart(C, label = "Wawel")

        assertEquals("Wawel", plan.describeStart())
        assertEquals("Rynek Główny", plan.describeDestination())
        assertTrue(plan.describe().contains("Wawel"), plan.describe())
        assertTrue(plan.describe().contains("Rynek Główny"), plan.describe())
    }

    /** Punkt wskazany palcem nie ma nazwy — wtedy pokazujemy współrzędne, nie pustkę. */
    @Test
    fun an_unnamed_point_falls_back_to_coordinates() {
        val plan = RoutePlan().withNextStop(GeoPoint(50.06143, 19.93658))!!

        assertEquals("50.06143, 19.93658", plan.describeDestination())
    }

    @Test
    fun coordinates_south_and_west_of_zero_keep_their_sign() {
        val described = Waypoint(GeoPoint(-33.86785, -151.20732)).describe()

        assertEquals("-33.86785, -151.20732", described)
    }

    @Test
    fun destination_can_be_replaced_without_touching_the_via_points() {
        val plan = RoutePlan().withNextStop(A)!!.withNextStop(B)!!.withDestination(C, "Nowy cel")

        assertEquals(listOf(Waypoint(A)), plan.via)
        assertEquals("Nowy cel", plan.describeDestination())
    }

    @Test
    fun there_is_no_destination_to_describe_before_one_is_chosen() {
        assertNull(RoutePlan().describeDestination())
    }

    @Test
    fun description_of_an_incomplete_plan_asks_for_a_destination() {
        assertTrue(RoutePlan().describe().contains("wskaż cel"))
        assertTrue(RoutePlan(start = Waypoint(C)).describe().contains("wskaż cel"))
    }

    private companion object {
        val HERE = GeoPoint(50.06, 19.94)
        val A = GeoPoint(50.10, 20.00)
        val B = GeoPoint(50.20, 20.10)
        val C = GeoPoint(50.30, 20.20)
    }
}
