package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationTest {

    /** Wyznaczona trasa to jeszcze nie jazda — w tym etapie aplikacja ma milczeć. */
    @Test
    fun planning_does_not_guide() {
        assertFalse(NavigationPhase.PLANNING.isGuiding)
    }

    @Test
    fun only_the_navigating_phase_guides() {
        assertTrue(NavigationPhase.NAVIGATING.isGuiding)
        assertFalse(NavigationPhase.ARRIVED.isGuiding)
    }

    @Test
    fun arrival_is_reported_at_the_end_of_the_route() {
        assertTrue(ArrivalDetector.hasArrived(progress(remaining = 0.0)))
        assertTrue(ArrivalDetector.hasArrived(progress(remaining = 10.0)))
    }

    @Test
    fun arrival_exactly_at_the_radius_counts() {
        assertTrue(ArrivalDetector.hasArrived(progress(remaining = ArrivalDetector.ARRIVAL_RADIUS_METERS)))
    }

    @Test
    fun a_rider_still_on_the_way_has_not_arrived() {
        assertFalse(ArrivalDetector.hasArrived(progress(remaining = 500.0)))
        assertFalse(ArrivalDetector.hasArrived(progress(remaining = ArrivalDetector.ARRIVAL_RADIUS_METERS + 0.1)))
    }

    /**
     * Dojazd liczymy wzdłuż trasy, a nie po odległości od niej — inaczej ktoś jadący
     * równolegle do końcówki trasy „dojeżdżałby" nie dojechawszy.
     */
    @Test
    fun being_far_from_the_route_does_not_prevent_reporting_arrival() {
        val offRoute = progress(remaining = 5.0).copy(distanceFromRouteMeters = 200.0)

        assertTrue(ArrivalDetector.hasArrived(offRoute))
    }

    private fun progress(remaining: Double) = RouteProgress(
        nearestIndex = 0,
        distanceFromRouteMeters = 5.0,
        nextManeuver = null,
        distanceToNextManeuverMeters = null,
        remainingDistanceMeters = remaining,
        traveledDistanceMeters = 1_000.0,
    )
}
