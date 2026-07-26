package pl.reactivebike.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenMeteoTest {

    /** Kształt odpowiedzi zgodny z dokumentacją Open-Meteo dla parametru `current`. */
    private val realResponse = """
        {
          "latitude": 50.0625,
          "longitude": 19.9375,
          "generationtime_ms": 0.05,
          "utc_offset_seconds": 0,
          "timezone": "GMT",
          "elevation": 219.0,
          "current_units": {
            "time": "iso8601",
            "temperature_2m": "°C",
            "precipitation": "mm",
            "weather_code": "wmo code",
            "wind_speed_10m": "km/h",
            "surface_pressure": "hPa"
          },
          "current": {
            "time": "2026-07-26T20:00",
            "interval": 900,
            "temperature_2m": 18.3,
            "precipitation": 0.0,
            "weather_code": 3,
            "wind_speed_10m": 12.4,
            "surface_pressure": 1008.2
          }
        }
    """.trimIndent()

    // --- budowanie adresu ---

    @Test
    fun `adres zawiera pozycje i wymagane zmienne`() {
        val url = OpenMeteoClient.currentWeatherUrl(50.0647, 19.945)

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast"), url)
        assertTrue(url.contains("latitude=50.0647"), url)
        assertTrue(url.contains("longitude=19.945"), url)
        assertTrue(url.contains("surface_pressure"), url)
        assertTrue(url.contains("precipitation"), url)
    }

    @Test
    fun `wspolrzedne sa ucinane do czterech miejsc`() {
        val url = OpenMeteoClient.currentWeatherUrl(50.06471234567, 19.94509876)

        assertTrue(url.contains("latitude=50.0647"), url)
        assertTrue(url.contains("longitude=19.945"), url)
    }

    @Test
    fun `ujemne wspolrzedne sa obslugiwane`() {
        val url = OpenMeteoClient.currentWeatherUrl(-33.8688, -151.2093)

        assertTrue(url.contains("latitude=-33.8688"), url)
        assertTrue(url.contains("longitude=-151.2093"), url)
    }

    // --- parsowanie ---

    @Test
    fun `realna odpowiedz jest parsowana`() {
        val result = assertIs<WeatherFetchResult.Success>(OpenMeteoClient.parse(realResponse))
        val c = result.conditions

        assertEquals(18.3, c.temperatureCelsius)
        assertEquals(0.0, c.precipitationMm)
        assertEquals(12.4, c.windSpeedKmh)
        assertEquals(1008.2, c.surfacePressureHpa)
        assertEquals(3, c.weatherCode)
        assertEquals("2026-07-26T20:00", c.observedAt)
    }

    @Test
    fun `nieznane pola nie psuja parsowania`() {
        val result = OpenMeteoClient.parse(realResponse)

        assertIs<WeatherFetchResult.Success>(result)
    }

    @Test
    fun `brak pojedynczej zmiennej nie uniewaznia odpowiedzi`() {
        val partial = """{ "current": { "time": "2026-07-26T20:00", "temperature_2m": 5.0 } }"""

        val result = assertIs<WeatherFetchResult.Success>(OpenMeteoClient.parse(partial))

        assertEquals(5.0, result.conditions.temperatureCelsius)
        assertEquals(null, result.conditions.precipitationMm)
    }

    @Test
    fun `brak sekcji current jest bledem`() {
        val result = assertIs<WeatherFetchResult.Failure>(OpenMeteoClient.parse("""{ "latitude": 50.0 }"""))

        assertTrue(result.reason.contains("current"), result.reason)
    }

    @Test
    fun `pusta odpowiedz jest bledem`() {
        assertIs<WeatherFetchResult.Failure>(OpenMeteoClient.parse(null))
        assertIs<WeatherFetchResult.Failure>(OpenMeteoClient.parse("   "))
    }

    @Test
    fun `niepoprawny JSON jest bledem`() {
        assertIs<WeatherFetchResult.Failure>(OpenMeteoClient.parse("<html>blad</html>"))
    }

    // --- interpretacja warunków ---

    @Test
    fun `brak opadow to nie deszcz`() {
        assertFalse(WeatherConditions(precipitationMm = 0.0).isRaining)
        assertFalse(WeatherConditions(precipitationMm = 0.1).isRaining)
    }

    @Test
    fun `opad powyzej progu to deszcz`() {
        assertTrue(WeatherConditions(precipitationMm = 0.2).isRaining)
        assertTrue(WeatherConditions(precipitationMm = 1.0).isRaining)
    }

    @Test
    fun `opad powyzej dwoch milimetrow to ulewa`() {
        assertFalse(WeatherConditions(precipitationMm = 1.9).isHeavyRain)
        assertTrue(WeatherConditions(precipitationMm = 2.0).isHeavyRain)
    }

    @Test
    fun `ulewa jest takze deszczem`() {
        val heavy = WeatherConditions(precipitationMm = 5.0)

        assertTrue(heavy.isRaining)
        assertTrue(heavy.isHeavyRain)
    }

    @Test
    fun `brak danych o opadach nie oznacza deszczu`() {
        assertFalse(WeatherConditions().isRaining)
    }

    @Test
    fun `temperatura wokol zera to ryzyko oblodzenia`() {
        assertTrue(WeatherConditions(temperatureCelsius = 0.5).isFreezing)
        assertTrue(WeatherConditions(temperatureCelsius = -3.0).isFreezing)
        assertFalse(WeatherConditions(temperatureCelsius = 5.0).isFreezing)
    }

    @Test
    fun `brak danych o temperaturze nie oznacza mrozu`() {
        assertFalse(WeatherConditions().isFreezing)
    }

    @Test
    fun `kody WMO sa tlumaczone na opis`() {
        assertEquals("bezchmurnie", WeatherConditions(weatherCode = 0).description)
        assertEquals("ulewny deszcz", WeatherConditions(weatherCode = 65).description)
        assertEquals("burza", WeatherConditions(weatherCode = 95).description)
        assertEquals("brak danych", WeatherConditions().description)
    }
}
