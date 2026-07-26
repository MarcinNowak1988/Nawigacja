package pl.reactivebike.weather

import pl.reactivebike.routing.RoutingWeights
import pl.reactivebike.routing.WeightTable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Pojedynczy odczyt z natywnego barometru.
 *
 * @property hectopascals ciśnienie w hPa
 * @property atEpochSeconds moment odczytu
 */
data class PressureReading(
    val hectopascals: Double,
    val atEpochSeconds: Long,
) {
    init {
        require(hectopascals.isFinite() && hectopascals > 0.0) {
            "Cisnienie musi byc dodatnie i skonczone, otrzymano: $hectopascals"
        }
    }
}

/** Ocena trendu ciśnienia. */
sealed interface StormVerdict {

    /** Wykryto gwałtowny spadek ciśnienia o [dropHpa] hPa w oknie obserwacji. */
    data class StormApproaching(val dropHpa: Double) : StormVerdict

    /** Ciśnienie stabilne albo rosnące. */
    data object Calm : StormVerdict

    /** Za mało odczytów w oknie, żeby cokolwiek orzec. */
    data object InsufficientData : StormVerdict
}

/**
 * Wykrywa nadciągającą burzę po spadku ciśnienia (sekcja 8 specyfikacji).
 *
 * Specyfikacja podaje próg „> 2 hPa", ale nie mówi, w jakim czasie — a bez okna czasowego
 * próg nic nie znaczy, bo 2 hPa w ciągu doby to zmiana pogody, a 2 hPa w ciągu godziny to
 * front. Przyjęto okno **3 godzin**, zgodnie z konwencją meteorologiczną, w której szybki
 * spadek ciśnienia definiuje się właśnie jako 2 hPa na 3 godziny. Oba parametry są
 * konfigurowalne, by dało się je dostroić na podstawie danych z realnych przejazdów.
 *
 * Detektor jest czystą funkcją: porównuje najwyższy odczyt w oknie z odczytem najnowszym.
 * Bierze maksimum, a nie odczyt najstarszy, żeby wychwycić spadek również wtedy, gdy
 * ciśnienie najpierw jeszcze rosło.
 */
class StormDetector(
    private val dropThresholdHpa: Double = DEFAULT_DROP_THRESHOLD_HPA,
    private val window: Duration = DEFAULT_WINDOW,
) {

    fun evaluate(readings: List<PressureReading>, nowEpochSeconds: Long): StormVerdict {
        val inWindow = readings.filter {
            val age = (nowEpochSeconds - it.atEpochSeconds).seconds
            !age.isNegative() && age <= window
        }

        if (inWindow.size < 2) return StormVerdict.InsufficientData

        val latest = inWindow.maxBy { it.atEpochSeconds }
        val peak = inWindow.maxOf { it.hectopascals }
        val drop = peak - latest.hectopascals

        return if (drop > dropThresholdHpa) {
            StormVerdict.StormApproaching(drop)
        } else {
            StormVerdict.Calm
        }
    }

    companion object {
        /** Próg spadku ciśnienia z sekcji 8 specyfikacji. */
        const val DEFAULT_DROP_THRESHOLD_HPA: Double = 2.0

        /** Okno obserwacji — konwencja meteorologiczna dla „szybkiego spadku". */
        val DEFAULT_WINDOW: Duration = 3.hours
    }
}

/**
 * Wagi trybu ucieczki przed burzą.
 *
 * Zgodnie z sekcją 8 tryb podnosi wagi odstraszające dla nawierzchni podatnych na
 * rozmoknięcie. Wartości są **punktem wyjścia do dostrojenia**, nie wynikiem pomiarów —
 * warto je zweryfikować na realnych przejazdach, zanim trafią do wydania produkcyjnego.
 *
 * Wagi są wyłącznie podwyższające, zgodnie z ADR-0002.
 *
 * Specyfikacja wymienia obok nawierzchni także „otwarty teren", ale model krawędzi nie
 * niesie dziś informacji o ekspozycji terenu — wymagałoby to danych o pokryciu terenu
 * spoza tagów nawierzchni. Pozostaje to otwarte.
 */
object StormMode {

    val weights: RoutingWeights = RoutingWeights(
        surface = WeightTable(
            mapOf(
                "mud" to 999.0,
                "ground" to 6.0,
                "dirt" to 6.0,
                "earth" to 6.0,
                "grass" to 5.0,
                "sand" to 4.0,
                "gravel" to 2.0,
                "compacted" to 1.5,
            ),
        ),
    )
}
