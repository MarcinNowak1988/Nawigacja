package pl.reactivebike.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Bieżące warunki pogodowe sprowadzone do tego, co wpływa na trasowanie.
 *
 * Wszystkie pola są opcjonalne, bo Open-Meteo potrafi pominąć pojedynczą zmienną —
 * brak jednej wartości nie może unieważniać całej odpowiedzi.
 */
data class WeatherConditions(
    val temperatureCelsius: Double? = null,
    val precipitationMm: Double? = null,
    val windSpeedKmh: Double? = null,
    val surfacePressureHpa: Double? = null,
    val weatherCode: Int? = null,
    val observedAt: String? = null,
) {

    /** Czy pada na tyle, by miało to znaczenie dla nawierzchni. */
    val isRaining: Boolean
        get() = (precipitationMm ?: 0.0) >= LIGHT_RAIN_MM

    /** Czy opad jest ulewny. */
    val isHeavyRain: Boolean
        get() = (precipitationMm ?: 0.0) >= HEAVY_RAIN_MM

    /** Czy istnieje ryzyko oblodzenia. */
    val isFreezing: Boolean
        get() = (temperatureCelsius ?: Double.MAX_VALUE) <= FREEZING_CELSIUS

    /** Opis słowny na podstawie kodu WMO zwracanego przez Open-Meteo. */
    val description: String
        get() = when (weatherCode) {
            null -> "brak danych"
            0 -> "bezchmurnie"
            1 -> "przeważnie bezchmurnie"
            2 -> "częściowe zachmurzenie"
            3 -> "pochmurno"
            45, 48 -> "mgła"
            51, 53, 55 -> "mżawka"
            56, 57 -> "marznąca mżawka"
            61 -> "słaby deszcz"
            63 -> "deszcz"
            65 -> "ulewny deszcz"
            66, 67 -> "marznący deszcz"
            71 -> "słabe opady śniegu"
            73 -> "opady śniegu"
            75 -> "intensywne opady śniegu"
            77 -> "krupa śnieżna"
            80 -> "przelotny deszcz"
            81 -> "silny przelotny deszcz"
            82 -> "gwałtowna ulewa"
            85, 86 -> "przelotne opady śniegu"
            95 -> "burza"
            96, 99 -> "burza z gradem"
            else -> "kod $weatherCode"
        }

    companion object {
        /** Próg, od którego uznajemy, że pada. */
        const val LIGHT_RAIN_MM: Double = 0.2

        /** Próg ulewy. */
        const val HEAVY_RAIN_MM: Double = 2.0

        /** Próg ryzyka oblodzenia. */
        const val FREEZING_CELSIUS: Double = 1.0
    }
}

/** Wynik pobrania i sparsowania prognozy. */
sealed interface WeatherFetchResult {
    data class Success(val conditions: WeatherConditions) : WeatherFetchResult
    data class Failure(val reason: String) : WeatherFetchResult
}

@kotlinx.serialization.Serializable
internal data class OpenMeteoResponse(
    val current: OpenMeteoCurrent? = null,
)

@kotlinx.serialization.Serializable
internal data class OpenMeteoCurrent(
    val time: String? = null,
    @SerialName("temperature_2m") val temperatureCelsius: Double? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("wind_speed_10m") val windSpeedKmh: Double? = null,
    @SerialName("surface_pressure") val surfacePressureHpa: Double? = null,
)

/**
 * Klient Open-Meteo w części niezależnej od platformy.
 *
 * Sam transport HTTP należy do warstwy natywnej — tutaj mieszka budowanie adresu
 * i parsowanie odpowiedzi, dzięki czemu jedno i drugie da się pokryć testami bez sieci.
 * Open-Meteo nie wymaga klucza API.
 */
object OpenMeteoClient {

    /** Adres zapytania o bieżące warunki dla zadanej pozycji. */
    fun currentWeatherUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${format(latitude)}" +
            "&longitude=${format(longitude)}" +
            "&current=temperature_2m,precipitation,weather_code,wind_speed_10m,surface_pressure" +
            "&timezone=UTC"

    fun parse(rawResponse: String?): WeatherFetchResult {
        if (rawResponse.isNullOrBlank()) {
            return WeatherFetchResult.Failure("pusta odpowiedz")
        }

        val response = try {
            json.decodeFromString<OpenMeteoResponse>(rawResponse)
        } catch (_: SerializationException) {
            return WeatherFetchResult.Failure("niepoprawny JSON")
        }

        val current = response.current
            ?: return WeatherFetchResult.Failure("brak sekcji 'current'")

        return WeatherFetchResult.Success(
            WeatherConditions(
                temperatureCelsius = current.temperatureCelsius,
                precipitationMm = current.precipitation,
                windSpeedKmh = current.windSpeedKmh,
                surfacePressureHpa = current.surfacePressureHpa,
                weatherCode = current.weatherCode,
                observedAt = current.time,
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Ucina współrzędną do czterech miejsc — ok. 11 m, więcej Open-Meteo i tak nie wykorzysta. */
    private fun format(value: Double): String {
        val rounded = (value * 10_000).toLong() / 10_000.0
        return rounded.toString()
    }
}
