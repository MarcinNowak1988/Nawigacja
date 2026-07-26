package pl.reactivebike.gps

/**
 * Stany maszyny zarządzania częstotliwością GPS (sekcja 7 specyfikacji).
 *
 * @property samplingIntervalSeconds odstęp między odczytami pozycji;
 *   `null` oznacza GPS całkowicie wyłączony
 */
enum class GpsState(val samplingIntervalSeconds: Int?) {

    /** Manewr nawigacyjny w zasięgu — maksymalna precyzja w momencie decyzji. 1 Hz. */
    CRITICAL(1),

    /** Długi, prosty odcinek trasy — standardowe śledzenie pozycji. 0.2 Hz. */
    CRUISE(5),

    /** Ekran wygaszony; rzadkie odczyty uzupełniane predykcją pozycji na bazie ETA. 0.05 Hz. */
    SLEEP(20),

    /** Postój — GPS wyłączony, wybudzenie następuje przez akcelerometr, nie przez GPS. 0 Hz. */
    STATIONARY(null),

    ;

    /** Częstotliwość odpytywania w hercach; `0.0` gdy GPS jest wyłączony. */
    val frequencyHz: Double
        get() = samplingIntervalSeconds?.let { 1.0 / it } ?: 0.0
}
