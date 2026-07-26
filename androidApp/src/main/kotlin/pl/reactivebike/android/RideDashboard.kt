package pl.reactivebike.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Warstwa widoków pulpitu, zbudowana w kodzie.
 *
 * Układ: pasek prędkości u góry, pod nim mapa, a najniżej przewijalne karty ze szczegółami.
 * Gdy mapa jest niedostępna, karty zajmują całą wysokość — aplikacja ma działać także wtedy,
 * gdy MapLibre nie wystartuje.
 *
 * Widoki powstają w kodzie, bez androidx i bez Compose. Same biblioteki androidx są już
 * w projekcie, ale wyłącznie tranzytywnie przez MapLibre — nie sięgamy po nie wprost.
 * Docelowy interfejs powstanie w Jetpack Compose (sekcja 3 specyfikacji).
 *
 * Klasa odpowiada wyłącznie za wygląd i trzyma referencje do pól, które [MainActivity]
 * aktualizuje przy każdym odświeżeniu. Nie zawiera logiki.
 */
class RideDashboard(private val context: Context) {

    private lateinit var speedValue: TextView
    private lateinit var speedCaption: TextView

    private val rows = mutableMapOf<String, TextView>()

    lateinit var refreshButton: Button
        private set

    lateinit var recenterButton: Button
        private set

    lateinit var offlineDownloadButton: Button
        private set

    lateinit var offlineDeleteButton: Button
        private set

    fun build(mapView: View?): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        root.addView(speedHeader())

        if (mapView != null) {
            root.addView(
                mapView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.5f),
            )
        }

        root.addView(
            cardsScroller(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        return root
    }

    /** Ustawia wartość wiersza; pusty tekst chowa wiersz, żeby pulpit nie puchł od „—”. */
    fun set(key: String, value: String?) {
        val view = rows[key] ?: return
        if (value.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.text = value
        }
    }

    fun setSpeed(value: String, caption: String) {
        speedValue.text = value
        speedCaption.text = caption
    }

    // --- budowanie widoków ---

    private fun speedHeader(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(ACCENT)
        setPadding(dp(18), dp(12), dp(14), dp(12))

        speedValue = TextView(context).apply {
            text = "—"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
        }
        addView(speedValue)

        speedCaption = TextView(context).apply {
            text = "km/h"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#C7D2FE"))
            setPadding(dp(10), dp(12), 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(speedCaption)

        recenterButton = Button(context).apply {
            text = "Wyśrodkuj"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        addView(recenterButton)
    }

    private fun cardsScroller(): ScrollView {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }

        column.addView(offlineCard())
        column.addView(card("Pozycja", listOf(KEY_COORDS, KEY_ACCURACY, KEY_ALTITUDE, KEY_FIX_AGE)))
        column.addView(card("Tryb GPS", listOf(KEY_GPS_STATE, KEY_GPS_RATE, KEY_GPS_REASON)))
        column.addView(card("Ciśnienie", listOf(KEY_PRESSURE, KEY_PRESSURE_TREND, KEY_STORM)))
        column.addView(
            card("Pogoda", listOf(KEY_WEATHER, KEY_TEMPERATURE, KEY_PRECIPITATION, KEY_WIND, KEY_WEATHER_AGE)),
        )
        column.addView(
            card("Wagi trasowania", listOf(KEY_WEIGHTS_SOURCE, KEY_WEIGHTS_REASON, KEY_WEIGHTS_DETAIL)),
        )

        refreshButton = Button(context).apply {
            text = "Odśwież pogodę"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        column.addView(refreshButton)

        column.addView(
            TextView(context).apply {
                text = "Wersja poglądowa. Mapa i ślad przejazdu działają; wyznaczania trasy " +
                    "i nawigacji zakrętowej jeszcze nie ma."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(MUTED)
                setPadding(dp(4), dp(14), dp(4), 0)
            },
        )

        return ScrollView(context).apply {
            setBackgroundColor(BACKGROUND)
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** Karta map offline — jako jedyna zawiera akcje, bo pobieranie jest czynnością użytkownika. */
    private fun offlineCard(): LinearLayout {
        val container = card("Mapa offline", listOf(KEY_OFFLINE_STATUS, KEY_OFFLINE_DETAIL))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        offlineDownloadButton = Button(context).apply {
            text = "Pobierz widoczny obszar"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        offlineDeleteButton = Button(context).apply {
            text = "Usuń"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        actions.addView(offlineDownloadButton)
        actions.addView(offlineDeleteButton)
        container.addView(actions)

        return container
    }

    private fun card(title: String, keys: List<String>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            addView(
                TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(MUTED)
                    letterSpacing = 0.08f
                },
            )

            keys.forEach { key ->
                val row = TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(TEXT)
                    setPadding(0, dp(5), 0, 0)
                    text = "—"
                    visibility = View.GONE
                }
                rows[key] = row
                addView(row)
            }
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.parseColor("#F1F5F9")
        private val ACCENT = Color.parseColor("#1E293B")
        private val TEXT = Color.parseColor("#0F172A")
        private val MUTED = Color.parseColor("#64748B")

        const val KEY_COORDS = "coords"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_ALTITUDE = "altitude"
        const val KEY_FIX_AGE = "fixAge"

        const val KEY_GPS_STATE = "gpsState"
        const val KEY_GPS_RATE = "gpsRate"
        const val KEY_GPS_REASON = "gpsReason"

        const val KEY_PRESSURE = "pressure"
        const val KEY_PRESSURE_TREND = "pressureTrend"
        const val KEY_STORM = "storm"

        const val KEY_WEATHER = "weather"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_PRECIPITATION = "precipitation"
        const val KEY_WIND = "wind"
        const val KEY_WEATHER_AGE = "weatherAge"

        const val KEY_OFFLINE_STATUS = "offlineStatus"
        const val KEY_OFFLINE_DETAIL = "offlineDetail"

        const val KEY_WEIGHTS_SOURCE = "weightsSource"
        const val KEY_WEIGHTS_REASON = "weightsReason"
        const val KEY_WEIGHTS_DETAIL = "weightsDetail"
    }
}
