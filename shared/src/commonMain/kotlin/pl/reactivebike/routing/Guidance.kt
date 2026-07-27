package pl.reactivebike.routing

/**
 * Decyduje, kiedy i co zapowiedzieć przed manewrem.
 *
 * Rowerzysta nie patrzy w ekran, więc nawigacja bez zapowiedzi jest w praktyce
 * bezużyteczna. Klasa jest czystą logiką — sama syntezę mowy zostawia warstwie natywnej.
 *
 * Każdy manewr zapowiadany jest raz na próg odległości. Bez pilnowania tego telefon
 * powtarzałby to samo zdanie przy każdym odczycie GPS, czyli co sekundę w stanie
 * `CRITICAL`.
 */
class ManeuverAnnouncer(
    private val thresholdsMeters: List<Double> = DEFAULT_THRESHOLDS,
) {

    /** Progi już wykorzystane dla bieżącego manewru, identyfikowanego indeksem początku. */
    private var currentManeuverKey: Int? = null
    private val usedThresholds = mutableSetOf<Double>()

    /**
     * Zwraca treść do wypowiedzenia albo `null`, gdy nie ma nic nowego do powiedzenia.
     */
    fun announce(progress: RouteProgress): String? {
        val maneuver = progress.nextManeuver ?: return null
        val distance = progress.distanceToNextManeuverMeters ?: return null

        if (currentManeuverKey != maneuver.beginShapeIndex) {
            currentManeuverKey = maneuver.beginShapeIndex
            usedThresholds.clear()
        }

        // Bierzemy najmniejszy próg, który już minęliśmy — gdy odczyty GPS są rzadkie,
        // rowerzysta potrafi przeskoczyć próg 300 m i 100 m między dwoma odczytami,
        // a wtedy ma usłyszeć to, co jest aktualne, a nie zaległą zapowiedź.
        val threshold = thresholdsMeters
            .filter { distance <= it && it !in usedThresholds }
            .minOrNull() ?: return null

        // Progi większe od wybranego są już nieaktualne — nie zapowiadamy ich z opóźnieniem.
        thresholdsMeters.filter { it >= threshold }.forEach { usedThresholds.add(it) }

        return phraseFor(maneuver, threshold)
    }

    /** Kasuje stan — po wyznaczeniu nowej trasy poprzednie zapowiedzi tracą sens. */
    fun reset() {
        currentManeuverKey = null
        usedThresholds.clear()
    }

    private fun phraseFor(maneuver: Maneuver, threshold: Double): String =
        if (threshold <= IMMEDIATE_THRESHOLD_METERS) {
            maneuver.instruction
        } else {
            "Za ${threshold.toInt()} metrów: ${maneuver.instruction}"
        }

    companion object {
        /** Progi zapowiedzi: uprzedzenie, przypomnienie i moment wykonania. */
        val DEFAULT_THRESHOLDS: List<Double> = listOf(300.0, 100.0, 25.0)

        /** Poniżej tej odległości mówimy samą instrukcję, bez podawania dystansu. */
        const val IMMEDIATE_THRESHOLD_METERS: Double = 25.0
    }
}

/**
 * Rozpoznaje zjechanie z trasy i pilnuje, by nie przeliczać jej po jednym błędnym odczycie.
 *
 * Pojedynczy fix GPS potrafi odskoczyć o kilkadziesiąt metrów pod drzewami albo między
 * budynkami. Przeliczanie trasy przy każdym takim odczycie oznaczałoby ciągłe zapytania
 * do silnika i skakanie trasy w tę i z powrotem, dlatego wymagamy kilku odczytów z rzędu.
 */
class OffRouteDetector(
    private val requiredConsecutive: Int = DEFAULT_REQUIRED_CONSECUTIVE,
) {

    private var consecutiveOffRoute = 0
    private var alreadyReported = false

    /** Zwraca `true` dokładnie raz, w momencie uznania, że trasę trzeba wyznaczyć od nowa. */
    fun update(progress: RouteProgress): Boolean {
        if (!RouteTracker.isOffRoute(progress)) {
            consecutiveOffRoute = 0
            alreadyReported = false
            return false
        }

        consecutiveOffRoute++
        if (consecutiveOffRoute >= requiredConsecutive && !alreadyReported) {
            alreadyReported = true
            return true
        }
        return false
    }

    /** Kasuje stan — wywoływane po wyznaczeniu nowej trasy. */
    fun reset() {
        consecutiveOffRoute = 0
        alreadyReported = false
    }

    companion object {
        /** Tyle odczytów z rzędu poza trasą uznajemy za faktyczne zjechanie. */
        const val DEFAULT_REQUIRED_CONSECUTIVE: Int = 3
    }
}
