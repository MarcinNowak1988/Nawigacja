package pl.reactivebike.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.reactivebike.routing.RouteEngine
import pl.reactivebike.routing.RouteFailure
import pl.reactivebike.routing.RouteRequest
import pl.reactivebike.routing.RouteResult
import pl.reactivebike.routing.valhalla.ValhallaRouting
import pl.reactivebike.weather.WeatherConditions
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Implementacja portu [RouteEngine] oparta o publiczną instancję Valhalli.
 *
 * **Rozwiązanie przejściowe, świadomie łamiące offline-first z sekcji 8.** Sens tego kroku
 * jest taki, że port z ADR-0001 dostaje pierwszą prawdziwą implementację i weryfikuje się
 * jako abstrakcja: silnik na urządzeniu podmieni tę klasę, nie ruszając ani warstwy
 * wspólnej, ani interfejsu użytkownika.
 *
 * Sama treść zapytania i parsowanie odpowiedzi mieszkają w `shared` i są pokryte testami —
 * tutaj zostaje wyłącznie transport.
 */
class HttpRouteEngine(
    private val endpoint: String = ValhallaRouting.PUBLIC_ENDPOINT,
) : RouteEngine {

    /** Warunki pogodowe wpływają na profil rowerowy — patrz `BicycleCosting`. */
    var conditions: WeatherConditions? = null

    override suspend fun route(request: RouteRequest): RouteResult = withContext(Dispatchers.IO) {
        val body = ValhallaRouting.buildRequest(request, conditions)

        val response = try {
            post(body)
        } catch (_: Exception) {
            // Brak sieci, timeout, błąd DNS — dla nawigacji to wszystko jedno i to samo:
            // trasy nie ma. Wyjątek nie wychodzi poza silnik, zgodnie z kontraktem portu.
            return@withContext RouteResult.Failure(RouteFailure.ENGINE_ERROR)
        }

        ValhallaRouting.parse(response)
    }

    private fun post(body: String): String? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                // Valhalla opisuje odmowę w treści błędu — parser rozróżni „nie ma trasy”
                // od awarii po kodzie, więc czytamy także odpowiedzi 4xx.
                connection.errorStream
            }
            stream?.bufferedReader()?.use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000
    }
}
