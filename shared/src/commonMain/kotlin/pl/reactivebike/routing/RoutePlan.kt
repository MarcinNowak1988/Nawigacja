package pl.reactivebike.routing

/** Rola punktu w planie przejazdu — decyduje o tym, jak jest rysowany i opisywany. */
enum class StopRole { START, VIA, DESTINATION }

/** Punkt planu wraz z rolą, w kolejności przejazdu. */
data class PlannedStop(val point: GeoPoint, val role: StopRole)

/**
 * Plan przejazdu: skąd, przez co i dokąd.
 *
 * Klasa jest **niezmienna** — każda operacja zwraca nowy plan. Dzięki temu cofnięcie
 * punktu czy wyczyszczenie planu nie wymaga odtwarzania stanu, a całość da się przetestować
 * bez mapy i bez GPS-u.
 *
 * @property start punkt startowy; `null` znaczy „moja bieżąca pozycja" i zostaje rozwinięty
 *   dopiero przy budowaniu zlecenia, żeby plan nie zamrażał pozycji sprzed kwadransa
 * @property via punkty pośrednie w kolejności przejazdu
 * @property destination cel; dopóki jest `null`, planu nie da się wyznaczyć
 */
data class RoutePlan(
    val start: GeoPoint? = null,
    val via: List<GeoPoint> = emptyList(),
    val destination: GeoPoint? = null,
) {

    /** Czy plan ma dość informacji, żeby zlecić wyznaczenie trasy. */
    val isComplete: Boolean get() = destination != null

    /** Czy plan jest pusty — nic nie wskazano, nie ma czego cofać ani czyścić. */
    val isEmpty: Boolean get() = start == null && via.isEmpty() && destination == null

    /** Liczba punktów wskazanych ręcznie przez użytkownika. */
    val stopCount: Int
        get() = (if (start != null) 1 else 0) + via.size + (if (destination != null) 1 else 0)

    /**
     * Dokłada kolejny wskazany punkt na końcu trasy.
     *
     * Wskazanie punktu przy pustym planie ustawia cel; każde następne przesuwa dotychczasowy
     * cel do punktów pośrednich. Dzięki temu jeden gest — „wskaż na mapie" — buduje całą
     * trasę po kolei, bez trybów i przełączników.
     *
     * Zwraca `null`, gdy plan osiągnął [MAX_WAYPOINTS] — wtedy wywołujący ma powiedzieć
     * użytkownikowi, że limit jest wyczerpany, zamiast po cichu zignorować gest.
     */
    fun withNextStop(point: GeoPoint): RoutePlan? {
        if (waypointCount() >= MAX_WAYPOINTS) return null

        val current = destination ?: return copy(destination = point)
        return copy(via = via + current, destination = point)
    }

    /**
     * Ustawia punkt startowy inny niż bieżąca pozycja.
     *
     * Nigdy nie odbija się od limitu: start zajmuje dokładnie jedno miejsce niezależnie
     * od tego, czy jest wskazany, czy brany z pozycji — [waypointCount] liczy je tak samo.
     */
    fun withStart(point: GeoPoint): RoutePlan = copy(start = point)

    /** Wraca do startu z bieżącej pozycji. */
    fun startingFromCurrentPosition(): RoutePlan = copy(start = null)

    /**
     * Cofa ostatnio dodany punkt.
     *
     * Kolejność cofania jest odwrotnością dodawania: najpierw cel wraca do ostatniego punktu
     * pośredniego, a gdy pośrednich zabraknie — znika sam cel, a na końcu punkt startowy.
     */
    fun withoutLastStop(): RoutePlan = when {
        destination != null && via.isNotEmpty() -> copy(via = via.dropLast(1), destination = via.last())
        destination != null -> copy(destination = null)
        start != null -> copy(start = null)
        else -> this
    }

    /** Usuwa wskazany punkt pośredni; indeks spoza zakresu zostawia plan bez zmian. */
    fun withoutVia(index: Int): RoutePlan =
        if (index !in via.indices) this else copy(via = via.filterIndexed { i, _ -> i != index })

    fun cleared(): RoutePlan = RoutePlan()

    /**
     * Zdejmuje z planu punkty pośrednie, do których rowerzysta już dojechał.
     *
     * Bez tego przeliczenie trasy po zjechaniu z niej zawracałoby do punktów, które są już
     * za plecami — im dalej w trasę, tym gorzej. Punkty konsumujemy **po kolei**: minięcie
     * drugiego nie kasuje pierwszego, bo plan ma być przejechany w zadanej kolejności,
     * a nie w tej, w której akurat przejeżdżamy obok.
     *
     * @param position bieżąca pozycja
     * @param reachRadiusMeters odległość, poniżej której punkt uznajemy za osiągnięty
     */
    fun consumingReachedVia(
        position: GeoPoint,
        reachRadiusMeters: Double = VIA_REACH_RADIUS_METERS,
    ): RoutePlan {
        var remaining = via
        while (remaining.isNotEmpty() && position.distanceTo(remaining.first()) <= reachRadiusMeters) {
            remaining = remaining.drop(1)
        }
        return if (remaining.size == via.size) this else copy(via = remaining)
    }

    /** Punkty w kolejności przejazdu, razem z rolami — do rysowania i opisu. */
    fun stops(): List<PlannedStop> = buildList {
        start?.let { add(PlannedStop(it, StopRole.START)) }
        via.forEach { add(PlannedStop(it, StopRole.VIA)) }
        destination?.let { add(PlannedStop(it, StopRole.DESTINATION)) }
    }

    /**
     * Buduje listę punktów dla silnika, rozwijając start z bieżącej pozycji.
     *
     * Zwraca `null`, gdy trasy nie da się jeszcze wyznaczyć: brak celu albo start
     * z pozycji, której nie znamy — czyli sytuacje, w których zapytanie do silnika
     * i tak byłoby bez sensu.
     */
    fun waypoints(currentPosition: GeoPoint?): List<GeoPoint>? {
        val target = destination ?: return null
        val origin = start ?: currentPosition ?: return null
        return listOf(origin) + via + target
    }

    /** Opis planu do pokazania na pulpicie. */
    fun describe(): String {
        if (!isComplete) {
            return if (via.isEmpty() && start == null) {
                "Brak trasy — przytrzymaj palec na mapie, żeby wskazać cel."
            } else {
                "Plan niekompletny — wskaż cel."
            }
        }

        val from = if (start == null) "moja pozycja" else "wybrany punkt"
        return when (via.size) {
            0 -> "Z: $from → cel"
            1 -> "Z: $from → 1 punkt pośredni → cel"
            in 2..4 -> "Z: $from → ${via.size} punkty pośrednie → cel"
            else -> "Z: $from → ${via.size} punktów pośrednich → cel"
        }
    }

    /** Ile punktów pójdzie do silnika, licząc start z pozycji, który dołoży się później. */
    private fun waypointCount(): Int = 1 + via.size + (if (destination != null) 1 else 0)

    companion object {

        /**
         * Limit punktów w jednym zapytaniu.
         *
         * Publiczna instancja Valhalli utrzymywana przez FOSSGIS przyjmuje do 20 lokalizacji
         * dla trasowania. Pilnujemy tego po naszej stronie, żeby użytkownik dowiedział się
         * o limicie przy dodawaniu punktu, a nie z błędu serwera po naciśnięciu „wyznacz".
         */
        const val MAX_WAYPOINTS = 20

        /**
         * Promień, w którym punkt pośredni uznajemy za osiągnięty.
         *
         * Dobrany szerzej niż typowy błąd GPS-u, bo punkt wskazany palcem na mapie i tak
         * rzadko leży dokładnie na drodze — liczy się przejechanie obok, nie trafienie w piksel.
         */
        const val VIA_REACH_RADIUS_METERS = 40.0
    }
}
