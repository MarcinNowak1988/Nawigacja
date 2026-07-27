package pl.reactivebike.routing.brouter

import pl.reactivebike.routing.GeoPoint
import kotlin.math.floor

/**
 * Pliki danych trasowania BRoutera potrzebne dla zadanego obszaru.
 *
 * BRouter dzieli świat na kafle 5°×5° zapisane w plikach `.rd5`. Nazwa kafla wynika wprost
 * z jego lewego dolnego rogu — odtworzone z `NodesCache.fileForSegment`, nie zgadnięte:
 * współrzędne zaokrąglane są **w dół** do wielokrotności 5°, ze znakami `W` i `S` dla wartości
 * ujemnych. Zaokrąglenie musi być podłogowe także poniżej zera, więc `-3°` trafia do kafla
 * `-5`, a nie `0`.
 *
 * Bez tych plików silnik na urządzeniu nie policzy niczego, więc aplikacja musi umieć
 * powiedzieć **przed** wyznaczaniem, których brakuje.
 */
object BRouterSegments {

    /** Rozmiar kafla w stopniach. */
    const val TILE_DEGREES = 5

    /** Katalog, z którego BRouter rozprowadza aktualne segmenty. */
    const val DOWNLOAD_BASE_URL = "https://brouter.de/brouter/segments4"

    /**
     * Przybliżony rozmiar jednego kafla.
     *
     * Kafle różnią się wielkością o rząd wielkości — gęsto zmapowana Europa Zachodnia waży
     * wielokrotnie więcej niż kafel w większości pusty. Traktować jako rząd wielkości przy
     * ostrzeganiu o transferze, nie jako obietnicę.
     */
    const val APPROXIMATE_TILE_BYTES: Long = 60L * 1024 * 1024

    /** Nazwa pliku segmentu pokrywającego dany punkt, np. `E15_N50.rd5`. */
    fun fileNameFor(point: GeoPoint): String = fileName(tileOrigin(point.longitude), tileOrigin(point.latitude))

    /**
     * Nazwy segmentów pokrywających prostokąt obejmujący wszystkie punkty trasy.
     *
     * Bierzemy cały prostokąt, a nie same punkty: trasa między dwoma rogami potrafi
     * przechodzić przez kafel, w którym nie leży żaden z wskazanych punktów.
     */
    fun fileNamesFor(points: List<GeoPoint>): List<String> {
        if (points.isEmpty()) return emptyList()

        val lonFrom = tileOrigin(points.minOf { it.longitude })
        val lonTo = tileOrigin(points.maxOf { it.longitude })
        val latFrom = tileOrigin(points.minOf { it.latitude })
        val latTo = tileOrigin(points.maxOf { it.latitude })

        val names = mutableListOf<String>()
        var lon = lonFrom
        while (lon <= lonTo) {
            var lat = latFrom
            while (lat <= latTo) {
                names += fileName(lon, lat)
                lat += TILE_DEGREES
            }
            lon += TILE_DEGREES
        }
        return names
    }

    fun downloadUrlFor(fileName: String): String = "$DOWNLOAD_BASE_URL/$fileName"

    /** Lewy dolny róg kafla: zaokrąglenie podłogowe do wielokrotności 5°, także dla ujemnych. */
    private fun tileOrigin(degrees: Double): Int =
        floor(degrees / TILE_DEGREES).toInt() * TILE_DEGREES

    private fun fileName(lon: Int, lat: Int): String {
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_$slat.rd5"
    }
}
