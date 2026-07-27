package pl.reactivebike.routing

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Średni promień Ziemi wg IUGG, w metrach. */
private const val EARTH_RADIUS_METERS = 6_371_008.8

private fun Double.toRadians(): Double = this * PI / 180.0

/**
 * Odległość po powierzchni kuli między dwoma punktami, w metrach.
 *
 * Wzór haversine — dla dystansów rowerowych błąd wynikający z przybliżenia Ziemi kulą
 * jest pomijalny (rzędu 0,3%), a wzór jest odporny numerycznie na małe odległości,
 * w odróżnieniu od naiwnego wzoru na cosinus kąta.
 */
fun GeoPoint.distanceTo(other: GeoPoint): Double {
    val dLat = (other.latitude - latitude).toRadians()
    val dLon = (other.longitude - longitude).toRadians()
    val lat1 = latitude.toRadians()
    val lat2 = other.latitude.toRadians()

    val a = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

/** Łączna długość łamanej, w metrach. */
fun List<GeoPoint>.pathLengthMeters(): Double {
    if (size < 2) return 0.0
    var total = 0.0
    for (i in 1 until size) {
        total += this[i - 1].distanceTo(this[i])
    }
    return total
}
