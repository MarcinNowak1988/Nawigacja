package pl.reactivebike.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.reactivebike.geocoding.GeocodeResult
import pl.reactivebike.geocoding.Geocoder
import pl.reactivebike.geocoding.Nominatim
import pl.reactivebike.routing.GeoPoint
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wyszukiwanie miejsc po nazwie — transport do publicznej instancji Nominatim.
 *
 * Budowanie adresu i parsowanie odpowiedzi mieszkają w `shared` i są pokryte testami;
 * tutaj zostaje samo połączenie oraz **przestrzeganie zasad korzystania z usługi**, które
 * są warunkiem dostępu, a nie dobrą praktyką: identyfikujący `User-Agent` i co najwyżej
 * jedno zapytanie na sekundę.
 */
class HttpGeocoder : Geocoder {

    override val providerName = "OpenStreetMap"

    private var lastRequestAtMillis = 0L

    override suspend fun search(query: String, near: GeoPoint?): GeocodeResult = withContext(Dispatchers.IO) {
        val url = try {
            Nominatim.buildUrl(query, near)
        } catch (_: IllegalArgumentException) {
            return@withContext GeocodeResult.Failure
        }

        throttle()

        val response = try {
            get(url)
        } catch (_: Exception) {
            // Brak sieci, timeout, błąd DNS — dla wyszukiwania to wszystko jedno i to samo.
            return@withContext GeocodeResult.Failure
        }

        Nominatim.parse(response)
    }

    /**
     * Dopilnowuje minimalnego odstępu między zapytaniami.
     *
     * Czekamy, zamiast odrzucać zapytanie: użytkownik nacisnął „szukaj" i ma dostać wynik,
     * a nie komunikat o limicie, o którym nie ma pojęcia. Odstęp jest na tyle krótki,
     * że przy ręcznym wpisywaniu zwykle nikt go nie zauważy.
     */
    private suspend fun throttle() {
        val sinceLast = System.currentTimeMillis() - lastRequestAtMillis
        if (sinceLast in 0 until Nominatim.MIN_INTERVAL_MILLIS) {
            delay(Nominatim.MIN_INTERVAL_MILLIS - sinceLast)
        }
        lastRequestAtMillis = System.currentTimeMillis()
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("User-Agent", Nominatim.USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "pl,en")
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
