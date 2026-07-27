package pl.reactivebike.geocoding

import pl.reactivebike.routing.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NominatimTest {

    // --- budowanie zapytania ---

    @Test
    fun query_and_format_land_in_the_url() {
        val url = Nominatim.buildUrl("Wawel")

        assertTrue(url.startsWith(Nominatim.PUBLIC_ENDPOINT), url)
        assertTrue(url.contains("q=Wawel"), url)
        assertTrue(url.contains("format=jsonv2"), url)
    }

    /** Bez kodowania polskie znaki i spacje rozwalają zapytanie. */
    @Test
    fun polish_characters_and_spaces_are_percent_encoded() {
        val url = Nominatim.buildUrl("Łódź Piotrkowska")

        assertTrue(url.contains("q=%C5%81%C3%B3d%C5%BA%20Piotrkowska"), url)
    }

    @Test
    fun characters_with_meaning_in_a_url_are_encoded_too() {
        val url = Nominatim.buildUrl("Kraków & Wieliczka?")

        assertTrue(url.contains("%26"), url)
        assertTrue(url.contains("%3F"), url)
    }

    @Test
    fun unreserved_characters_survive_untouched() {
        val url = Nominatim.buildUrl("Nowa-Wies_2.0~")

        assertTrue(url.contains("q=Nowa-Wies_2.0~"), url)
    }

    @Test
    fun query_is_trimmed_before_encoding() {
        val url = Nominatim.buildUrl("  Wawel  ")

        assertTrue(url.contains("q=Wawel&"), url)
    }

    @Test
    fun position_adds_a_soft_viewbox() {
        val url = Nominatim.buildUrl("Rynek", near = GeoPoint(50.06, 19.94))

        assertTrue(url.contains("viewbox="), url)
        // Miękkie okno: wyniki spoza okolicy dalej mają się znaleźć, tylko niżej.
        assertTrue(url.contains("bounded=0"), url)
    }

    @Test
    fun no_position_means_no_viewbox() {
        val url = Nominatim.buildUrl("Rynek")

        assertTrue(!url.contains("viewbox"), url)
    }

    /** Okno wokół bieguna nie może wyjechać poza zakres współrzędnych. */
    @Test
    fun viewbox_near_the_pole_stays_within_range() {
        val url = Nominatim.buildUrl("stacja", near = GeoPoint(89.9, 179.8))

        val viewbox = url.substringAfter("viewbox=").substringBefore("&").split(",").map { it.toDouble() }
        assertTrue(viewbox[1] <= 90.0, "polnoc poza zakresem: $viewbox")
        assertTrue(viewbox[2] <= 180.0, "wschod poza zakresem: $viewbox")
    }

    @Test
    fun blank_query_is_rejected() {
        assertFailsWith<IllegalArgumentException> { Nominatim.buildUrl("   ") }
    }

    @Test
    fun limit_outside_the_allowed_range_is_rejected() {
        assertFailsWith<IllegalArgumentException> { Nominatim.buildUrl("Wawel", limit = 0) }
        assertFailsWith<IllegalArgumentException> { Nominatim.buildUrl("Wawel", limit = 100) }
    }

    // --- parsowanie odpowiedzi ---

    @Test
    fun places_are_parsed_with_coordinates() {
        val result = Nominatim.parse(
            """
            [
              {
                "lat": "50.0546",
                "lon": "19.9354",
                "name": "Wawel",
                "display_name": "Wawel, Bernardyńska, Stare Miasto, Kraków, województwo małopolskie, Polska"
              }
            ]
            """.trimIndent(),
        )

        val places = assertIs<GeocodeResult.Success>(result).places
        assertEquals(1, places.size)
        assertEquals("Wawel", places[0].name)
        assertEquals(50.0546, places[0].point.latitude)
        assertEquals(19.9354, places[0].point.longitude)
    }

    /** Pełna nazwa z Nominatim ciągnie się przez kraj i kod pocztowy — skracamy ją. */
    @Test
    fun detail_is_shortened_to_what_distinguishes_places() {
        val result = Nominatim.parse(
            """
            [{"lat":"50.0","lon":"20.0","name":"Nowa Wieś",
              "display_name":"Nowa Wieś, gmina Kęty, powiat oświęcimski, województwo małopolskie, Polska, 32-650"}]
            """.trimIndent(),
        )

        val place = assertIs<GeocodeResult.Success>(result).places.single()
        assertEquals("gmina Kęty, powiat oświęcimski, województwo małopolskie", place.detail)
    }

    @Test
    fun name_falls_back_to_the_first_segment_of_the_display_name() {
        val result = Nominatim.parse(
            """[{"lat":"50.0","lon":"20.0","display_name":"Rynek Główny, Kraków, Polska"}]""",
        )

        assertEquals("Rynek Główny", assertIs<GeocodeResult.Success>(result).places.single().name)
    }

    @Test
    fun empty_list_means_no_matches_not_a_failure() {
        assertEquals(GeocodeResult.NoMatches, Nominatim.parse("[]"))
    }

    @Test
    fun malformed_json_is_a_failure() {
        assertEquals(GeocodeResult.Failure, Nominatim.parse("{ to nie jest lista }"))
    }

    @Test
    fun empty_body_is_a_failure() {
        assertEquals(GeocodeResult.Failure, Nominatim.parse(""))
        assertEquals(GeocodeResult.Failure, Nominatim.parse(null))
    }

    /** Jeden zepsuty rekord nie może unieważnić pozostałych wyników. */
    @Test
    fun a_broken_entry_is_skipped_and_the_rest_survives() {
        val result = Nominatim.parse(
            """
            [
              {"lat":"nie-liczba","lon":"20.0","display_name":"Zepsuty"},
              {"lat":"91.0","lon":"20.0","display_name":"Poza globem"},
              {"lat":"50.0","lon":"20.0","display_name":"Dobry, Kraków"}
            ]
            """.trimIndent(),
        )

        val places = assertIs<GeocodeResult.Success>(result).places
        assertEquals(listOf("Dobry"), places.map { it.name })
    }

    @Test
    fun a_list_of_only_broken_entries_means_no_matches() {
        assertEquals(
            GeocodeResult.NoMatches,
            Nominatim.parse("""[{"lat":"nie-liczba","lon":"20.0","display_name":"Zepsuty"}]"""),
        )
    }

    @Test
    fun unknown_fields_do_not_break_parsing() {
        val result = Nominatim.parse(
            """[{"lat":"50.0","lon":"20.0","display_name":"Wawel","place_rank":30,"cos_nowego":true}]""",
        )

        assertIs<GeocodeResult.Success>(result)
    }
}
