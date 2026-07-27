package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteEtaTest {

    @Test
    fun measured_speed_wins_when_the_rider_is_actually_riding() {
        // 5000 m przy 5 m/s to 1000 sekund.
        val estimate = RouteEta.estimate(route(3_600), progress(remaining = 5_000.0), speedMetersPerSecond = 5.0)

        assertEquals(EtaBasis.MEASURED_SPEED, estimate.basis)
        assertEquals(1_000L, estimate.remainingDurationSeconds)
    }

    /** Na postoju chwilowa prędkość niczego nie mówi — wtedy liczy się plan silnika. */
    @Test
    fun standing_still_falls_back_to_the_engine_estimate() {
        val estimate = RouteEta.estimate(
            route(estimatedDurationSeconds = 3_600),
            progress(traveled = 5_000.0, remaining = 5_000.0),
            speedMetersPerSecond = 0.0,
        )

        assertEquals(EtaBasis.ENGINE_ESTIMATE, estimate.basis)
        // Połowa trasy przed nami, więc połowa zaplanowanego czasu.
        assertEquals(1_800L, estimate.remainingDurationSeconds)
    }

    @Test
    fun walking_pace_is_treated_as_no_measurement() {
        val estimate = RouteEta.estimate(
            route(3_600),
            progress(remaining = 10_000.0),
            speedMetersPerSecond = RouteEta.MIN_SPEED_METERS_PER_SECOND - 0.1,
        )

        assertEquals(EtaBasis.ENGINE_ESTIMATE, estimate.basis)
    }

    @Test
    fun speed_exactly_at_the_threshold_still_counts() {
        val estimate = RouteEta.estimate(
            route(3_600),
            progress(remaining = 10_000.0),
            speedMetersPerSecond = RouteEta.MIN_SPEED_METERS_PER_SECOND,
        )

        assertEquals(EtaBasis.MEASURED_SPEED, estimate.basis)
    }

    @Test
    fun missing_speed_falls_back_to_the_engine_estimate() {
        val estimate = RouteEta.estimate(route(3_600), progress(remaining = 10_000.0), speedMetersPerSecond = null)

        assertEquals(EtaBasis.ENGINE_ESTIMATE, estimate.basis)
    }

    @Test
    fun a_nonsense_speed_reading_does_not_produce_a_nonsense_eta() {
        val estimate = RouteEta.estimate(
            route(3_600),
            progress(remaining = 10_000.0),
            speedMetersPerSecond = Double.NaN,
        )

        assertEquals(EtaBasis.ENGINE_ESTIMATE, estimate.basis)
    }

    /** Silnik, który nie podał czasu, nie ma z czego dać oszacowania — i tak to mówimy. */
    @Test
    fun no_engine_duration_and_no_speed_means_no_estimate() {
        val estimate = RouteEta.estimate(route(estimatedDurationSeconds = 0), progress(remaining = 10_000.0))

        assertNull(estimate.remainingDurationSeconds)
    }

    @Test
    fun a_zero_length_route_does_not_divide_by_zero() {
        val estimate = RouteEta.estimate(
            route(3_600),
            progress(traveled = 0.0, remaining = 0.0),
        )

        assertNull(estimate.remainingDurationSeconds)
        assertEquals(0.0, estimate.remainingDistanceMeters)
    }

    @Test
    fun remaining_distance_is_passed_through() {
        val estimate = RouteEta.estimate(route(3_600), progress(remaining = 1_234.0), speedMetersPerSecond = 5.0)

        assertEquals(1_234.0, estimate.remainingDistanceMeters)
    }

    // --- postep liczony przez RouteProgress ---

    @Test
    fun completed_fraction_is_the_share_of_the_route_behind_us() {
        val progress = progress(traveled = 2_500.0, remaining = 7_500.0)

        assertEquals(10_000.0, progress.totalDistanceMeters)
        assertEquals(0.25, progress.completedFraction)
    }

    @Test
    fun a_route_of_zero_length_reports_no_progress_instead_of_dividing_by_zero() {
        assertEquals(0.0, progress(traveled = 0.0, remaining = 0.0).completedFraction)
    }

    @Test
    fun tracker_reports_how_much_of_the_route_is_behind() {
        val geometry = listOf(
            GeoPoint(50.000, 20.0),
            GeoPoint(50.010, 20.0),
            GeoPoint(50.020, 20.0),
        )
        val route = Route(geometry = geometry, distanceMeters = 2_224.0, estimatedDurationSeconds = 600)

        val progress = RouteTracker.progress(route, GeoPoint(50.010, 20.0))!!

        assertTrue(progress.traveledDistanceMeters > 1_000.0, "${progress.traveledDistanceMeters}")
        assertTrue(progress.remainingDistanceMeters > 1_000.0, "${progress.remainingDistanceMeters}")
        assertTrue(
            progress.completedFraction in 0.45..0.55,
            "w polowie trasy udzial powinien byc bliski polowy: ${progress.completedFraction}",
        )
    }

    @Test
    fun at_the_start_nothing_is_behind_us_yet() {
        val geometry = listOf(GeoPoint(50.000, 20.0), GeoPoint(50.010, 20.0))
        val route = Route(geometry = geometry, distanceMeters = 1_112.0, estimatedDurationSeconds = 300)

        val progress = RouteTracker.progress(route, GeoPoint(50.000, 20.0))!!

        assertEquals(0.0, progress.traveledDistanceMeters)
        assertEquals(0.0, progress.completedFraction)
    }

    private fun route(estimatedDurationSeconds: Long) = Route(
        geometry = listOf(GeoPoint(50.0, 20.0), GeoPoint(50.1, 20.0)),
        distanceMeters = 10_000.0,
        estimatedDurationSeconds = estimatedDurationSeconds,
    )

    private fun progress(traveled: Double = 0.0, remaining: Double) = RouteProgress(
        nearestIndex = 0,
        distanceFromRouteMeters = 5.0,
        nextManeuver = null,
        distanceToNextManeuverMeters = null,
        remainingDistanceMeters = remaining,
        traveledDistanceMeters = traveled,
    )
}
