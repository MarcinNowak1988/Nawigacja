package pl.reactivebike.routing.brouter

import pl.reactivebike.routing.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BRouterSegmentsTest {

    @Test
    fun a_point_in_krakow_falls_into_the_E15_N50_tile() {
        assertEquals("E15_N50.rd5", BRouterSegments.fileNameFor(GeoPoint(50.0614, 19.9366)))
    }

    @Test
    fun a_point_in_warsaw_falls_into_the_E20_N50_tile() {
        assertEquals("E20_N50.rd5", BRouterSegments.fileNameFor(GeoPoint(52.2297, 21.0122)))
    }

    /** Punkt dokładnie na krawędzi należy do kafla, który się od niej zaczyna. */
    @Test
    fun a_point_exactly_on_a_tile_boundary_belongs_to_the_tile_starting_there() {
        assertEquals("E20_N50.rd5", BRouterSegments.fileNameFor(GeoPoint(50.0, 20.0)))
    }

    /**
     * Zaokrąglenie musi być podłogowe także poniżej zera. Zwykłe dzielenie całkowite ucina
     * w stronę zera, przez co -3° trafiłoby do kafla 0 zamiast -5.
     */
    @Test
    fun negative_coordinates_round_down_not_towards_zero() {
        assertEquals("W5_S5.rd5", BRouterSegments.fileNameFor(GeoPoint(-3.0, -3.0)))
        assertEquals("W5_N0.rd5", BRouterSegments.fileNameFor(GeoPoint(1.0, -1.0)))
    }

    @Test
    fun a_point_west_of_greenwich_uses_the_W_prefix() {
        assertEquals("W5_N50.rd5", BRouterSegments.fileNameFor(GeoPoint(51.5074, -0.1278)))
    }

    @Test
    fun a_single_point_needs_exactly_one_segment() {
        assertEquals(listOf("E15_N50.rd5"), BRouterSegments.fileNamesFor(listOf(GeoPoint(50.06, 19.94))))
    }

    @Test
    fun no_points_need_no_segments() {
        assertTrue(BRouterSegments.fileNamesFor(emptyList()).isEmpty())
    }

    /** Dwa punkty w jednym kaflu nie mogą dać dwóch takich samych nazw. */
    @Test
    fun two_points_in_the_same_tile_need_that_tile_once() {
        val names = BRouterSegments.fileNamesFor(listOf(GeoPoint(50.06, 19.94), GeoPoint(50.10, 19.99)))

        assertEquals(listOf("E15_N50.rd5"), names)
    }

    /**
     * Sedno: bierzemy cały prostokąt, a nie same punkty. Trasa Kraków–Warszawa przechodzi
     * przez kafle, w których nie leży żaden ze wskazanych punktów.
     */
    @Test
    fun a_route_across_tiles_needs_the_whole_bounding_box() {
        val names = BRouterSegments.fileNamesFor(
            listOf(GeoPoint(50.0614, 19.9366), GeoPoint(52.2297, 21.0122)),
        )

        assertEquals(
            listOf("E15_N50.rd5", "E20_N50.rd5"),
            names.sorted(),
        )
    }

    /**
     * Obszar Polski: długości 14–21° dają **trzy** kolumny kafli (10, 15, 20), a szerokości
     * 49–51° dwa wiersze (45, 50). Sześć plików — tyle trzeba pobrać, żeby wyznaczyć trasę
     * w dowolnym miejscu kraju.
     */
    @Test
    fun the_area_of_poland_needs_six_segments() {
        val names = BRouterSegments.fileNamesFor(
            listOf(GeoPoint(49.0, 14.0), GeoPoint(51.0, 21.0)),
        )

        assertEquals(
            listOf(
                "E10_N45.rd5", "E10_N50.rd5",
                "E15_N45.rd5", "E15_N50.rd5",
                "E20_N45.rd5", "E20_N50.rd5",
            ),
            names.sorted(),
        )
    }

    @Test
    fun a_bounding_box_crossing_the_equator_and_greenwich_covers_all_four_quadrants() {
        val names = BRouterSegments.fileNamesFor(listOf(GeoPoint(-1.0, -1.0), GeoPoint(1.0, 1.0)))

        assertEquals(
            listOf("E0_N0.rd5", "E0_S5.rd5", "W5_N0.rd5", "W5_S5.rd5"),
            names.sorted(),
        )
    }

    @Test
    fun the_download_url_points_at_the_official_segment_directory() {
        assertEquals(
            "https://brouter.de/brouter/segments4/E15_N50.rd5",
            BRouterSegments.downloadUrlFor("E15_N50.rd5"),
        )
    }
}
