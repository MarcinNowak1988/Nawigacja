package pl.reactivebike.weather

import pl.reactivebike.routing.EdgeWeight
import pl.reactivebike.routing.RoutingWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherWeightsValidatorTest {

    private val validator = WeatherWeightsValidator()

    /** Dokładny przykład odpowiedzi z sekcji 6.2 specyfikacji. */
    private val exampleResponse = """
        {
          "surface_overrides": { "mud": 999.0, "asphalt": 0.5 },
          "infrastructure_overrides": { "cycleway": 0.5 },
          "ui_notification": "Wykryto ulewe. Szukam asfaltu."
        }
    """.trimIndent()

    @Test
    fun `przyklad z sekcji 6_2 jest przyjmowany`() {
        val result = assertIs<WeatherWeightsResult.Accepted>(validator.parse(exampleResponse))

        assertEquals("Wykryto ulewe. Szukam asfaltu.", result.uiNotification)
    }

    @Test
    fun `zakaz wjazdu przechodzi przez walidacje nienaruszony`() {
        val result = assertIs<WeatherWeightsResult.Accepted>(validator.parse(exampleResponse))

        assertTrue(result.weights.surface.weightFor("mud").isImpassable())
    }

    @Test
    fun `wagi ponizej neutralnej sa normalizowane a nie odrzucane`() {
        val result = assertIs<WeatherWeightsResult.Accepted>(validator.parse(exampleResponse))

        val asphalt = result.weights.surface.weightFor("asphalt")
        val default = result.weights.surface.default

        assertTrue(asphalt >= EdgeWeight.NEUTRAL, "asfalt: $asphalt")
        assertTrue(default > asphalt, "domyslna $default powinna byc wyzsza od asfaltu $asphalt")
    }

    @Test
    fun `odpowiedz bez nadpisan jest poprawna i daje wagi domyslne`() {
        val result = assertIs<WeatherWeightsResult.Accepted>(
            validator.parse("""{ "ui_notification": "Pogoda bez zmian." }"""),
        )

        assertEquals(RoutingWeights.DEFAULT, result.weights)
    }

    @Test
    fun `puste powiadomienie jest zwracane jako null`() {
        val result = assertIs<WeatherWeightsResult.Accepted>(
            validator.parse("""{ "surface_overrides": { "mud": 999.0 } }"""),
        )

        assertNull(result.uiNotification)
    }

    @Test
    fun `nieznane klucze nie psuja parsowania`() {
        val result = validator.parse(
            """{ "surface_overrides": { "mud": 999.0 }, "model_version": "2026-07" }""",
        )

        assertIs<WeatherWeightsResult.Accepted>(result)
    }

    @Test
    fun `brak odpowiedzi konczy sie fallbackiem na wagi domyslne`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(validator.parse(null))

        assertEquals(RejectionReason.EMPTY_RESPONSE, result.reason)
        assertEquals(RoutingWeights.DEFAULT, result.weights)
    }

    @Test
    fun `pusty tekst konczy sie fallbackiem`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(validator.parse("   "))

        assertEquals(RejectionReason.EMPTY_RESPONSE, result.reason)
    }

    @Test
    fun `tekst wokol JSON-a jest odrzucany`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""Oto wagi: { "surface_overrides": { "mud": 999.0 } }"""),
        )

        assertEquals(RejectionReason.MALFORMED_JSON, result.reason)
    }

    @Test
    fun `blok markdown wokol JSON-a jest odrzucany`() {
        val wrapped = "```json\n{ \"surface_overrides\": { \"mud\": 999.0 } }\n```"

        val result = assertIs<WeatherWeightsResult.Rejected>(validator.parse(wrapped))

        assertEquals(RejectionReason.MALFORMED_JSON, result.reason)
    }

    @Test
    fun `uciety JSON jest odrzucany`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "surface_overrides": { "mud": 999.0 """),
        )

        assertEquals(RejectionReason.MALFORMED_JSON, result.reason)
    }

    @Test
    fun `waga ujemna jest odrzucana`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "surface_overrides": { "asphalt": -1.0 } }"""),
        )

        assertEquals(RejectionReason.INVALID_WEIGHT, result.reason)
    }

    @Test
    fun `waga zerowa jest odrzucana`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "surface_overrides": { "asphalt": 0.0 } }"""),
        )

        assertEquals(RejectionReason.INVALID_WEIGHT, result.reason)
    }

    /**
     * Liczba spoza zakresu `Double` jest odrzucana już na poziomie parsowania — `Json`
     * domyślnie nie dopuszcza nieskończoności (`allowSpecialFloatingPointValues = false`),
     * więc do walidacji wartości sprawa w ogóle nie dochodzi. Istotne jest to, że
     * odpowiedź zostaje odrzucona, a nawigacja dostaje wagi domyślne.
     */
    @Test
    fun `waga poza zakresem Double jest odrzucana`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "surface_overrides": { "asphalt": 1e400 } }"""),
        )

        assertEquals(RejectionReason.MALFORMED_JSON, result.reason)
        assertEquals(RoutingWeights.DEFAULT, result.weights)
    }

    @Test
    fun `literal NaN jest odrzucany`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "surface_overrides": { "asphalt": NaN } }"""),
        )

        assertEquals(RejectionReason.MALFORMED_JSON, result.reason)
    }

    @Test
    fun `pusty klucz nawierzchni jest odrzucany`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "surface_overrides": { "": 2.0 } }"""),
        )

        assertEquals(RejectionReason.BLANK_KEY, result.reason)
    }

    @Test
    fun `bledna waga infrastruktury tez jest wychwytywana`() {
        val result = assertIs<WeatherWeightsResult.Rejected>(
            validator.parse("""{ "infrastructure_overrides": { "cycleway": -3.0 } }"""),
        )

        assertEquals(RejectionReason.INVALID_WEIGHT, result.reason)
    }

    @Test
    fun `kazdy wynik niesie wagi gotowe do uzycia`() {
        val responses = listOf(null, "", "nie-json", """{ "surface_overrides": { "x": 0.0 } }""", exampleResponse)

        responses.forEach { raw ->
            val weights = validator.parse(raw).weights
            val all = listOf(weights.surface, weights.infrastructure)
                .flatMap { it.overrides.values + it.default }

            assertTrue(all.all { it >= EdgeWeight.NEUTRAL }, "odpowiedz $raw dala wagi $all")
        }
    }
}
