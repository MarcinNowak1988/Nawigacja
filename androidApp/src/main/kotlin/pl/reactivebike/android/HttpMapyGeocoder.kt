package pl.reactivebike.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.reactivebike.geocoding.GeocodeResult
import pl.reactivebike.geocoding.Geocoder
import pl.reactivebike.geocoding.MapyGeocoding
import pl.reactivebike.routing.GeoPoint
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wyszukiwanie miejsc przez Mapy.com — transport.
 *
 * Wybrane zamiast Nominatima dlatego, że w Europie Środkowej trafniej rozpoznaje polskie
 * adresy. Zmiana dotyczy **wyłącznie wyszukiwania**: kafelki mapy i trasowanie zostają
 * na OpenStreetMap, bo regulamin Mapy.com zabrania buforowania kafelków, a to zabiłoby
 * zapisane regiony offline (ADR-0008).
 *
 * Budowanie adresu i parsowanie mieszkają w `shared` i są pokryte testami — tutaj zostaje
 * połączenie oraz pilnowanie, żeby **klucz API nie wyciekł do logów**.
 */
class HttpMapyGeocoder(private val apiKey: String) : Geocoder {

    override val providerName = MapyGeocoding.PROVIDER_NAME

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult = withContext(Dispatchers.IO) {
        val url = try {
            MapyGeocoding.buildUrl(query, apiKey, near)
        } catch (_: IllegalArgumentException) {
            return@withContext GeocodeResult.Failure
        }

        val response = try {
            get(url)
        } catch (_: Exception) {
            // Treści wyjątku celowo nie przekazujemy dalej: potrafi zawierać pełny adres
            // wraz z kluczem, a stąd trafiłaby do logu.
            return@withContext GeocodeResult.Failure
        }

        MapyGeocoding.parse(response)
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream?.bufferedReader()?.use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}
