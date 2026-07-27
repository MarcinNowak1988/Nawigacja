package pl.reactivebike.maps

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/** Prostokątny wycinek mapy. */
data class GeoBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    init {
        require(north in -90.0..90.0 && south in -90.0..90.0) {
            "Szerokosc poza zakresem: north=$north, south=$south"
        }
        require(east in -180.0..180.0 && west in -180.0..180.0) {
            "Dlugosc poza zakresem: east=$east, west=$west"
        }
        require(north >= south) { "Granica polnocna musi byc na polnoc od poludniowej" }
    }
}

/**
 * Oszacowanie kosztu pobrania regionu mapy.
 *
 * @property tileCount liczba kafelków do pobrania w całym zakresie powiększeń
 * @property estimatedBytes przybliżony rozmiar — patrz [OfflineRegionEstimator.AVERAGE_TILE_BYTES]
 */
data class OfflineRegionEstimate(
    val tileCount: Long,
    val estimatedBytes: Long,
) {
    val estimatedMegabytes: Double
        get() = estimatedBytes / 1_048_576.0
}

/**
 * Liczy, ile kafelków obejmie pobranie regionu, zanim użytkownik je uruchomi.
 *
 * Bez tego pobranie obszaru wielkości województwa startowało bez ostrzeżenia i dopiero
 * pasek postępu zdradzał skalę — co przy limicie transferu jest kosztowną niespodzianką.
 *
 * Liczba kafelków jest dokładna dla samej siatki Web Mercator, ale traktować ją należy jako
 * **oszacowanie od góry** liczby faktycznie pobranych plików: styl wektorowy ma zwykle własny
 * `maxzoom` (często 14), a powyżej niego renderer skaluje kafelki niższego poziomu zamiast
 * pobierać nowe.
 *
 * Rozmiar jest oszacowaniem tym bardziej: kafelki wektorowe różnią się wielkością o rząd
 * wielkości zależnie od tego, czy pokrywają miasto czy pustkowie. Celem nie jest podanie
 * dokładnej liczby megabajtów, tylko odróżnienie „to zejdzie w kilka sekund" od „to zje
 * połowę pakietu danych".
 */
object OfflineRegionEstimator {

    /**
     * Przyjęty średni rozmiar kafelka wektorowego.
     *
     * Wartość z obserwacji typowych stylów OpenMapTiles — gęsto zabudowane miasto potrafi
     * dać kafelki kilkukrotnie większe, a las czy woda kilkukrotnie mniejsze. Traktować
     * jako rząd wielkości, nie jako obietnicę.
     */
    const val AVERAGE_TILE_BYTES: Long = 50_000

    /** Powyżej tylu kafelków ostrzegamy użytkownika przed uruchomieniem pobierania. */
    const val LARGE_REGION_TILE_COUNT: Long = 20_000

    fun estimate(bounds: GeoBounds, minZoom: Int, maxZoom: Int): OfflineRegionEstimate {
        require(minZoom >= 0 && maxZoom >= minZoom) {
            "Nieprawidlowy zakres powiekszen: $minZoom..$maxZoom"
        }

        var tiles = 0L
        for (zoom in minZoom..maxZoom) {
            tiles += tileCountAt(bounds, zoom)
        }

        return OfflineRegionEstimate(
            tileCount = tiles,
            estimatedBytes = tiles * AVERAGE_TILE_BYTES,
        )
    }

    fun isLarge(estimate: OfflineRegionEstimate): Boolean =
        estimate.tileCount > LARGE_REGION_TILE_COUNT

    /**
     * Rozmiar w formie do pokazania użytkownikowi.
     *
     * Konsekwentnie zaokrąglany i poprzedzony „ok.", bo podanie megabajtów co do dziesiątej
     * części sugerowałoby dokładność, której to oszacowanie nie ma.
     */
    fun describeSize(estimate: OfflineRegionEstimate): String {
        val megabytes = estimate.estimatedMegabytes
        return when {
            megabytes < 1.0 -> "poniżej 1 MB"
            megabytes < 10.0 -> {
                val tenths = (megabytes * 10).roundToInt()
                "ok. ${tenths / 10},${tenths % 10} MB"
            }
            else -> "ok. ${megabytes.roundToInt()} MB"
        }
    }

    private fun tileCountAt(bounds: GeoBounds, zoom: Int): Long {
        val scale = 1L shl zoom

        val xMin = longitudeToTileX(bounds.west, scale)
        val xMax = longitudeToTileX(bounds.east, scale)

        // W siatce kafelków oś Y rośnie na południe, więc północna granica daje mniejszy indeks.
        val yMin = latitudeToTileY(bounds.north, scale)
        val yMax = latitudeToTileY(bounds.south, scale)

        // Region przecinający południk 180° ma wschodnią granicę o mniejszej długości
        // niż zachodnia — wtedy liczymy kafelki „dookoła”, zamiast dostać wynik ujemny.
        val columns = if (xMax >= xMin) xMax - xMin + 1 else (scale - xMin) + xMax + 1
        val rows = yMax - yMin + 1

        return columns * rows
    }

    private fun longitudeToTileX(longitude: Double, scale: Long): Long =
        floor((longitude + 180.0) / 360.0 * scale).toLong().coerceIn(0, scale - 1)

    private fun latitudeToTileY(latitude: Double, scale: Long): Long {
        // Bieguny w odwzorowaniu Web Mercator leżą w nieskończoności, więc szerokość
        // przycinamy do zakresu, w którym odwzorowanie jest określone.
        val clamped = latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)
        val radians = clamped * PI / 180.0
        val y = (1.0 - asinh(tan(radians)) / PI) / 2.0
        return floor(y * scale).toLong().coerceIn(0, scale - 1)
    }

    /** Granica odwzorowania Web Mercator. */
    private const val MERCATOR_LIMIT = 85.05112878
}
