package pl.reactivebike.routing

import kotlin.math.roundToLong

/** Na czym oparte jest oszacowanie czasu — użytkownik ma prawo wiedzieć. */
enum class EtaBasis {

    /** Z tempa, którym rowerzysta faktycznie jedzie. */
    MEASURED_SPEED,

    /** Z czasu podanego przez silnik trasowania, przeskalowanego pozostałym dystansem. */
    ENGINE_ESTIMATE,
}

/**
 * Ile zostało do celu.
 *
 * @property remainingDistanceMeters dystans wzdłuż trasy
 * @property remainingDurationSeconds szacowany czas; `null`, gdy nie ma z czego go policzyć
 * @property basis skąd wzięło się oszacowanie czasu
 */
data class RideEstimate(
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Long?,
    val basis: EtaBasis,
)

/**
 * Szacuje, ile jeszcze zostało jazdy.
 *
 * Dwa źródła, w tej kolejności:
 *
 * 1. **Tempo rowerzysty**, gdy jedzie na tyle szybko, że pomiar coś znaczy. To jest
 *    uczciwsze niż plan silnika, bo uwzględnia, że ktoś jedzie pod wiatr, z dzieckiem
 *    w przyczepce albo po prostu wolniej, niż zakłada profil rowerowy.
 * 2. **Plan silnika**, przeskalowany pozostałym dystansem — używany na postoju i tuż po
 *    starcie, kiedy z chwilowej prędkości nie da się nic wywnioskować.
 *
 * Świadomie nie uśredniamy tempa z całego przejazdu: postój na światłach albo przerwa na
 * kawę zaniżyłyby średnią tak, że oszacowanie przestałoby odpowiadać temu, jak się jedzie
 * teraz. Uśrednianie po oknie czasowym byłoby lepsze, ale wymaga historii, której ta
 * funkcja celowo nie trzyma — ma pozostać czysta.
 */
object RouteEta {

    /**
     * Poniżej tej prędkości pomiar nie nadaje się na podstawę oszacowania.
     *
     * 1,5 m/s to około 5,4 km/h — wolniej niż jedzie się rowerem, więc taki odczyt znaczy
     * raczej „stoję" albo „prowadzę rower" niż „takim tempem dojadę do celu".
     */
    const val MIN_SPEED_METERS_PER_SECOND = 1.5

    fun estimate(
        route: Route,
        progress: RouteProgress,
        speedMetersPerSecond: Double? = null,
    ): RideEstimate {
        val remaining = progress.remainingDistanceMeters

        if (speedMetersPerSecond != null &&
            speedMetersPerSecond.isFinite() &&
            speedMetersPerSecond >= MIN_SPEED_METERS_PER_SECOND
        ) {
            return RideEstimate(
                remainingDistanceMeters = remaining,
                remainingDurationSeconds = (remaining / speedMetersPerSecond).roundToLong(),
                basis = EtaBasis.MEASURED_SPEED,
            )
        }

        val total = progress.totalDistanceMeters
        val planned = route.estimatedDurationSeconds
        val duration = if (total <= 0.0 || planned <= 0L) {
            null
        } else {
            (planned * (remaining / total)).roundToLong()
        }

        return RideEstimate(
            remainingDistanceMeters = remaining,
            remainingDurationSeconds = duration,
            basis = EtaBasis.ENGINE_ESTIMATE,
        )
    }
}
