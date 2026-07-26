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
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Feature
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

    val view: MapView = MapView(context)

    private var map: MapLibreMap? = null
    private var styleReady = false
    private var triedFallbackStyle = false

    private val track = mutableListOf<Point>()
    private var followPosition = true

    init {
        MapLibre.getInstance(context)
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
        target.setStyle(styleUrl) { style ->
            styleReady = true
            addLayers(style)
            redraw(style)
        }
    }

    private fun addLayers(style: Style) {
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
        const val SOURCE_TRACK = "rb-track-source"
        const val LAYER_TRACK = "rb-track-layer"

        const val ZOOM = 15.0
        const val MAX_TRACK_POINTS = 2_000

        val POSITION_COLOR = Color.parseColor("#2563EB")
        val TRACK_COLOR = Color.parseColor("#F97316")
    }
}
