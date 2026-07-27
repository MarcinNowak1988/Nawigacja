package pl.reactivebike.geocoding

import pl.reactivebike.routing.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MapyGeocodingTest {

    // --- budowanie zapytania ---

    @Test
    fun query_language_and_key_land_in_the_url() {
        val url = MapyGeocoding.buildUrl("Wawel", apiKey = "TAJNY")

        assertTrue(url.startsWith(MapyGeocoding.PUBLIC_ENDPOINT), url)
        assertTrue(url.contains("query=Wawel"), url)
        assertTrue(url.contains("lang=pl"), url)
        assertTrue(url.contains("apikey=TAJNY"), url)
    }

    @Test
    fun polish_characters_are_percent_encoded() {
        val url = MapyGeocoding.buildUrl("Łódź Piotrkowska", apiKey = "K")

        assertTrue(url.contains("query=%C5%81%C3%B3d%C5%BA%20Piotrkowska"), url)
    }

    /** Klucz też przechodzi przez koder — znak specjalny w kluczu nie może rozbić adresu. */
    @Test
    fun a_key_with_special_characters_does_not_break_the_url() {
        val url = MapyGeocoding.buildUrl("Wawel", apiKey = "a+b/c=d")

        assertTrue(url.contains("apikey=a%2Bb%2Fc%3Dd"), url)
    }

    @Test
    fun position_adds_a_preference_but_not_a_hard_filter() {
        val url = MapyGeocoding.buildUrl("Rynek", apiKey = "K", near = GeoPoint(50.06, 19.94))

        // Mapy.com przyjmuje kolejność dlugosc,szerokosc — odwrotnie niż nasz GeoPoint.
        assertTrue(url.contains("preferNear=19.94,50.06"), url)
    }

    @Test
    fun no_position_means_no_preference() {
        assertTrue(!MapyGeocoding.buildUrl("Rynek", apiKey = "K").contains("preferNear"))
    }

    @Test
    fun blank_query_or_key_is_rejected() {
        assertFailsWith<IllegalArgumentException> { MapyGeocoding.buildUrl("  ", apiKey = "K") }
        assertFailsWith<IllegalArgumentException> { MapyGeocoding.buildUrl("Wawel", apiKey = " ") }
    }

    /** Klucz nie ma prawa trafić do logu ani do komunikatu błędu. */
    @Test
    fun the_key_is_redacted_from_messages() {
        val message = MapyGeocoding.redact("blad dla https://api.mapy.com/v1/geocode?apikey=TAJNY", "TAJNY")

        assertTrue(!message.contains("TAJNY"), message)
        assertTrue(message.contains("***"), message)
    }

    @Test
    fun redacting_without_a_key_changes_nothing() {
        assertEquals("cokolwiek", MapyGeocoding.redact("cokolwiek", ""))
    }

    // --- parsowanie odpowiedzi ---

    @Test
    fun places_are_parsed_with_coordinates() {
        val result = MapyGeocoding.parse(
            """
            {"items":[
              {"name":"Wawel","label":"Zamek","location":"Kraków, Polska",
               "position":{"lon":19.9354,"lat":50.0546}}
            ]}
            """.trimIndent(),
        )

        val place = assertIs<GeocodeResult.Success>(result).places.single()
        assertEquals("Wawel", place.name)
        assertEquals("Kraków, Polska", place.detail)
        assertEquals(50.0546, place.point.latitude)
        assertEquals(19.9354, place.point.longitude)
    }

    @Test
    fun an_empty_result_list_means_no_matches_not_a_failure() {
        assertEquals(GeocodeResult.NoMatches, MapyGeocoding.parse("""{"items":[]}"""))
    }

    @Test
    fun malformed_json_is_a_failure() {
        assertEquals(GeocodeResult.Failure, MapyGeocoding.parse("{to nie jest json"))
    }

    @Test
    fun empty_body_is_a_failure() {
        assertEquals(GeocodeResult.Failure, MapyGeocoding.parse(""))
        assertEquals(GeocodeResult.Failure, MapyGeocoding.parse(null))
    }

    /**
     * Kształt odpowiedzi przyjęto bez dostępu do dokumentacji, więc parser musi znosić
     * niespodzianki: nieznana struktura ma dać „nic nie znalazłem", a nie awarię.
     */
    @Test
    fun an_unexpected_shape_degrades_to_no_matches_instead_of_crashing() {
        assertEquals(GeocodeResult.NoMatches, MapyGeocoding.parse("""{"results":[{"foo":1}]}"""))
        assertEquals(GeocodeResult.NoMatches, MapyGeocoding.parse("""{"items":[{"nieznane":"pole"}]}"""))
    }

    @Test
    fun an_entry_without_a_position_is_skipped_and_the_rest_survives() {
        val result = MapyGeocoding.parse(
            """
            {"items":[
              {"name":"Bez pozycji"},
              {"name":"Poza globem","position":{"lat":91.0,"lon":20.0}},
              {"name":"Dobry","location":"Kraków","position":{"lat":50.0,"lon":20.0}}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("Dobry"), assertIs<GeocodeResult.Success>(result).places.map { it.name })
    }

    @Test
    fun a_place_without_a_name_falls_back_to_its_locality() {
        val result = MapyGeocoding.parse(
            """{"items":[{"location":"Nowa Wieś, gmina Kęty","position":{"lat":50.0,"lon":20.0}}]}""",
        )

        assertEquals("Nowa Wieś, gmina Kęty", assertIs<GeocodeResult.Success>(result).places.single().name)
    }

    @Test
    fun unknown_fields_do_not_break_parsing() {
        val result = MapyGeocoding.parse(
            """{"items":[{"name":"Wawel","position":{"lat":50.0,"lon":20.0},"zip":"31-001","regionalStructure":[]}]}""",
        )

        assertIs<GeocodeResult.Success>(result)
    }
}
