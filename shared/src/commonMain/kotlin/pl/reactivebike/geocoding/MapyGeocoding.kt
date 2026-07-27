package pl.reactivebike.geocoding

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.reactivebike.routing.GeoPoint

/**
 * Klient wyszukiwania miejsc Mapy.com, w części niezależnej od platformy.
 *
 * Powód zmiany dostawcy: Nominatim radzi sobie z polskimi adresami przeciętnie, a Mapy.com
 * są w Europie Środkowej wyraźnie lepsze. Zmieniamy **wyłącznie wyszukiwanie** — kafelki mapy
 * i trasowanie zostają na OpenStreetMap, bo regulamin Mapy.com zabrania buforowania kafelków,
 * co zabiłoby zapisane regiony offline (jedyny tryb offline, jaki został po ADR-0007).
 *
 * ## Kształt odpowiedzi jest przyjęty, nie potwierdzony
 *
 * Środowisko, w którym powstawał ten kod, nie ma dostępu do `api.mapy.com` ani do jej
 * dokumentacji, więc pól odpowiedzi nie dało się sprawdzić u źródła. Parsowanie jest z tego
 * powodu **celowo tolerancyjne**: każde pole jest opcjonalne, a wpis nie do odczytania jest
 * pomijany zamiast wywracać wyszukiwanie. Nieznany kształt odpowiedzi skończy się więc
 * komunikatem „nic nie znalazłem", a nie awarią — i wtedy wystarczy poprawić same nazwy pól.
 */
object MapyGeocoding {

    const val PUBLIC_ENDPOINT = "https://api.mapy.com/v1/geocode"

    const val DEFAULT_LIMIT = 8

    /** Nazwa dostawcy pokazywana użytkownikowi. */
    const val PROVIDER_NAME = "Mapy.com"

    /**
     * Buduje adres zapytania.
     *
     * Klucz API doklejamy jako parametr `apikey`. Trafia on do adresu, więc **nie wolno go
     * logować** — stąd [redact], którym warstwa transportowa czyści komunikaty błędów.
     */
    fun buildUrl(
        query: String,
        apiKey: String,
        near: GeoPoint? = null,
        limit: Int = DEFAULT_LIMIT,
        language: String = "pl",
    ): String {
        require(query.isNotBlank()) { "Puste zapytanie nie ma czego szukac" }
        require(apiKey.isNotBlank()) { "Brak klucza API" }
        require(limit in 1..100) { "Limit wynikow poza zakresem: $limit" }

        val parameters = mutableListOf(
            "query=${percentEncode(query.trim())}",
            "lang=${percentEncode(language)}",
            "limit=$limit",
            "apikey=${percentEncode(apiKey)}",
        )

        // Preferencja lokalizacyjna: wyniki bliżej rowerzysty mają być wyżej.
        if (near != null) {
            parameters += "preferNear=${near.longitude},${near.latitude}"
        }

        return "$PUBLIC_ENDPOINT?${parameters.joinToString("&")}"
    }

    /** Usuwa klucz z tekstu — do komunikatów o błędach, żeby nie wyciekł do logów. */
    fun redact(text: String, apiKey: String): String =
        if (apiKey.isBlank()) text else text.replace(apiKey, "***")

    fun parse(rawResponse: String?): GeocodeResult {
        if (rawResponse.isNullOrBlank()) return GeocodeResult.Failure

        val response = try {
            json.decodeFromString(MapyResponse.serializer(), rawResponse)
        } catch (_: SerializationException) {
            return GeocodeResult.Failure
        }

        val places = response.items.orEmpty().mapNotNull { it.toPlace() }
        return if (places.isEmpty()) GeocodeResult.NoMatches else GeocodeResult.Success(places)
    }

    private fun percentEncode(text: String): String {
        val builder = StringBuilder()
        text.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in UNRESERVED) {
                builder.append(char)
            } else {
                builder.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return builder.toString()
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    private const val HEX = "0123456789ABCDEF"

    private val json = Json { ignoreUnknownKeys = true }
}

@Serializable
internal data class MapyResponse(
    val items: List<MapyItem>? = null,
)

@Serializable
internal data class MapyItem(
    val name: String? = null,
    val label: String? = null,
    val location: String? = null,
    val position: MapyPosition? = null,
) {

    fun toPlace(): Place? {
        val latitude = position?.lat ?: return null
        val longitude = position.lon ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        val title = name?.trim()?.takeIf { it.isNotBlank() }
            ?: location?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        // `location` to u Mapy.com opis okolicy („Kraków, Polska"), czyli dokładnie to,
        // co odróżnia dwa miejsca o tej samej nazwie. `label` bywa typem obiektu.
        val detail = listOfNotNull(
            location?.trim()?.takeIf { it.isNotBlank() && it != title },
            label?.trim()?.takeIf { it.isNotBlank() && it != title },
        ).firstOrNull().orEmpty()

        return Place(name = title, detail = detail, point = GeoPoint(latitude, longitude))
    }
}

@Serializable
internal data class MapyPosition(
    val lat: Double? = null,
    val lon: Double? = null,
)
