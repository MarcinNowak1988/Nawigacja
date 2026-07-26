package pl.reactivebike.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kontrakt wyjściowy modułu AI (sekcja 6.2 specyfikacji).
 *
 * Model działa jako bezstanowy tłumacz warunków pogodowych na wagi i zwraca wyłącznie
 * poprawny JSON. Odpowiedzi nie należy używać wprost — przechodzi przez
 * [WeatherWeightsValidator], który waliduje wartości i normalizuje je zgodnie z ADR-0002.
 */
@Serializable
data class WeatherWeightsResponse(
    @SerialName("surface_overrides")
    val surfaceOverrides: Map<String, Double> = emptyMap(),

    @SerialName("infrastructure_overrides")
    val infrastructureOverrides: Map<String, Double> = emptyMap(),

    @SerialName("ui_notification")
    val uiNotification: String = "",
)
