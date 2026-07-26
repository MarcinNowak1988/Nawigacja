package pl.reactivebike.gps

/**
 * Migawka sytuacji na trasie — wejście maszyny stanów.
 *
 * @property distanceToManeuverMeters odległość do najbliższego manewru; `null` gdy nie ma
 *   zaplanowanego manewru. W stanie [GpsState.SLEEP] wartość pochodzi z predykcji ETA,
 *   a nie z bieżącego odczytu GPS.
 * @property speedKmh bieżąca prędkość
 * @property screenOn czy ekran urządzenia jest włączony
 * @property motionDetected czy natywny akcelerometr wykrywa ruch
 */
data class RideSignals(
    val distanceToManeuverMeters: Double? = null,
    val speedKmh: Double = 0.0,
    val screenOn: Boolean = true,
    val motionDetected: Boolean = false,
)

/**
 * Maszyna stanów sterująca częstotliwością odpytywania GPS (sekcja 7 specyfikacji).
 *
 * Czysta funkcja przejścia — bez zegara, bez dostępu do sprzętu, bez stanu wewnętrznego.
 * Platformy dostarczają [RideSignals] i wykonują to, co wynika z [GpsState].
 *
 * **Pierwszeństwo reguł.** Diagram w specyfikacji nie rozstrzyga, co zrobić, gdy kilka
 * warunków zachodzi jednocześnie. Przyjęta kolejność:
 * 1. zbliżający się manewr → [GpsState.CRITICAL] (bezpieczeństwo nawigacji ma priorytet),
 * 2. postój → [GpsState.STATIONARY],
 * 3. stan ekranu rozstrzyga między [GpsState.CRUISE] a [GpsState.SLEEP].
 *
 * **Warunek postoju.** Wejście w [GpsState.STATIONARY] wymaga jednocześnie prędkości
 * poniżej [STATIONARY_SPEED_KMH] **i** braku ruchu z akcelerometru — patrz ADR-0004.
 * Sama prędkość nie wystarcza, bo rowerzysta na stromym podjeździe potrafi zejść poniżej
 * 3 km/h, wciąż jadąc.
 */
object GpsStateMachine {

    /** Próg odległości do manewru, poniżej którego wchodzimy w [GpsState.CRITICAL]. */
    const val MANEUVER_DISTANCE_METERS: Double = 300.0

    /** Próg prędkości, poniżej którego jazda może zostać uznana za postój. */
    const val STATIONARY_SPEED_KMH: Double = 3.0

    /** Stan początkowy po uruchomieniu nawigacji. */
    val INITIAL_STATE: GpsState = GpsState.CRUISE

    fun next(current: GpsState, signals: RideSignals): GpsState = when (current) {
        GpsState.CRUISE -> fromCruise(signals)
        GpsState.CRITICAL -> fromCritical(signals)
        GpsState.SLEEP -> fromSleep(signals)
        GpsState.STATIONARY -> fromStationary(signals)
    }

    private fun fromCruise(signals: RideSignals): GpsState = when {
        signals.maneuverImminent -> GpsState.CRITICAL
        signals.stopped -> GpsState.STATIONARY
        !signals.screenOn -> GpsState.SLEEP
        else -> GpsState.CRUISE
    }

    /**
     * Ze stanu [GpsState.CRITICAL] specyfikacja przewiduje jedno wyjście — do
     * [GpsState.CRUISE] po wykonaniu manewru. Świadomie nie dodajemy przejść do
     * [GpsState.SLEEP] ani [GpsState.STATIONARY]: dopóki manewr jest przed nami,
     * precyzja pozycji jest ważniejsza niż oszczędność baterii.
     */
    private fun fromCritical(signals: RideSignals): GpsState =
        if (signals.maneuverImminent) GpsState.CRITICAL else GpsState.CRUISE

    private fun fromSleep(signals: RideSignals): GpsState = when {
        signals.maneuverImminent -> GpsState.CRITICAL
        signals.stopped -> GpsState.STATIONARY
        signals.screenOn -> GpsState.CRUISE
        else -> GpsState.SLEEP
    }

    /** Wybudzenie z postoju następuje wyłącznie na podstawie akcelerometru, nie GPS. */
    private fun fromStationary(signals: RideSignals): GpsState =
        if (signals.motionDetected) GpsState.CRUISE else GpsState.STATIONARY

    private val RideSignals.maneuverImminent: Boolean
        get() = distanceToManeuverMeters?.let { it < MANEUVER_DISTANCE_METERS } == true

    private val RideSignals.stopped: Boolean
        get() = speedKmh < STATIONARY_SPEED_KMH && !motionDetected
}
