package pl.reactivebike.routing.brouter

/**
 * Manewr w rozumieniu BRoutera, wraz z instrukcją po polsku.
 *
 * Kody liczbowe pochodzą z `btools.router.VoiceHint` i są tam **pakietowo-prywatne**, więc
 * powtarzamy je tutaj. Powtórzenie jest świadome: alternatywą byłoby przepuszczanie trasy
 * przez format tekstowy silnika i parsowanie go z powrotem, co kosztuje więcej i psuje się
 * ciszej. Gdyby BRouter zmienił numerację, [fromCode] zwróci `null` i manewr zostanie
 * pominięty, zamiast zamienić się w instrukcję „skręć w lewo" w miejscu ronda.
 *
 * @property code wartość pola `cmd` podpowiedzi BRoutera
 * @property instruction treść czytana użytkownikowi i pokazywana na pulpicie
 */
enum class BRouterCommand(val code: Int, val instruction: String) {
    CONTINUE(1, "Jedź prosto"),
    TURN_LEFT(2, "Skręć w lewo"),
    TURN_SLIGHTLY_LEFT(3, "Lekko w lewo"),
    TURN_SHARPLY_LEFT(4, "Ostro w lewo"),
    TURN_RIGHT(5, "Skręć w prawo"),
    TURN_SLIGHTLY_RIGHT(6, "Lekko w prawo"),
    TURN_SHARPLY_RIGHT(7, "Ostro w prawo"),
    KEEP_LEFT(8, "Trzymaj się lewej"),
    KEEP_RIGHT(9, "Trzymaj się prawej"),
    U_TURN_LEFT(10, "Zawróć w lewo"),
    U_TURN_RIGHT(11, "Zawróć w prawo"),
    OFF_ROUTE(12, "Poza trasą"),
    ROUNDABOUT(13, "Wjedź na rondo"),
    ROUNDABOUT_LEFT(14, "Wjedź na rondo w lewo"),
    U_TURN(15, "Zawróć"),
    BEELINE(16, "Jedź w kierunku celu"),
    EXIT_LEFT(17, "Zjazd w lewo"),
    EXIT_RIGHT(18, "Zjazd w prawo"),
    END(100, "Dojechałeś do celu"),
    ;

    /** Czy manewr wymaga od rowerzysty działania — czy tylko potwierdza kierunek jazdy. */
    val isActionable: Boolean
        get() = this != CONTINUE && this != BEELINE && this != OFF_ROUTE

    companion object {

        private val byCode = entries.associateBy { it.code }

        /** Manewr o zadanym kodzie; `null` dla kodu, którego nie znamy. */
        fun fromCode(code: Int): BRouterCommand? = byCode[code]

        /**
         * Instrukcja dla kodu, z rozsądnym zastępnikiem.
         *
         * Nieznany kod daje „Jedź dalej", a nie pustkę: cisza w nawigacji jest gorsza niż
         * ogólnik, bo rowerzysta nie wie, czy zapowiedź się nie odezwała, czy jej nie było.
         */
        fun instructionFor(code: Int): String = fromCode(code)?.instruction ?: "Jedź dalej"
    }
}
