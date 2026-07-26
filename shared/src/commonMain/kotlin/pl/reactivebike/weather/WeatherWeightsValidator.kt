package pl.reactivebike.weather

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.reactivebike.routing.EdgeWeight
import pl.reactivebike.routing.RoutingWeights
import pl.reactivebike.routing.WeightTable

/** Powód odrzucenia odpowiedzi modelu AI. */
enum class RejectionReason {

    /** Brak odpowiedzi — puste ciało, timeout albo błąd sieci. */
    EMPTY_RESPONSE,

    /** Odpowiedź nie jest samodzielnym, poprawnym dokumentem JSON. */
    MALFORMED_JSON,

    /** JSON poprawny, ale któraś z wag jest bezużyteczna (nie-liczba, ujemna, zero, nieskończoność). */
    INVALID_WEIGHT,

    /** JSON poprawny, ale któryś z kluczy jest pusty. */
    BLANK_KEY,
}

/**
 * Wynik przetworzenia odpowiedzi modelu AI.
 *
 * Każdy wariant niesie gotowe do użycia [weights], więc wywołujący nigdy nie zostaje
 * bez wag — przy odrzuceniu dostaje [RoutingWeights.DEFAULT]. To realizuje fallback
 * wskazany jako otwarta kwestia w sekcji 11 specyfikacji.
 */
sealed interface WeatherWeightsResult {

    val weights: RoutingWeights

    /** Odpowiedź przyjęta; [weights] są już znormalizowane zgodnie z ADR-0002. */
    data class Accepted(
        override val weights: RoutingWeights,
        val uiNotification: String?,
    ) : WeatherWeightsResult

    /** Odpowiedź odrzucona; [weights] to wagi domyślne, nawigacja jedzie dalej bez adaptacji. */
    data class Rejected(
        val reason: RejectionReason,
        override val weights: RoutingWeights = RoutingWeights.DEFAULT,
    ) : WeatherWeightsResult
}

/**
 * Waliduje odpowiedź modelu AI i tłumaczy ją na [RoutingWeights].
 *
 * Zasady:
 * - odpowiedź musi być **samodzielnym** dokumentem JSON; tekst wokół (np. `Oto wynik: {...}`
 *   albo bloki ```json) jest odrzucany, bo sekcja 6.1 wymaga od modelu czystego JSON-a,
 * - nieznane klucze są tolerowane — pozwala to rozszerzać kontrakt bez psucia starszych klientów,
 * - każda waga musi być liczbą skończoną i dodatnią; `0` i wartości ujemne dają
 *   [RejectionReason.INVALID_WEIGHT]. `NaN` i nieskończoności odpada wcześniej, na
 *   parsowaniu — `Json` domyślnie ich nie dopuszcza — więc kontrola [Double.isFinite]
 *   jest tu zabezpieczeniem na wypadek zmiany konfiguracji [json],
 * - wagi `< 1.0` nie są błędem — zostają przeskalowane do postaci wyłącznie
 *   podwyższającej ([WeightTable.normalizedToIncreaseOnly], ADR-0002).
 */
class WeatherWeightsValidator(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun parse(rawResponse: String?): WeatherWeightsResult {
        if (rawResponse.isNullOrBlank()) {
            return WeatherWeightsResult.Rejected(RejectionReason.EMPTY_RESPONSE)
        }

        val response = try {
            json.decodeFromString<WeatherWeightsResponse>(rawResponse)
        } catch (_: SerializationException) {
            return WeatherWeightsResult.Rejected(RejectionReason.MALFORMED_JSON)
        }

        val overrides = listOf(response.surfaceOverrides, response.infrastructureOverrides)
        overrides.forEach { table ->
            rejectionFor(table)?.let { return WeatherWeightsResult.Rejected(it) }
        }

        val weights = RoutingWeights(
            surface = WeightTable(response.surfaceOverrides),
            infrastructure = WeightTable(response.infrastructureOverrides),
        ).normalizedToIncreaseOnly()

        return WeatherWeightsResult.Accepted(
            weights = weights,
            uiNotification = response.uiNotification.takeIf { it.isNotBlank() },
        )
    }

    private fun rejectionFor(overrides: Map<String, Double>): RejectionReason? {
        overrides.forEach { (key, weight) ->
            if (key.isBlank()) return RejectionReason.BLANK_KEY
            if (!weight.isFinite() || weight <= 0.0) return RejectionReason.INVALID_WEIGHT
        }
        return null
    }
}

/** Czy waga oznacza całkowity zakaz wjazdu. */
fun Double.isImpassable(): Boolean = this >= EdgeWeight.IMPASSABLE
