package pl.reactivebike.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Warstwa widoków pulpitu, zbudowana w kodzie.
 *
 * Moduł nie korzysta z androidx ani Compose — widoki powstają programowo, dzięki czemu
 * aplikacja nie wnosi zależności, których wersji nie dałoby się zweryfikować przed
 * pierwszym buildem. Docelowy interfejs powstanie w Jetpack Compose (sekcja 3 specyfikacji).
 *
 * Klasa odpowiada wyłącznie za wygląd i trzyma referencje do pól, które [MainActivity]
 * aktualizuje przy każdym odświeżeniu. Nie zawiera logiki.
 */
class RideDashboard(private val context: Context) {

    lateinit var root: ScrollView
        private set

    private lateinit var speedValue: TextView
    private lateinit var speedCaption: TextView

    private val rows = mutableMapOf<String, TextView>()

    lateinit var refreshButton: Button
        private set

    fun build(): ScrollView {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        column.addView(speedHeader())

        column.addView(
            card(
                "Pozycja",
                listOf(KEY_COORDS, KEY_ACCURACY, KEY_ALTITUDE, KEY_FIX_AGE),
            ),
        )
        column.addView(
            card(
                "Tryb GPS",
                listOf(KEY_GPS_STATE, KEY_GPS_RATE, KEY_GPS_REASON),
            ),
        )
        column.addView(
            card(
                "Ciśnienie",
                listOf(KEY_PRESSURE, KEY_PRESSURE_TREND, KEY_STORM),
            ),
        )
        column.addView(
            card(
                "Pogoda",
                listOf(KEY_WEATHER, KEY_TEMPERATURE, KEY_PRECIPITATION, KEY_WIND, KEY_WEATHER_AGE),
            ),
        )
        column.addView(
            card(
                "Wagi trasowania",
                listOf(KEY_WEIGHTS_SOURCE, KEY_WEIGHTS_REASON, KEY_WEIGHTS_DETAIL),
            ),
        )

        refreshButton = Button(context).apply {
            text = "Odśwież pogodę"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        column.addView(refreshButton)

        column.addView(
            TextView(context).apply {
                text = "Wersja poglądowa — bez mapy i trasowania. " +
                    "Pulpit pokazuje logikę z modułu wspólnego działającą na żywych czujnikach."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(MUTED)
                setPadding(dp(4), dp(16), dp(4), 0)
            },
        )

        root = ScrollView(context).apply {
            setBackgroundColor(BACKGROUND)
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return root
    }

    /** Ustawia wartość wiersza; pusty tekst chowa wiersz, żeby pulpit nie puchł od „—”. */
    fun set(key: String, value: String?) {
        val view = rows[key] ?: return
        if (value.isNullOrBlank()) {
            view.visibility = TextView.GONE
        } else {
            view.visibility = TextView.VISIBLE
            view.text = value
        }
    }

    fun setSpeed(value: String, caption: String) {
        speedValue.text = value
        speedCaption.text = caption
    }

    // --- budowanie widoków ---

    private fun speedHeader(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = cardBackground(ACCENT_CARD)
        setPadding(dp(16), dp(20), dp(16), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }

        speedValue = TextView(context).apply {
            text = "—"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        speedCaption = TextView(context).apply {
            text = "km/h"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#C7D2FE"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        addView(speedValue)
        addView(speedCaption)
    }

    private fun card(title: String, keys: List<String>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(CARD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }

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
                    setPadding(0, dp(6), 0, 0)
                    text = "—"
                    visibility = TextView.GONE
                }
                rows[key] = row
                addView(row)
            }
        }

    private fun cardBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.parseColor("#F1F5F9")
        private val CARD = Color.WHITE
        private val ACCENT_CARD = Color.parseColor("#1E293B")
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

        const val KEY_WEIGHTS_SOURCE = "weightsSource"
        const val KEY_WEIGHTS_REASON = "weightsReason"
        const val KEY_WEIGHTS_DETAIL = "weightsDetail"
    }
}
