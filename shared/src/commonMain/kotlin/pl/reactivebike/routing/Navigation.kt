package pl.reactivebike.routing

/**
 * Etap, w którym jest przejazd.
 *
 * Rozdzielenie układania trasy od jazdy nią jest po to, żeby aplikacja nie zaczynała mówić
 * i przeliczać trasy w chwili, gdy użytkownik dopiero przegląda warianty. Wyznaczona trasa
 * to jeszcze nie jazda.
 */
enum class NavigationPhase {

    /** Trasa może być wyznaczona i narysowana, ale nikt nią jeszcze nie jedzie. */
    PLANNING,

    /** Jedziemy: zapowiedzi głosowe, przeliczanie po zjechaniu i wykrywanie dojazdu. */
    NAVIGATING,

    /** Cel osiągnięty. Nawigacja się zakończyła, ale trasa zostaje na ekranie. */
    ARRIVED,
    ;

    /** Czy w tym etapie prowadzimy użytkownika — zapowiadamy manewry i pilnujemy trasy. */
    val isGuiding: Boolean get() = this == NAVIGATING
}

/**
 * Rozstrzyga, kiedy przejazd dobiegł końca.
 *
 * Bez tego nawigacja nigdy się nie kończy: użytkownik dojeżdża na miejsce, a aplikacja dalej
 * pilnuje trasy, zapowiada manewry i wybudza GPS z częstotliwością jazdy.
 */
object ArrivalDetector {

    /**
     * Odległość od końca trasy, poniżej której uznajemy, że jesteśmy na miejscu.
     *
     * Dobrana szerzej niż typowy błąd GPS-u i celowo szersza niż próg zjechania z trasy
     * ([RouteTracker.OFF_ROUTE_THRESHOLD_METERS] to 50 m dla odległości **od** trasy, co jest
     * inną miarą). Cel wskazany palcem albo znaleziony po nazwie rzadko leży dokładnie tam,
     * gdzie da się dojechać rowerem — liczy się dotarcie na miejsce, nie trafienie w punkt.
     */
    const val ARRIVAL_RADIUS_METERS = 35.0

    fun hasArrived(progress: RouteProgress): Boolean =
        progress.remainingDistanceMeters <= ARRIVAL_RADIUS_METERS
}
