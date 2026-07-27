package pl.reactivebike.routing

/** Rola punktu w planie przejazdu — decyduje o tym, jak jest rysowany i opisywany. */
enum class StopRole { START, VIA, DESTINATION }

/**
 * Punkt planu wraz z nazwą.
 *
 * Nazwa jest częścią punktu, a nie dodatkiem obok: miejsce znalezione po nazwie traciło ją
 * w chwili trafienia do planu, przez co „Wawel" zamieniał się w parę liczb i użytkownik
 * nie miał jak sprawdzić, co właściwie wybrał.
 *
 * @property label nazwa do pokazania; `null` znaczy „nie wiemy", np. przy punkcie wskazanym
 *   palcem na mapie, dla którego nie pytaliśmy o adres
 */
data class Waypoint(
    val point: GeoPoint,
    val label: String? = null,
) {
    /** Nazwa albo współrzędne — zawsze coś, co da się pokazać użytkownikowi. */
    fun describe(): String = label ?: formatCoordinates(point)
}

/** Punkt planu wraz z rolą, w kolejności przejazdu. */
data class PlannedStop(val waypoint: Waypoint, val role: StopRole) {
    val point: GeoPoint get() = waypoint.point
}

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
    val start: Waypoint? = null,
    val via: List<Waypoint> = emptyList(),
    val destination: Waypoint? = null,
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
    fun withNextStop(point: GeoPoint, label: String? = null): RoutePlan? {
        if (waypointCount() >= MAX_WAYPOINTS) return null

        val added = Waypoint(point, label)
        val current = destination ?: return copy(destination = added)
        return copy(via = via + current, destination = added)
    }

    /**
     * Ustawia punkt startowy inny niż bieżąca pozycja.
     *
     * Nigdy nie odbija się od limitu: start zajmuje dokładnie jedno miejsce niezależnie
     * od tego, czy jest wskazany, czy brany z pozycji — [waypointCount] liczy je tak samo.
     */
    fun withStart(point: GeoPoint, label: String? = null): RoutePlan =
        copy(start = Waypoint(point, label))

    /** Podmienia sam cel, zostawiając punkty pośrednie na miejscu. */
    fun withDestination(point: GeoPoint, label: String? = null): RoutePlan =
        copy(destination = Waypoint(point, label))

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
        while (remaining.isNotEmpty() && position.distanceTo(remaining.first().point) <= reachRadiusMeters) {
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
        val origin = start?.point ?: currentPosition ?: return null
        return listOf(origin) + via.map { it.point } + target.point
    }

    /** Opis startu do pokazania obok pola wyboru. */
    fun describeStart(): String = start?.describe() ?: "moja pozycja"

    /** Opis celu; `null`, gdy nie został jeszcze wybrany. */
    fun describeDestination(): String? = destination?.describe()

    /** Opis planu do pokazania na pulpicie. */
    fun describe(): String {
        if (!isComplete) {
            return if (via.isEmpty() && start == null) {
                "Brak trasy — wskaż cel na mapie albo wyszukaj go po nazwie."
            } else {
                "Plan niekompletny — wskaż cel."
            }
        }

        val viaPart = when (via.size) {
            0 -> ""
            1 -> " przez 1 punkt pośredni"
            in 2..4 -> " przez ${via.size} punkty pośrednie"
            else -> " przez ${via.size} punktów pośrednich"
        }
        return "Z: ${describeStart()}$viaPart do: ${describeDestination()}"
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

/**
 * Współrzędne w postaci czytelnej dla człowieka.
 *
 * Pięć miejsc po przecinku to około metra — dokładniej nie ma sensu przy punkcie
 * wskazanym palcem, a mniej nie odróżniłoby sąsiednich ulic.
 */
internal fun formatCoordinates(point: GeoPoint): String {
    fun round(value: Double): String {
        // Zaokrąglamy, a nie obcinamy: iloczyn zmiennoprzecinkowy potrafi dać
        // -3386784.9999999995 zamiast -3386785, przez co ostatnia cyfra uciekała.
        val scaled = kotlin.math.round(value * 100_000).toLong()
        val whole = scaled / 100_000
        val fraction = (if (scaled < 0) -scaled else scaled) % 100_000
        return "$whole.${fraction.toString().padStart(5, '0')}"
    }
    return "${round(point.latitude)}, ${round(point.longitude)}"
}
