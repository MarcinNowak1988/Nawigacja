package pl.reactivebike.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoAndPolylineTest {

    private val krakow = GeoPoint(50.0647, 19.9450)
    private val zakopane = GeoPoint(49.2992, 19.9496)

    // --- odległości ---

    @Test
    fun `odleglosc punktu od samego siebie wynosi zero`() {
        assertEquals(0.0, krakow.distanceTo(krakow), absoluteTolerance = 1e-6)
    }

    @Test
    fun `odleglosc jest symetryczna`() {
        assertEquals(
            krakow.distanceTo(zakopane),
            zakopane.distanceTo(krakow),
            absoluteTolerance = 1e-6,
        )
    }

    /** Kraków–Zakopane w linii prostej to ok. 85 km. */
    @Test
    fun `odleglosc Krakow Zakopane miesci sie w oczekiwanym zakresie`() {
        val km = krakow.distanceTo(zakopane) / 1_000

        assertTrue(km in 80.0..90.0, "otrzymano $km km")
    }

    /** Stopień szerokości geograficznej to ok. 111 km, niezależnie od długości. */
    @Test
    fun `stopien szerokosci ma okolo 111 km`() {
        val km = GeoPoint(50.0, 20.0).distanceTo(GeoPoint(51.0, 20.0)) / 1_000

        assertTrue(km in 110.0..112.0, "otrzymano $km km")
    }

    @Test
    fun `male odleglosci sa liczone dokladnie`() {
        // 0.00001 stopnia szerokości to ok. 1,11 m
        val meters = GeoPoint(50.0, 20.0).distanceTo(GeoPoint(50.00001, 20.0))

        assertTrue(meters in 1.0..1.3, "otrzymano $meters m")
    }

    @Test
    fun `dlugosc lamanej sumuje odcinki`() {
        val path = listOf(
            GeoPoint(50.0, 20.0),
            GeoPoint(50.01, 20.0),
            GeoPoint(50.02, 20.0),
        )

        val expected = path[0].distanceTo(path[1]) + path[1].distanceTo(path[2])

        assertEquals(expected, path.pathLengthMeters(), absoluteTolerance = 1e-6)
    }

    @Test
    fun `lamana z jednego punktu ma zerowa dlugosc`() {
        assertEquals(0.0, listOf(krakow).pathLengthMeters())
        assertEquals(0.0, emptyList<GeoPoint>().pathLengthMeters())
    }

    // --- dekodowanie geometrii ---

    @Test
    fun `pusty ciag daje pusta geometrie`() {
        assertEquals(emptyList(), Polyline.decode(""))
    }

    /** Klasyczny przykład z dokumentacji formatu, w precyzji 5. */
    @Test
    fun `dekoduje przykladowy ciag w precyzji 5`() {
        val decoded = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)

        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].latitude, absoluteTolerance = 1e-5)
        assertEquals(-120.2, decoded[0].longitude, absoluteTolerance = 1e-5)
        assertEquals(40.7, decoded[1].latitude, absoluteTolerance = 1e-5)
        assertEquals(-120.95, decoded[1].longitude, absoluteTolerance = 1e-5)
        assertEquals(43.252, decoded[2].latitude, absoluteTolerance = 1e-5)
        assertEquals(-126.453, decoded[2].longitude, absoluteTolerance = 1e-5)
    }

    /**
     * Ten sam ciąg odczytany w precyzji 6 daje współrzędne dziesięciokrotnie mniejsze.
     * Test pilnuje, żeby pomyłka precyzji nie przeszła niezauważona — objawia się
     * trasą przesuniętą o setki kilometrów, co łatwo wziąć za błąd zupełnie gdzie indziej.
     */
    @Test
    fun `precyzja zmienia skale wspolrzednych`() {
        val p5 = Polyline.decode("_p~iF~ps|U", precision = 5)
        val p6 = Polyline.decode("_p~iF~ps|U", precision = 6)

        assertEquals(p5[0].latitude / 10, p6[0].latitude, absoluteTolerance = 1e-6)
    }

    @Test
    fun `domyslna precyzja to szesc miejsc`() {
        assertEquals(6, Polyline.VALHALLA_PRECISION)
        assertEquals(
            Polyline.decode("_p~iF~ps|U", precision = 6),
            Polyline.decode("_p~iF~ps|U"),
        )
    }

    @Test
    fun `urwany ciag nie wywraca dekodera`() {
        val decoded = Polyline.decode("_p~iF~ps|U_ulL", precision = 5)

        assertTrue(decoded.isNotEmpty())
    }

    @Test
    fun `smieci nie wywracaja dekodera`() {
        Polyline.decode("!!!???", precision = 6)
        Polyline.decode("~~~~~~~~~~~~~~~~~~~~", precision = 6)
    }
}
