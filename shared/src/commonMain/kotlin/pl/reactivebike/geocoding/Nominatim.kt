package pl.reactivebike.geocoding

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.reactivebike.routing.GeoPoint

/**
 * Miejsce znalezione po nazwie.
 *
 * @property name krótka nazwa do pokazania na liście wyników
 * @property detail reszta adresu — to ona odróżnia trzy Nowe Wsie od siebie
 * @property point współrzędne
 */
data class Place(
    val name: String,
    val detail: String,
    val point: GeoPoint,
)

/** Wynik wyszukiwania miejsca. */
sealed interface GeocodeResult {
    data class Success(val places: List<Place>) : GeocodeResult

    /** Zapytanie się udało, ale nic nie pasuje — to nie jest błąd. */
    data object NoMatches : GeocodeResult

    /** Brak sieci, błąd usługi albo odpowiedź nie do odczytania. */
    data object Failure : GeocodeResult
}

/**
 * Klient wyszukiwania miejsc oparty o Nominatim, w części niezależnej od platformy.
 *
 * Transport HTTP należy do warstwy natywnej — tutaj mieszka budowanie adresu zapytania
 * i parsowanie odpowiedzi, dzięki czemu jedno i drugie da się pokryć testami bez sieci.
 *
 * **Zasady korzystania z publicznej instancji są wiążące, nie uprzejme.** Nominatim
 * utrzymuje społeczność OSM i wymaga: własnego `User-Agent` identyfikującego aplikację,
 * najwyżej jednego zapytania na sekundę i braku podpowiedzi w trakcie pisania. Dlatego
 * wyszukiwanie uruchamia przycisk, a nie każde naciśnięcie klawisza.
 */
object Nominatim {

    const val PUBLIC_ENDPOINT = "https://nominatim.openstreetmap.org/search"

    /** Wymagany przez zasady korzystania — anonimowy klient bywa blokowany. */
    const val USER_AGENT = "ReactiveBike/0.10 (https://github.com/MarcinNowak1988/Nawigacja)"

    /** Najkrótszy dopuszczalny odstęp między zapytaniami. */
    const val MIN_INTERVAL_MILLIS = 1_000L

    const val DEFAULT_LIMIT = 8

    /** Połowa boku okna, którym podpowiadamy wyszukiwarce okolicę — około 80 km. */
    private const val VIEWBOX_HALF_DEGREES = 0.75

    /**
     * Buduje adres zapytania.
     *
     * @param near pozycja, wokół której podbijamy trafność wyników. Okno jest **miękkie**
     *   (`bounded=0`): miejsce spoza okolicy dalej się znajdzie, tylko niżej na liście.
     *   Bez tego „Rynek" zwracałby rynki z całego świata w przypadkowej kolejności.
     */
    fun buildUrl(
        query: String,
        near: GeoPoint? = null,
        limit: Int = DEFAULT_LIMIT,
    ): String {
        require(query.isNotBlank()) { "Puste zapytanie nie ma czego szukac" }
        require(limit in 1..40) { "Limit wynikow poza zakresem: $limit" }

        val parameters = mutableListOf(
            "q=${percentEncode(query.trim())}",
            "format=jsonv2",
            "limit=$limit",
            "addressdetails=0",
        )

        if (near != null) {
            val west = (near.longitude - VIEWBOX_HALF_DEGREES).coerceIn(-180.0, 180.0)
            val east = (near.longitude + VIEWBOX_HALF_DEGREES).coerceIn(-180.0, 180.0)
            val north = (near.latitude + VIEWBOX_HALF_DEGREES).coerceIn(-90.0, 90.0)
            val south = (near.latitude - VIEWBOX_HALF_DEGREES).coerceIn(-90.0, 90.0)
            parameters += "viewbox=$west,$north,$east,$south"
            parameters += "bounded=0"
        }

        return "$PUBLIC_ENDPOINT?${parameters.joinToString("&")}"
    }

    fun parse(rawResponse: String?): GeocodeResult {
        if (rawResponse.isNullOrBlank()) return GeocodeResult.Failure

        val entries = try {
            json.decodeFromString(ListSerializer, rawResponse)
        } catch (_: SerializationException) {
            return GeocodeResult.Failure
        }

        // Pojedynczy wpis o niepoprawnych współrzędnych odrzucamy, ale reszty wyników
        // nie wyrzucamy do kosza — jeden zepsuty rekord nie ma unieważniać wyszukiwania.
        val places = entries.mapNotNull { it.toPlace() }

        return if (places.isEmpty()) GeocodeResult.NoMatches else GeocodeResult.Success(places)
    }

    /**
     * Koduje procentowo wszystko poza znakami niezastrzeżonymi z RFC 3986.
     *
     * Piszemy to sami, bo `commonMain` nie ma kodera adresów, a bez niego polskie znaki
     * i spacje w nazwach miejscowości psują zapytanie.
     */
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
    private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(NominatimPlace.serializer())
}

@Serializable
internal data class NominatimPlace(
    // Nominatim podaje współrzędne jako napisy, nie liczby.
    val lat: String? = null,
    val lon: String? = null,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
) {

    fun toPlace(): Place? {
        val latitude = lat?.toDoubleOrNull() ?: return null
        val longitude = lon?.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        val full = displayName?.trim().orEmpty()
        val segments = full.split(",").map { it.trim() }.filter { it.isNotBlank() }

        val label = name?.trim()?.takeIf { it.isNotBlank() }
            ?: segments.firstOrNull()
            ?: return null

        // Pełna nazwa z Nominatim ciągnie się przez województwo, kraj i kod pocztowy.
        // Zostawiamy kilka pierwszych członów — tyle wystarczy, żeby odróżnić miejsca
        // o tej samej nazwie, a więcej i tak nie zmieściłoby się na liście.
        val detail = segments
            .dropWhile { it == label }
            .take(DETAIL_SEGMENTS)
            .joinToString(", ")

        return Place(
            name = label,
            detail = detail,
            point = GeoPoint(latitude, longitude),
        )
    }
}

/**
 * Ile członów pełnej nazwy zostawiamy jako opis.
 *
 * Stała stoi poza klasą celowo: własny `companion object` przesłoniłby ten generowany
 * przez `kotlinx.serialization`, przez co `NominatimPlace.serializer()` przestałby być
 * dostępny.
 */
private const val DETAIL_SEGMENTS = 3
