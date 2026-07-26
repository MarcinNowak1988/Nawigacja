package pl.reactivebike.android

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import pl.reactivebike.gps.GpsState
import pl.reactivebike.gps.GpsStateMachine
import pl.reactivebike.gps.RideSignals
import pl.reactivebike.routing.Edge
import pl.reactivebike.routing.EdgeCost
import pl.reactivebike.routing.EdgeCostCalculator
import pl.reactivebike.routing.RoutingWeights
import pl.reactivebike.weather.CachedWeatherWeights
import pl.reactivebike.weather.OfflineWeatherPolicy
import pl.reactivebike.weather.PressureReading
import pl.reactivebike.weather.WeatherWeightsResult
import pl.reactivebike.weather.WeatherWeightsSource
import pl.reactivebike.weather.WeatherWeightsValidator

/**
 * Ekran diagnostyczny warstwy wspólnej.
 *
 * **To nie jest interfejs nawigacji.** Aplikacja nie ma jeszcze mapy, trasowania ani UI
 * z sekcji 3 specyfikacji. Ten ekran istnieje po to, by potwierdzić, że logika z modułu
 * `shared` uruchamia się na urządzeniu z Androidem, i by pipeline CI miał co zapakować
 * do APK. Docelowy interfejs powstanie w Jetpack Compose.
 *
 * Świadomie nie użyto tu żadnych bibliotek androidx — widoki budowane są w kodzie,
 * dzięki czemu moduł nie wnosi zależności, których wersji nie dałoby się zweryfikować.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }

        content.addView(title("ReactiveBike"))
        content.addView(subtitle("Ekran diagnostyczny warstwy wspólnej — nie interfejs nawigacji"))

        section(content, "Maszyna stanów GPS (sekcja 7)", gpsDemo())
        section(content, "Funkcja kosztu krawędzi (sekcja 5.1)", edgeCostDemo())
        section(content, "Walidacja odpowiedzi modułu AI (sekcja 6.4)", validatorDemo())
        section(content, "Degradacja offline (sekcja 8)", offlineDemo())

        setContentView(
            ScrollView(this).apply {
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    // --- demonstracje logiki wspólnej ---

    private fun gpsDemo(): String {
        val riding = RideSignals(
            distanceToManeuverMeters = 5_000.0,
            speedKmh = 22.0,
            screenOn = true,
            motionDetected = true,
        )
        val approaching = riding.copy(distanceToManeuverMeters = 180.0)
        val stopped = riding.copy(speedKmh = 0.0, motionDetected = false)
        val slowClimb = riding.copy(speedKmh = 2.4, motionDetected = true)

        return buildString {
            appendLine("jazda, manewr daleko        -> ${describe(GpsStateMachine.next(GpsState.CRUISE, riding))}")
            appendLine("manewr 180 m                -> ${describe(GpsStateMachine.next(GpsState.CRUISE, approaching))}")
            appendLine("postój                      -> ${describe(GpsStateMachine.next(GpsState.CRUISE, stopped))}")
            append("podjazd 2,4 km/h            -> ${describe(GpsStateMachine.next(GpsState.CRUISE, slowClimb))}")
        }
    }

    private fun describe(state: GpsState): String {
        val interval = state.samplingIntervalSeconds?.let { "co ${it}s" } ?: "GPS wyłączony"
        return "$state ($interval)"
    }

    private fun edgeCostDemo(): String {
        val validator = WeatherWeightsValidator()
        val response = """
            {
              "surface_overrides": { "mud": 999.0, "asphalt": 0.5 },
              "infrastructure_overrides": { "cycleway": 0.5 },
              "ui_notification": "Wykryto ulewę. Szukam asfaltu."
            }
        """.trimIndent()

        val weights = validator.parse(response).weights
        val calculator = EdgeCostCalculator(weights)

        val cycleway = calculator.costOf(
            Edge(distanceMeters = 1_000.0, surface = "asphalt", infrastructure = "cycleway"),
        )
        val plain = calculator.costOf(Edge(distanceMeters = 1_000.0))
        val mud = calculator.costOf(Edge(distanceMeters = 1_000.0, surface = "mud"))

        return buildString {
            appendLine("1 km ścieżki asfaltowej     -> ${format(cycleway)}")
            appendLine("1 km drogi domyślnej        -> ${format(plain)}")
            appendLine("1 km przez błoto            -> ${format(mud)}")
            append("stosunek ścieżka / domyślna -> ${ratio(cycleway, plain)}")
        }
    }

    private fun format(cost: EdgeCost): String = when (cost) {
        is EdgeCost.Passable -> "koszt ${cost.cost.toInt()}"
        EdgeCost.Impassable -> "wykluczona"
    }

    private fun ratio(a: EdgeCost, b: EdgeCost): String {
        if (a !is EdgeCost.Passable || b !is EdgeCost.Passable) return "nieporównywalne"
        val value = a.cost / b.cost
        return "${(value * 100).toInt() / 100.0}x"
    }

    private fun validatorDemo(): String {
        val validator = WeatherWeightsValidator()
        val cases = listOf(
            "poprawny JSON" to """{ "surface_overrides": { "mud": 999.0 } }""",
            "tekst wokół JSON-a" to """Oto wagi: { "surface_overrides": {} }""",
            "waga ujemna" to """{ "surface_overrides": { "asphalt": -1.0 } }""",
            "brak odpowiedzi" to "",
        )

        return cases.joinToString("\n") { (label, raw) ->
            val verdict = when (val result = validator.parse(raw)) {
                is WeatherWeightsResult.Accepted -> "przyjęte"
                is WeatherWeightsResult.Rejected -> "odrzucone (${result.reason})"
            }
            label.padEnd(20) + "-> $verdict"
        }
    }

    private fun offlineDemo(): String {
        val policy = OfflineWeatherPolicy()
        val now = System.currentTimeMillis() / 1_000
        val cached = CachedWeatherWeights(
            weights = RoutingWeights.DEFAULT,
            forecastIssuedAtEpochSeconds = now - 1_800,
        )
        val calm = listOf(
            PressureReading(1013.0, now - 7_200),
            PressureReading(1013.1, now),
        )
        val falling = listOf(
            PressureReading(1013.0, now - 7_200),
            PressureReading(1008.5, now),
        )

        fun label(source: WeatherWeightsSource): String = when (source) {
            is WeatherWeightsSource.StormMode -> "Storm Mode (spadek ${source.dropHpa} hPa)"
            is WeatherWeightsSource.Cached -> "wagi ze zbuforowanej prognozy"
            is WeatherWeightsSource.Default -> "wagi domyślne"
        }

        return buildString {
            appendLine("bufor świeży, ciśnienie stabilne -> ${label(policy.resolve(cached, calm, now))}")
            appendLine("bufor świeży, ciśnienie spada    -> ${label(policy.resolve(cached, falling, now))}")
            append("brak bufora, ciśnienie stabilne  -> ${label(policy.resolve(null, calm, now))}")
        }
    }

    // --- budowanie widoków ---

    private fun section(parent: LinearLayout, heading: String, body: String) {
        parent.addView(sectionTitle(heading))
        parent.addView(monospace(body))
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(20))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun monospace(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
