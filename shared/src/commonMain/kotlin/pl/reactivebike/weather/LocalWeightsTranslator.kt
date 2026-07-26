package pl.reactivebike.weather

import pl.reactivebike.routing.EdgeWeight
import pl.reactivebike.routing.RoutingWeights
import pl.reactivebike.routing.WeightTable

/**
 * Deterministyczny tłumacz warunków pogodowych na wagi krawędzi.
 *
 * **To nie jest moduł AI z sekcji 6.** Model językowy działa wyłącznie online i wymaga
 * własnej usługi; ten tłumacz jest lokalną, regułową alternatywą, która pozwala aplikacji
 * adaptować trasę bez sieci i bez żadnej usługi zewnętrznej. Gdy moduł AI będzie dostępny,
 * ten tłumacz zostaje jako ścieżka zapasowa.
 *
 * Reguły są jawne i przewidywalne — celowo, bo od nich zależy, którędy pojedzie rowerzysta.
 * Wagi są wyłącznie podwyższające, zgodnie z ADR-0002.
 */
object LocalWeightsTranslator {

    /** Nawierzchnie, które po deszczu stają się grząskie. */
    private val softSurfaces = listOf("ground", "dirt", "earth", "grass", "sand", "mud")

    fun translate(conditions: WeatherConditions): RoutingWeights {
        val surface = mutableMapOf<String, Double>()

        when {
            conditions.isHeavyRain -> {
                // Ulewa: błoto wykluczone, pozostałe miękkie nawierzchnie mocno odstraszane.
                surface["mud"] = EdgeWeight.IMPASSABLE
                surface["ground"] = 8.0
                surface["dirt"] = 8.0
                surface["earth"] = 8.0
                surface["grass"] = 7.0
                surface["sand"] = 5.0
                surface["gravel"] = 2.5
                surface["compacted"] = 1.5
            }

            conditions.isRaining -> {
                // Deszcz: miękkie nawierzchnie odstraszane, ale wciąż przejezdne.
                surface["mud"] = 12.0
                surface["ground"] = 4.0
                surface["dirt"] = 4.0
                surface["earth"] = 4.0
                surface["grass"] = 3.5
                surface["sand"] = 3.0
                surface["gravel"] = 1.8
                surface["compacted"] = 1.2
            }

            else -> {
                // Sucho: bez modyfikacji poza trwale grząskim błotem.
                surface["mud"] = 3.0
            }
        }

        if (conditions.isFreezing) {
            // Ryzyko oblodzenia — gładkie, twarde nawierzchnie robią się zdradliwe.
            surface["sett"] = maxOf(surface["sett"] ?: 1.0, 3.0)
            surface["cobblestone"] = maxOf(surface["cobblestone"] ?: 1.0, 3.0)
            surface["paving_stones"] = maxOf(surface["paving_stones"] ?: 1.0, 2.0)
            surface["metal"] = maxOf(surface["metal"] ?: 1.0, 4.0)
            surface["wood"] = maxOf(surface["wood"] ?: 1.0, 4.0)
        }

        return RoutingWeights(surface = WeightTable(surface)).normalizedToIncreaseOnly()
    }

    /** Krótkie uzasadnienie doboru wag — do pokazania użytkownikowi (odpowiednik `ui_notification`). */
    fun explain(conditions: WeatherConditions): String = when {
        conditions.isHeavyRain && conditions.isFreezing ->
            "Ulewa przy temperaturze wokół zera. Omijam błoto i śliskie nawierzchnie."
        conditions.isHeavyRain ->
            "Ulewa. Błoto wykluczone, szukam nawierzchni utwardzonych."
        conditions.isRaining && conditions.isFreezing ->
            "Deszcz i ryzyko oblodzenia. Unikam miękkich i śliskich nawierzchni."
        conditions.isRaining ->
            "Pada. Odstraszam nawierzchnie podatne na rozmoknięcie."
        conditions.isFreezing ->
            "Temperatura wokół zera. Unikam kostki, metalu i drewna."
        else ->
            "Sucho. Trasa bez ograniczeń pogodowych."
    }
}
