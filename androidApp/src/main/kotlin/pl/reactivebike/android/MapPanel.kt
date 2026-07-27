package pl.reactivebike.android

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Mapa wektorowa z bieżącą pozycją i śladem przejazdu.
 *
 * Renderowaniem zajmuje się MapLibre GL Native — ten sam silnik, który zgodnie z sekcją 3
 * specyfikacji ma docelowo czytać kafelki `.mbtiles` z pamięci urządzenia. Na tym etapie
 * styl pobierany jest z sieci; przejście na kafelki offline opisuje ADR-0003 i jest osobnym
 * krokiem, bo wymaga procesu pobierania i zarządzania regionami.
 *
 * Cykl życia [MapView] musi być przekazywany ręcznie — stąd komplet metod poniżej.
 */
class MapPanel(context: Context) {

    // Kolejność ma znaczenie: MapLibre trzeba zainicjalizować **przed** utworzeniem
    // MapView, inaczej konstruktor widoku rzuca wyjątkiem. Inicjalizatory pól wykonują
    // się przed blokiem `init`, więc `view` musi być przypisane wewnątrz `init`, po
    // wywołaniu `getInstance` — nie w deklaracji pola.
    val view: MapView

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
    }

    private var map: MapLibreMap? = null
    private var styleReady = false
    private var triedFallbackStyle = false

    /** Styl faktycznie wczytany — potrzebny przy pobieraniu regionu offline. */
    var activeStyleUrl: String = PRIMARY_STYLE
        private set

    private val track = mutableListOf<Point>()
    private var followPosition = true

    /**
     * Punkty planu, żeby dało się je narysować ponownie.
     *
     * Wczytanie stylu — także zapasowego po nieudanym pobraniu podstawowego — tworzy warstwy
     * od nowa i gubi ich zawartość. Bez zapamiętania punkty znikałyby z mapy, mimo że plan
     * dalej istnieje.
     */
    private var stopsToDraw: List<pl.reactivebike.routing.PlannedStop> = emptyList()

    /** Wywoływane po długim naciśnięciu mapy — tak użytkownik wskazuje cel trasy. */
    var onDestinationPicked: ((Double, Double) -> Unit)? = null

    /**
     * Wywoływane, gdy kamera się zatrzyma — czyli gdy zmienił się obszar, który obejmie
     * pobranie offline. Dzięki temu szacowany rozmiar odpowiada temu, co użytkownik widzi,
     * a nie temu, co widział przy starcie.
     */
    var onVisibleAreaChanged: (() -> Unit)? = null

    /** Pozycja, na którą ustawiamy kamerę, zanim mapa się wczyta — np. ostatnia znana z systemu. */
    private var pendingCamera: LatLng? = null
    private var pendingZoom: Double = DEFAULT_ZOOM

    /**
     * Ustawia punkt startowy kamery przed pierwszym fixem GPS.
     *
     * Wywoływane z ostatnią znaną pozycją z systemu, jeśli taka jest — dzięki temu mapa
     * otwiera się tam, gdzie użytkownik faktycznie jest, a nie na domyślnym widoku.
     */
    fun setInitialPosition(latitude: Double, longitude: Double) {
        pendingCamera = LatLng(latitude, longitude)
        pendingZoom = ZOOM
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), ZOOM))
    }

    fun onCreate(savedInstanceState: Bundle?) {
        view.onCreate(savedInstanceState)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            // Ręczne przesunięcie mapy wyłącza podążanie za pozycją — inaczej kamera
            // wyrywałaby użytkownikowi widok przy każdym odczycie GPS.
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followPosition = false
                }
            }
            // Bez tego kamera stoi na (0, 0) w powiększeniu 0 aż do pierwszego fixu GPS —
            // użytkownik widzi środek Atlantyku i nie ma czego wybrać do pobrania.
            ready.moveCamera(
                CameraUpdateFactory.newLatLngZoom(pendingCamera ?: DEFAULT_CAMERA, pendingZoom),
            )
            ready.addOnCameraIdleListener { onVisibleAreaChanged?.invoke() }
            ready.addOnMapLongClickListener { point ->
                onDestinationPicked?.invoke(point.latitude, point.longitude)
                true
            }
            loadStyle(ready, PRIMARY_STYLE)
        }

        view.addOnDidFailLoadingMapListener {
            val current = map
            if (!triedFallbackStyle && current != null) {
                triedFallbackStyle = true
                loadStyle(current, FALLBACK_STYLE)
            }
        }
    }

    private fun loadStyle(target: MapLibreMap, styleUrl: String) {
        activeStyleUrl = styleUrl
        target.setStyle(styleUrl) { style ->
            styleReady = true
            addLayers(style)
            redraw(style)
            if (stopsToDraw.isNotEmpty()) showStops(stopsToDraw)
        }
    }

    /** Granice widocznego fragmentu mapy — obszar, który obejmie pobranie offline. */
    fun visibleBounds(): org.maplibre.android.geometry.LatLngBounds? =
        map?.projection?.visibleRegion?.latLngBounds

    private fun addLayers(style: Style) {
        // Kolejność dodawania wyznacza kolejność rysowania: trasa pod śladem przejazdu,
        // a pozycja i cel na samej górze, żeby nigdy nie zniknęły pod linią.
        if (style.getSource(SOURCE_ROUTE) == null) {
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_ROUTE))
            style.addLayer(
                LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
                    PropertyFactory.lineColor(ROUTE_COLOR),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                ),
            )
        }
        if (style.getSource(SOURCE_TRACK) == null) {
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_TRACK))
            style.addLayer(
                LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                    PropertyFactory.lineColor(TRACK_COLOR),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                ),
            )
        }
        if (style.getSource(SOURCE_DESTINATION) == null) {
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_DESTINATION))
            style.addLayer(
                CircleLayer(LAYER_DESTINATION, SOURCE_DESTINATION).withProperties(
                    PropertyFactory.circleRadius(11f),
                    // Kolor bierzemy z cechy punktu, a nie z osobnej warstwy na rolę —
                    // jedno źródło i jedna warstwa zamiast trzech, które trzeba synchronizować.
                    PropertyFactory.circleColor(Expression.get(PROPERTY_STOP_COLOR)),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                ),
            )
            style.addLayer(
                SymbolLayer(LAYER_STOP_LABELS, SOURCE_DESTINATION).withProperties(
                    PropertyFactory.textField(Expression.get(PROPERTY_STOP_LABEL)),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor(Color.BLACK),
                    // Podpis pod znacznikiem, a nie na nim: „Start" i „Koniec" nie zmieszczą
                    // się w kółku, a przykryte kółkiem byłyby nieczytelne.
                    PropertyFactory.textAnchor("top"),
                    PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
                    // Obwódka, bo podpis leży na mapie o nieprzewidywalnym tle.
                    PropertyFactory.textHaloColor(Color.WHITE),
                    PropertyFactory.textHaloWidth(1.6f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
        }
        if (style.getSource(SOURCE_POSITION) == null) {
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_POSITION))
            style.addLayer(
                CircleLayer(LAYER_POSITION, SOURCE_POSITION).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(POSITION_COLOR),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                ),
            )
        }
    }

    /** Rysuje wyznaczoną trasę. Punkty planu rysuje [showStops] — są od trasy niezależne. */
    fun showRoute(geometry: List<pl.reactivebike.routing.GeoPoint>) {
        val style = map?.style ?: return

        if (geometry.size >= 2) {
            val points = geometry.map { Point.fromLngLat(it.longitude, it.latitude) }
            (style.getSource(SOURCE_ROUTE) as? org.maplibre.android.style.sources.GeoJsonSource)
                ?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
        }
    }

    /**
     * Rysuje punkty planu: start, pośrednie i cel.
     *
     * Punkty żyją własnym życiem, niezależnie od linii trasy — użytkownik ma je widzieć
     * także wtedy, gdy trasa jeszcze się liczy albo w ogóle nie da się jej wyznaczyć.
     * Rolę niesie kolor, a kolejność — podpis, bo przy kilku punktach sam kolor przestaje
     * wystarczać do odczytania, którędy trasa ma prowadzić.
     */
    fun showStops(stops: List<pl.reactivebike.routing.PlannedStop>) {
        val style = map?.style ?: return
        stopsToDraw = stops

        val features = stops.mapIndexed { index, stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.point.longitude, stop.point.latitude)).apply {
                addStringProperty(PROPERTY_STOP_COLOR, colorOf(stop.role))
                addStringProperty(PROPERTY_STOP_LABEL, labelOf(stop, index, stops))
            }
        }

        (style.getSource(SOURCE_DESTINATION) as? org.maplibre.android.style.sources.GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun colorOf(role: pl.reactivebike.routing.StopRole): String = when (role) {
        pl.reactivebike.routing.StopRole.START -> START_COLOR
        pl.reactivebike.routing.StopRole.VIA -> VIA_COLOR
        pl.reactivebike.routing.StopRole.DESTINATION -> DESTINATION_COLOR
    }

    private fun labelOf(
        stop: pl.reactivebike.routing.PlannedStop,
        index: Int,
        stops: List<pl.reactivebike.routing.PlannedStop>,
    ): String = when (stop.role) {
        pl.reactivebike.routing.StopRole.START -> "Start"
        pl.reactivebike.routing.StopRole.DESTINATION -> "Koniec"
        // Numerujemy wyłącznie punkty pośrednie, żeby „1" znaczyło pierwszy przystanek,
        // a nie pierwszy punkt na liście — start bywa, a bywa i nie.
        pl.reactivebike.routing.StopRole.VIA ->
            (stops.take(index).count { it.role == pl.reactivebike.routing.StopRole.VIA } + 1).toString()
    }

    /** Zdejmuje trasę i punkty planu z mapy. */
    fun clearRoute() {
        val style = map?.style ?: return
        stopsToDraw = emptyList()
        val empty = FeatureCollection.fromFeatures(emptyList())
        (style.getSource(SOURCE_ROUTE) as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(empty)
        (style.getSource(SOURCE_DESTINATION) as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(empty)
    }

    /** Dokłada odczyt pozycji do śladu i przesuwa kamerę, o ile użytkownik jej nie przejął. */
    fun updatePosition(latitude: Double, longitude: Double) {
        val point = Point.fromLngLat(longitude, latitude)

        val previous = track.lastOrNull()
        if (previous == null || previous.longitude() != longitude || previous.latitude() != latitude) {
            track.add(point)
            if (track.size > MAX_TRACK_POINTS) {
                track.removeAt(0)
            }
        }

        val target = map ?: return
        if (followPosition) {
            target.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), ZOOM))
        }
        if (styleReady) {
            target.style?.let { redraw(it) }
        }
    }

    /**
     * Przesuwa kamerę na wskazany punkt — np. na miejsce znalezione po nazwie.
     *
     * Przy okazji wyłącza podążanie za pozycją: użytkownik chce zobaczyć to, czego szukał,
     * a nie zostać w pół sekundy przerzucony z powrotem nad własny rower.
     */
    fun focusOn(latitude: Double, longitude: Double) {
        followPosition = false
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), ZOOM))
    }

    /** Przywraca podążanie kamery za pozycją po tym, jak użytkownik przesunął mapę. */
    fun recenter() {
        followPosition = true
        val last = track.lastOrNull() ?: return
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude(), last.longitude()), ZOOM),
        )
    }

    private fun redraw(style: Style) {
        val last = track.lastOrNull() ?: return

        (style.getSource(SOURCE_POSITION) as? org.maplibre.android.style.sources.GeoJsonSource)
            ?.setGeoJson(Feature.fromGeometry(last))

        if (track.size >= 2) {
            (style.getSource(SOURCE_TRACK) as? org.maplibre.android.style.sources.GeoJsonSource)
                ?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(track.toList())))
        }
    }

    // --- cykl życia ---

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onSaveInstanceState(outState: Bundle) = view.onSaveInstanceState(outState)
    fun onLowMemory() = view.onLowMemory()
    fun onDestroy() = view.onDestroy()

    private companion object {
        /** Styl OSM bez klucza API. */
        const val PRIMARY_STYLE = "https://tiles.openfreemap.org/styles/liberty"

        /** Styl zapasowy, gdyby podstawowy był niedostępny — ubogi, ale zawsze dostępny. */
        const val FALLBACK_STYLE = "https://demotiles.maplibre.org/style.json"

        const val SOURCE_POSITION = "rb-position-source"
        const val LAYER_POSITION = "rb-position-layer"
        const val SOURCE_ROUTE = "rb-route-source"
        const val LAYER_ROUTE = "rb-route-layer"
        const val SOURCE_DESTINATION = "rb-destination-source"
        const val LAYER_DESTINATION = "rb-destination-layer"
        const val LAYER_STOP_LABELS = "rb-stop-labels-layer"
        const val PROPERTY_STOP_COLOR = "rb-stop-color"
        const val PROPERTY_STOP_LABEL = "rb-stop-label"
        const val SOURCE_TRACK = "rb-track-source"
        const val LAYER_TRACK = "rb-track-layer"

        const val ZOOM = 15.0

        /** Widok startowy, gdy nie znamy jeszcze żadnej pozycji — środek Polski. */
        val DEFAULT_CAMERA = LatLng(52.0, 19.4)
        const val DEFAULT_ZOOM = 5.5

        const val MAX_TRACK_POINTS = 2_000

        val POSITION_COLOR = Color.parseColor("#2563EB")
        val ROUTE_COLOR = Color.parseColor("#7C3AED")
        val TRACK_COLOR = Color.parseColor("#F97316")

        // Kolory punktow planu jako tekst — trafiaja do cech GeoJSON, ktore MapLibre
        // czyta wyrazeniem, a nie jako liczby ARGB.
        const val START_COLOR = "#059669"
        const val VIA_COLOR = "#D97706"
        const val DESTINATION_COLOR = "#DC2626"
    }
}
