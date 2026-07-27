package pl.reactivebike.geocoding

import pl.reactivebike.routing.GeoPoint

/**
 * Port wyszukiwania miejsc — granica między warstwą wspólną a dostawcą.
 *
 * Ten sam zabieg co [pl.reactivebike.routing.RouteEngine] z ADR-0001: interfejs wspólny,
 * implementacja per dostawca. Dzięki temu zmiana wyszukiwarki jest podmianą jednej klasy,
 * a nie przeszywaniem interfejsu użytkownika.
 *
 * Implementacje sygnalizują niepowodzenie przez [GeocodeResult.Failure], a nie wyjątkiem:
 * brak sieci w nawigacji rowerowej jest sytuacją normalną, nie błędem programu.
 */
interface Geocoder {

    /** Nazwa dostawcy do pokazania użytkownikowi — wie, czyich wyników patrzy. */
    val providerName: String

    suspend fun search(query: String, near: GeoPoint?): GeocodeResult
}
