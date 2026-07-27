package pl.reactivebike.routing

/**
 * Dekoder skompresowanej geometrii trasy (encoded polyline).
 *
 * Format upakowuje ciąg współrzędnych jako różnice względem punktu poprzedniego,
 * zapisane w kodowaniu zmiennej długości. Valhalla używa precyzji 6 miejsc po przecinku,
 * a nie 5 jak oryginalny format Google — stąd [precision] jako parametr, bo pomyłka na
 * tym polu daje trasę przesuniętą o rząd wielkości i wygląda jak błąd zupełnie gdzie indziej.
 */
object Polyline {

    /** Domyślna precyzja Valhalli. */
    const val VALHALLA_PRECISION = 6

    fun decode(encoded: String, precision: Int = VALHALLA_PRECISION): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()

        val factor = generateSequence(1.0) { it * 10 }.take(precision + 1).last()
        val points = mutableListOf<GeoPoint>()

        var index = 0
        var lat = 0
        var lon = 0

        while (index < encoded.length) {
            val deltaLat = readValue(encoded, index) ?: return points
            index = deltaLat.second
            lat += deltaLat.first

            val deltaLon = readValue(encoded, index) ?: return points
            index = deltaLon.second
            lon += deltaLon.first

            val latitude = lat / factor
            val longitude = lon / factor

            // Odrzucamy punkty spoza zakresu zamiast pozwolić, by GeoPoint rzucił wyjątkiem —
            // uszkodzony fragment geometrii nie powinien wywracać całej nawigacji.
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                points.add(GeoPoint(latitude, longitude))
            }
        }

        return points
    }

    /** Zwraca odczytaną różnicę i nową pozycję w łańcuchu, albo `null` przy urwanych danych. */
    private fun readValue(encoded: String, startIndex: Int): Pair<Int, Int>? {
        var index = startIndex
        var shift = 0
        var result = 0

        while (true) {
            if (index >= encoded.length) return null
            val chunk = encoded[index].code - 63
            index++
            result = result or ((chunk and 0x1f) shl shift)
            shift += 5
            if (chunk < 0x20) break
            if (shift > 30) return null
        }

        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return value to index
    }
}
