package pl.reactivebike.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineRegionEstimateTest {

    /** Cały świat mieści się w jednym kafelku na powiększeniu 0 — punkt odniesienia siatki. */
    @Test
    fun world_at_zoom_zero_is_one_tile() {
        val estimate = OfflineRegionEstimator.estimate(WORLD, minZoom = 0, maxZoom = 0)

        assertEquals(1, estimate.tileCount)
    }

    @Test
    fun world_at_zoom_two_is_sixteen_tiles() {
        val estimate = OfflineRegionEstimator.estimate(WORLD, minZoom = 2, maxZoom = 2)

        assertEquals(16, estimate.tileCount)
    }

    /** Zakres powiększeń sumuje się po poziomach: 1 + 4 + 16. */
    @Test
    fun zoom_range_sums_every_level() {
        val estimate = OfflineRegionEstimator.estimate(WORLD, minZoom = 0, maxZoom = 2)

        assertEquals(21, estimate.tileCount)
    }

    /**
     * Region przecinający południk 180° ma wschodnią granicę o mniejszej długości niż
     * zachodnia. Bez obsługi tego przypadku odejmowanie indeksów daje liczbę ujemną.
     */
    @Test
    fun region_crossing_antimeridian_wraps_instead_of_going_negative() {
        // Pas celowo nie dotyka równika — ten leży na granicy kafelków i dołożyłby drugi
        // wiersz, przez co test mierzyłby coś innego niż zawijanie kolumn.
        val crossing = GeoBounds(north = 10.0, south = 1.0, east = -170.0, west = 170.0)

        val estimate = OfflineRegionEstimator.estimate(crossing, minZoom = 2, maxZoom = 2)

        // Kolumny 3 i 0 na czterokolumnowej siatce, w jednym wierszu.
        assertEquals(2, estimate.tileCount)
    }

    /** Web Mercator nie sięga biegunów — szerokość musi zostać przycięta, a nie rozjechać się. */
    @Test
    fun region_touching_the_pole_is_clamped() {
        val polar = GeoBounds(north = 90.0, south = 89.0, east = 1.0, west = 0.0)

        val estimate = OfflineRegionEstimator.estimate(polar, minZoom = 1, maxZoom = 1)

        assertEquals(1, estimate.tileCount)
    }

    @Test
    fun estimated_size_follows_tile_count() {
        val estimate = OfflineRegionEstimator.estimate(WORLD, minZoom = 2, maxZoom = 2)

        assertEquals(16 * OfflineRegionEstimator.AVERAGE_TILE_BYTES, estimate.estimatedBytes)
        assertEquals(
            estimate.estimatedBytes / 1_048_576.0,
            estimate.estimatedMegabytes,
        )
    }

    /**
     * Miasto w zakresie powiększeń używanym przy pobieraniu mieści się w progu ostrzeżenia,
     * a województwo już nie — to jest cała różnica, którą próg ma wychwycić.
     */
    @Test
    fun city_sized_region_is_not_large_but_a_province_is() {
        val krakow = GeoBounds(north = 50.13, south = 49.97, east = 20.10, west = 19.79)
        val malopolska = GeoBounds(north = 50.60, south = 49.15, east = 21.50, west = 19.10)

        val city = OfflineRegionEstimator.estimate(krakow, minZoom = 10, maxZoom = 15)
        val province = OfflineRegionEstimator.estimate(malopolska, minZoom = 10, maxZoom = 15)

        assertFalse(OfflineRegionEstimator.isLarge(city), "kafelki miasta: ${city.tileCount}")
        assertTrue(OfflineRegionEstimator.isLarge(province), "kafelki województwa: ${province.tileCount}")
    }

    /** Każdy kolejny poziom to czterokrotnie więcej kafelków — stąd ostrzeżenie ma sens. */
    @Test
    fun each_zoom_level_quadruples_the_tile_count() {
        val region = GeoBounds(north = 50.13, south = 49.97, east = 20.10, west = 19.79)

        val atFourteen = OfflineRegionEstimator.estimate(region, minZoom = 14, maxZoom = 14).tileCount
        val atFifteen = OfflineRegionEstimator.estimate(region, minZoom = 15, maxZoom = 15).tileCount

        assertTrue(
            atFifteen >= atFourteen * 3,
            "poziom 15 ($atFifteen) powinien być wielokrotnie liczniejszy niż 14 ($atFourteen)",
        )
    }

    @Test
    fun tiny_region_is_described_without_a_misleading_zero() {
        val described = OfflineRegionEstimator.describeSize(OfflineRegionEstimate(1, 50_000))

        assertEquals("poniżej 1 MB", described)
    }

    @Test
    fun size_below_ten_megabytes_keeps_one_decimal() {
        // 100 kafelków × 50 kB = 5 000 000 B = 4,768… MB
        val described = OfflineRegionEstimator.describeSize(OfflineRegionEstimate(100, 5_000_000))

        assertEquals("ok. 4,8 MB", described)
    }

    @Test
    fun larger_size_is_rounded_to_whole_megabytes() {
        // 1 000 kafelków × 50 kB = 50 000 000 B = 47,68… MB
        val described = OfflineRegionEstimator.describeSize(OfflineRegionEstimate(1_000, 50_000_000))

        assertEquals("ok. 48 MB", described)
    }

    @Test
    fun bounds_reject_reversed_latitudes() {
        assertFailsWith<IllegalArgumentException> {
            GeoBounds(north = 49.0, south = 50.0, east = 20.0, west = 19.0)
        }
    }

    @Test
    fun bounds_reject_values_outside_the_globe() {
        assertFailsWith<IllegalArgumentException> {
            GeoBounds(north = 91.0, south = 49.0, east = 20.0, west = 19.0)
        }
        assertFailsWith<IllegalArgumentException> {
            GeoBounds(north = 50.0, south = 49.0, east = 200.0, west = 19.0)
        }
    }

    @Test
    fun estimator_rejects_a_reversed_zoom_range() {
        assertFailsWith<IllegalArgumentException> {
            OfflineRegionEstimator.estimate(WORLD, minZoom = 12, maxZoom = 10)
        }
    }

    @Test
    fun estimator_rejects_a_negative_zoom() {
        assertFailsWith<IllegalArgumentException> {
            OfflineRegionEstimator.estimate(WORLD, minZoom = -1, maxZoom = 10)
        }
    }

    private companion object {
        /** Cały świat w granicach, w jakich Web Mercator jest określony. */
        val WORLD = GeoBounds(north = 85.0, south = -85.0, east = 180.0, west = -180.0)
    }
}
