package pl.reactivebike.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import pl.reactivebike.gps.GpsState
import pl.reactivebike.gps.GpsStateMachine
import pl.reactivebike.gps.RideSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.reactivebike.routing.EdgeWeight
import pl.reactivebike.routing.GeoPoint
import pl.reactivebike.routing.ManeuverAnnouncer
import pl.reactivebike.routing.OffRouteDetector
import pl.reactivebike.routing.Route
import pl.reactivebike.routing.RouteFailure
import pl.reactivebike.routing.RouteProgress
import pl.reactivebike.routing.RouteRequest
import pl.reactivebike.routing.RouteResult
import pl.reactivebike.routing.RouteTracker
import pl.reactivebike.weather.CachedWeatherWeights
import pl.reactivebike.weather.LocalWeightsTranslator
import pl.reactivebike.weather.OfflineWeatherPolicy
import pl.reactivebike.weather.OpenMeteoClient
import pl.reactivebike.weather.PressureReading
import pl.reactivebike.weather.StormDetector
import pl.reactivebike.weather.WeatherConditions
import pl.reactivebike.weather.WeatherFetchResult
import pl.reactivebike.weather.WeatherWeightsSource
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pulpit przejazdu — logika z modułu `shared` wpięta w żywe czujniki telefonu.
 *
 * Trzy mechanizmy ze specyfikacji faktycznie tu pracują, a nie są tylko demonstrowane:
 *
 * - **maszyna stanów GPS (sekcja 7)** steruje realną częstotliwością odpytywania lokalizacji,
 *   a w stanie `STATIONARY` całkowicie wyłącza odbiornik — to jest ta oszczędność baterii,
 *   nie jej opis,
 * - **detektor burzy (sekcja 8)** czyta natywny barometr i sam decyduje o Storm Mode,
 * - **polityka offline (sekcja 8.1)** rozstrzyga, które wagi obowiązują: z bieżącego pomiaru
 *   ciśnienia, ze zbuforowanej prognozy czy domyślne.
 *
 * Trasa wyznaczana jest przez [HttpRouteEngine] — pierwszą implementację portu z ADR-0001.
 * Odległość do najbliższego manewru trafia do maszyny stanów, dzięki czemu stan `CRITICAL`
 * wreszcie się pojawia; bez wyznaczonej trasy był nieosiągalny.
 *
 * Czego tu **nie ma**: trasowania offline. Silnik na urządzeniu podmieni implementację
 * portu, nie ruszając tej klasy.
 */
class MainActivity : Activity(), LocationListener, SensorEventListener {

    private lateinit var dashboard: RideDashboard
    private var mapPanel: MapPanel? = null
    private var offlineMaps: OfflineMapDownloader? = null
    private lateinit var locationManager: LocationManager
    private var sensorManager: SensorManager? = null
    private var pressureSensor: Sensor? = null
    private var accelerometer: Sensor? = null

    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()

    // --- stan przejazdu ---

    private var gpsState: GpsState = GpsStateMachine.INITIAL_STATE
    private var registeredIntervalSeconds: Int? = null
    private var locationUpdatesActive = false

    private var lastLocation: Location? = null
    private var lastFixAtMillis: Long = 0

    private val pressureReadings = mutableListOf<PressureReading>()
    private var latestPressureHpa: Double? = null

    private val accelerationWindow = ArrayDeque<Double>()
    private var motionDetected = false

    private var screenOn = true

    private var conditions: WeatherConditions? = null
    private var cachedWeather: CachedWeatherWeights? = null
    private var weatherStatus: String? = null
    private var weatherRequestInFlight = false

    private val routeEngine = HttpRouteEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var destination: GeoPoint? = null
    private var currentRoute: Route? = null
    private var routeProgress: RouteProgress? = null
    private var routeStatus: String? = null
    private var routeRequestInFlight = false

    private val announcer = ManeuverAnnouncer()
    private val offRouteDetector = OffRouteDetector()
    private var textToSpeech: TextToSpeech? = null
    private var voiceReady = false
    private var voiceEnabled = true

    private val stormDetector = StormDetector()
    private val offlinePolicy = OfflineWeatherPolicy()

    private val ticker = object : Runnable {
        override fun run() {
            recomputeGpsState()
            render()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mapa jest opcjonalna: gdyby MapLibre nie wystartował, pulpit ma dalej działać,
        // bo prędkość, ciśnienie i pogoda nie zależą od renderowania kafelków.
        //
        // Powód awarii pokazujemy na ekranie zamiast go połykać — cicha degradacja
        // wygląda dla użytkownika identycznie jak zepsuta aplikacja.
        var mapError: String? = null
        val panel = try {
            MapPanel(this).also { it.onCreate(savedInstanceState) }
        } catch (t: Throwable) {
            mapError = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
            null
        }
        mapPanel = panel

        dashboard = RideDashboard(this)
        setContentView(dashboard.build(panel?.view))
        dashboard.refreshButton.setOnClickListener { requestWeather(force = true) }
        dashboard.recenterButton.setOnClickListener { mapPanel?.recenter() }

        setUpOfflineMaps(panel, mapError)

        panel?.onDestinationPicked = { lat, lon -> requestRoute(GeoPoint(lat, lon)) }
        dashboard.clearRouteButton.setOnClickListener { clearRoute() }
        dashboard.voiceButton.setOnClickListener { toggleVoice() }

        setUpVoice()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        restoreCachedWeather()
        render()
    }

    override fun onStart() {
        super.onStart()
        mapPanel?.onStart()
        screenOn = true

        if (hasLocationPermission()) {
            seedMapWithLastKnownLocation()
            applyLocationUpdates()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
        }

        pressureSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        mapPanel?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapPanel?.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapPanel?.onStop()
        // Ekran wygaszony albo aplikacja w tle — dla maszyny stanów to warunek wejścia w SLEEP.
        screenOn = false
        handler.removeCallbacks(ticker)
        sensorManager?.unregisterListener(this)
        stopLocationUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapPanel?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapPanel?.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        scope.cancel()
        offlineMaps?.stop()
        mapPanel?.onDestroy()
        network.shutdownNow()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return

        if (hasLocationPermission()) {
            seedMapWithLastKnownLocation()
            applyLocationUpdates()
        } else {
            Toast.makeText(
                this,
                "Bez dostępu do lokalizacji pulpit pokaże tylko ciśnienie i pogodę.",
                Toast.LENGTH_LONG,
            ).show()
        }
        render()
    }

    /**
     * Ustawia mapę na ostatniej znanej pozycji z systemu, zanim przyjdzie pierwszy fix.
     *
     * Bez tego mapa otwiera się na widoku całego kraju i nie da się sensownie wskazać
     * obszaru do pobrania offline, dopóki GPS nie złapie sygnału — a w budynku może
     * nie złapać wcale.
     */
    private fun seedMapWithLastKnownLocation() {
        val panel = mapPanel ?: return
        if (!hasLocationPermission()) return

        val known = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .asSequence()
                .filter { locationManager.isProviderEnabled(it) }
                .mapNotNull { locationManager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        } ?: return

        panel.setInitialPosition(known.latitude, known.longitude)
        if (lastLocation == null) {
            lastLocation = known
            lastFixAtMillis = known.time
            requestWeather(force = false)
        }
    }

    // --- mapy offline ---

    private fun setUpOfflineMaps(panel: MapPanel?, mapError: String?) {
        if (panel == null) {
            dashboard.set(RideDashboard.KEY_OFFLINE_STATUS, "Mapa się nie uruchomiła — pobieranie wyłączone.")
            dashboard.set(RideDashboard.KEY_OFFLINE_DETAIL, mapError?.let { "Powód: $it" })
            dashboard.offlineDownloadButton.isEnabled = false
            dashboard.offlineDeleteButton.isEnabled = false
            return
        }

        val downloader = try {
            OfflineMapDownloader(this)
        } catch (_: Throwable) {
            dashboard.set(RideDashboard.KEY_OFFLINE_STATUS, "Pobieranie map niedostępne.")
            dashboard.offlineDownloadButton.isEnabled = false
            dashboard.offlineDeleteButton.isEnabled = false
            return
        }
        offlineMaps = downloader

        downloader.observe { state ->
            dashboard.set(RideDashboard.KEY_OFFLINE_STATUS, state.summary)
            dashboard.set(RideDashboard.KEY_OFFLINE_DETAIL, state.detail)
            dashboard.offlineDownloadButton.isEnabled = !state.busy
            dashboard.offlineDeleteButton.isEnabled = !state.busy
        }

        dashboard.offlineDownloadButton.setOnClickListener {
            val bounds = panel.visibleBounds()
            if (bounds == null) {
                Toast.makeText(this, "Mapa jeszcze się nie wczytała.", Toast.LENGTH_SHORT).show()
            } else {
                downloader.download(panel.activeStyleUrl, bounds)
            }
        }
        dashboard.offlineDeleteButton.setOnClickListener { downloader.deleteAll() }

        downloader.refresh()
    }


    // --- trasowanie ---

    /** Wyznacza trasę z bieżącej pozycji do wskazanego punktu. */
    private fun requestRoute(target: GeoPoint) {
        val from = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
        if (from == null) {
            Toast.makeText(this, "Czekam na pozycję — bez niej nie ma skąd wyznaczyć trasy.", Toast.LENGTH_SHORT).show()
            return
        }
        if (routeRequestInFlight) return

        destination = target
        announcer.reset()
        offRouteDetector.reset()
        routeRequestInFlight = true
        routeStatus = "Wyznaczam trasę…"
        mapPanel?.showRoute(emptyList(), target)
        render()

        routeEngine.conditions = conditions

        scope.launch {
            val result = routeEngine.route(RouteRequest(listOf(from, target)))
            routeRequestInFlight = false

            when (result) {
                is RouteResult.Success -> {
                    currentRoute = result.route
                    routeStatus = null
                    mapPanel?.showRoute(result.route.geometry, target)
                }

                is RouteResult.Failure -> {
                    currentRoute = null
                    routeStatus = when (result.reason) {
                        RouteFailure.NO_ROUTE_FOUND -> "Nie znalazłem trasy do tego punktu."
                        RouteFailure.MISSING_MAP_DATA -> "Brak danych mapowych dla tego obszaru."
                        RouteFailure.ENGINE_ERROR -> "Trasowanie niedostępne — sprawdź połączenie."
                    }
                }
            }
            render()
        }
    }

    private fun clearRoute() {
        destination = null
        currentRoute = null
        routeProgress = null
        routeStatus = null
        announcer.reset()
        offRouteDetector.reset()
        mapPanel?.clearRoute()
        render()
    }

    private fun renderRoute() {
        val route = currentRoute
        val status = routeStatus

        if (route == null) {
            dashboard.set(
                RideDashboard.KEY_ROUTE_SUMMARY,
                status ?: "Brak trasy. Przytrzymaj palec na mapie, żeby wskazać cel.",
            )
            dashboard.set(RideDashboard.KEY_NEXT_MANEUVER, null)
            dashboard.set(RideDashboard.KEY_ROUTE_REMAINING, null)
            dashboard.clearRouteButton.isEnabled = destination != null
            return
        }

        dashboard.clearRouteButton.isEnabled = true
        dashboard.set(
            RideDashboard.KEY_ROUTE_SUMMARY,
            "${formatDistance(route.distanceMeters)}, ok. ${formatDuration(route.estimatedDurationSeconds)}",
        )

        val progress = routeProgress
        dashboard.set(
            RideDashboard.KEY_NEXT_MANEUVER,
            progress?.nextManeuver?.let { maneuver ->
                val distance = progress.distanceToNextManeuverMeters?.let { " za ${formatDistance(it)}" }.orEmpty()
                "${maneuver.instruction}$distance"
            } ?: "Jedź dalej.",
        )
        dashboard.set(
            RideDashboard.KEY_ROUTE_REMAINING,
            progress?.let {
                val off = if (RouteTracker.isOffRoute(it)) " — zjechałeś z trasy" else ""
                "Do celu: ${formatDistance(it.remainingDistanceMeters)}$off"
            },
        )
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1_000) "${(meters / 100).roundToInt() / 10.0} km" else "${meters.roundToInt()} m"

    private fun formatDuration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }


    // --- prowadzenie ---

    private fun setUpVoice() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val result = textToSpeech?.setLanguage(Locale("pl", "PL"))
            voiceReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!voiceReady) {
                // Brak polskiego głosu nie może wywracać nawigacji — instrukcje zostają
                // na ekranie, a przycisk mówi wprost, czemu nic nie słychać.
                handler.post { dashboard.voiceButton.text = "Brak polskiego głosu" }
            }
        }
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        if (!voiceEnabled) textToSpeech?.stop()
        dashboard.voiceButton.text = if (voiceEnabled) "Głos: włączony" else "Głos: wyłączony"
    }

    private fun speak(text: String) {
        if (!voiceEnabled || !voiceReady) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rb-guidance")
    }

    /**
     * Reaguje na postęp na trasie: zapowiada manewry i przelicza trasę po zjechaniu.
     *
     * Obie decyzje podejmuje logika z modułu wspólnego — tutaj zostaje wywołanie syntezy
     * mowy i ponowne zapytanie do silnika.
     */
    private fun handleGuidance(progress: RouteProgress) {
        announcer.announce(progress)?.let { speak(it) }

        if (offRouteDetector.update(progress)) {
            val target = destination ?: return
            speak("Zjechałeś z trasy. Wyznaczam nową.")
            requestRoute(target)
        }
    }

    // --- lokalizacja ---

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Wpina częstotliwość wynikającą z [gpsState] w odbiornik.
     *
     * W stanie `STATIONARY` interwał jest `null` — wtedy nasłuch jest zdejmowany całkowicie,
     * bo na tym polega zerowe zużycie GPS podczas postoju.
     */
    private fun applyLocationUpdates() {
        if (!hasLocationPermission()) return

        val interval = gpsState.samplingIntervalSeconds
        if (interval == null) {
            stopLocationUpdates()
            return
        }
        if (locationUpdatesActive && registeredIntervalSeconds == interval) return

        try {
            locationManager.removeUpdates(this)
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: return

            locationManager.requestLocationUpdates(provider, interval * 1000L, 0f, this)
            locationUpdatesActive = true
            registeredIntervalSeconds = interval
        } catch (_: SecurityException) {
            locationUpdatesActive = false
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
            // nic — i tak przestajemy nasłuchiwać
        }
        locationUpdatesActive = false
        registeredIntervalSeconds = null
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        lastFixAtMillis = System.currentTimeMillis()
        mapPanel?.updatePosition(location.latitude, location.longitude)
        requestWeather(force = false)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) {
        applyLocationUpdates()
    }

    override fun onProviderDisabled(provider: String) {
        stopLocationUpdates()
    }

    // --- czujniki ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> recordPressure(event.values[0].toDouble())
            Sensor.TYPE_ACCELEROMETER -> recordAcceleration(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun recordPressure(hectopascals: Double) {
        if (hectopascals <= 0.0 || !hectopascals.isFinite()) return
        latestPressureHpa = hectopascals

        val now = nowSeconds()
        val last = pressureReadings.lastOrNull()
        // Jeden odczyt na minutę wystarcza do wykrycia trendu w oknie trzygodzinnym.
        if (last == null || now - last.atEpochSeconds >= PRESSURE_SAMPLE_SECONDS) {
            pressureReadings.add(PressureReading(hectopascals, now))
            val cutoff = now - PRESSURE_WINDOW_SECONDS
            pressureReadings.removeAll { it.atEpochSeconds < cutoff }
        }
    }

    /**
     * Prosty detektor ruchu: zmienność modułu przyspieszenia w krótkim oknie.
     *
     * Zgodnie z ADR-0004 wejście w `STATIONARY` wymaga potwierdzenia bezruchu, bo sama
     * prędkość poniżej progu wyłączyłaby GPS rowerzyście mozolnie jadącemu pod górę.
     */
    private fun recordAcceleration(values: FloatArray) {
        if (values.size < 3) return
        val magnitude = sqrt(
            (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble(),
        )

        accelerationWindow.addLast(magnitude)
        while (accelerationWindow.size > ACCELERATION_WINDOW_SAMPLES) {
            accelerationWindow.removeFirst()
        }
        if (accelerationWindow.size < 4) return

        val spread = (accelerationWindow.maxOrNull() ?: 0.0) - (accelerationWindow.minOrNull() ?: 0.0)
        motionDetected = spread > MOTION_THRESHOLD
    }

    // --- maszyna stanów ---

    private fun recomputeGpsState() {
        val location = lastLocation
        val speedKmh = location?.takeIf { it.hasSpeed() }?.speed?.times(3.6)?.toDouble() ?: 0.0

        // Postęp na trasie liczymy tutaj, bo to on dostarcza odległość do manewru —
        // jedyne wejście, które w ogóle pozwala maszynie stanów wejść w CRITICAL.
        routeProgress = currentRoute
            ?.let { route -> location?.let { RouteTracker.progress(route, GeoPoint(it.latitude, it.longitude)) } }
        routeProgress?.let { handleGuidance(it) }

        val signals = RideSignals(
            distanceToManeuverMeters = routeProgress?.distanceToNextManeuverMeters,
            speedKmh = speedKmh,
            screenOn = screenOn,
            motionDetected = motionDetected,
        )

        val next = GpsStateMachine.next(gpsState, signals)
        if (next != gpsState) {
            gpsState = next
            applyLocationUpdates()
        } else if (gpsState.samplingIntervalSeconds != null && !locationUpdatesActive) {
            applyLocationUpdates()
        }
    }

    // --- pogoda ---

    private fun restoreCachedWeather() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_WEATHER_JSON, null) ?: return
        val issuedAt = prefs.getLong(KEY_WEATHER_AT, 0L)
        if (issuedAt <= 0L) return

        val parsed = OpenMeteoClient.parse(raw)
        if (parsed is WeatherFetchResult.Success) {
            conditions = parsed.conditions
            cachedWeather = CachedWeatherWeights(
                weights = LocalWeightsTranslator.translate(parsed.conditions),
                forecastIssuedAtEpochSeconds = issuedAt,
            )
        }
    }

    private fun storeWeather(raw: String, issuedAt: Long) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WEATHER_JSON, raw)
            .putLong(KEY_WEATHER_AT, issuedAt)
            .apply()
    }

    /**
     * Pobiera prognozę dla bieżącej pozycji.
     *
     * Bez wymuszenia zapytanie leci tylko wtedy, gdy bufor jest pusty albo przeterminowany —
     * offline aplikacja ma korzystać z tego, co już ma, a nie dobijać się do sieci.
     */
    private fun requestWeather(force: Boolean) {
        val location = lastLocation ?: run {
            if (force) weatherStatus = "Czekam na pozycję z GPS."
            return
        }
        if (weatherRequestInFlight) return

        if (!force) {
            val cached = cachedWeather
            val ageSeconds = cached?.let { nowSeconds() - it.forecastIssuedAtEpochSeconds }
            if (ageSeconds != null && ageSeconds < WEATHER_REFRESH_SECONDS) return
        }

        weatherRequestInFlight = true
        weatherStatus = "Pobieram prognozę…"
        val url = OpenMeteoClient.currentWeatherUrl(location.latitude, location.longitude)

        network.execute {
            val body = try {
                fetch(url)
            } catch (e: Exception) {
                null
            }

            handler.post {
                weatherRequestInFlight = false
                when (val parsed = OpenMeteoClient.parse(body)) {
                    is WeatherFetchResult.Success -> {
                        val issuedAt = nowSeconds()
                        conditions = parsed.conditions
                        cachedWeather = CachedWeatherWeights(
                            weights = LocalWeightsTranslator.translate(parsed.conditions),
                            forecastIssuedAtEpochSeconds = issuedAt,
                        )
                        body?.let { storeWeather(it, issuedAt) }
                        weatherStatus = null
                    }

                    is WeatherFetchResult.Failure ->
                        weatherStatus = "Prognoza niedostępna (${parsed.reason}). Jadę na tym, co mam."
                }
                render()
            }
        }
    }

    private fun fetch(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MILLIS
            readTimeout = HTTP_TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    // --- prezentacja ---

    private fun render() {
        renderSpeed()
        renderRoute()
        renderPosition()
        renderGps()
        renderPressure()
        renderWeather()
        renderWeights()
    }

    private fun renderSpeed() {
        val location = lastLocation
        if (location == null || !location.hasSpeed()) {
            dashboard.setSpeed("—", if (hasLocationPermission()) "czekam na sygnał GPS" else "brak zgody na lokalizację")
            return
        }
        val kmh = location.speed * 3.6
        dashboard.setSpeed(oneDecimal(kmh.toDouble()), "km/h")
    }

    private fun renderPosition() {
        val location = lastLocation
        if (location == null) {
            dashboard.set(RideDashboard.KEY_COORDS, "Brak pozycji")
            dashboard.set(RideDashboard.KEY_ACCURACY, null)
            dashboard.set(RideDashboard.KEY_ALTITUDE, null)
            dashboard.set(RideDashboard.KEY_FIX_AGE, null)
            return
        }

        dashboard.set(
            RideDashboard.KEY_COORDS,
            "${fiveDecimals(location.latitude)}, ${fiveDecimals(location.longitude)}",
        )
        dashboard.set(
            RideDashboard.KEY_ACCURACY,
            if (location.hasAccuracy()) "Dokładność ${location.accuracy.roundToInt()} m" else null,
        )
        dashboard.set(
            RideDashboard.KEY_ALTITUDE,
            if (location.hasAltitude()) "Wysokość ${location.altitude.roundToInt()} m n.p.m." else null,
        )
        val ageSeconds = ((System.currentTimeMillis() - lastFixAtMillis) / 1000).toInt()
        dashboard.set(RideDashboard.KEY_FIX_AGE, "Ostatni odczyt: ${ageSeconds} s temu")
    }

    private fun renderGps() {
        dashboard.set(RideDashboard.KEY_GPS_STATE, "Stan: ${gpsState.name}")
        dashboard.set(
            RideDashboard.KEY_GPS_RATE,
            gpsState.samplingIntervalSeconds
                ?.let { "Odczyt co ${it} s (${trimZero(gpsState.frequencyHz)} Hz)" }
                ?: "Odbiornik GPS wyłączony",
        )
        dashboard.set(
            RideDashboard.KEY_GPS_REASON,
            when (gpsState) {
                GpsState.CRITICAL -> "Manewr w zasięgu — maksymalna precyzja."
                GpsState.CRUISE -> "Jazda po prostym odcinku."
                GpsState.SLEEP -> "Ekran wygaszony — pozycja uzupełniana predykcją."
                GpsState.STATIONARY -> "Postój. Wybudzę się z akcelerometru, nie z GPS."
            },
        )
    }

    private fun renderPressure() {
        val pressure = latestPressureHpa
        if (pressureSensor == null) {
            dashboard.set(RideDashboard.KEY_PRESSURE, "Ten telefon nie ma barometru")
            dashboard.set(RideDashboard.KEY_PRESSURE_TREND, null)
            dashboard.set(RideDashboard.KEY_STORM, "Wykrywanie burzy niedostępne.")
            return
        }
        if (pressure == null) {
            dashboard.set(RideDashboard.KEY_PRESSURE, "Czekam na odczyt barometru…")
            dashboard.set(RideDashboard.KEY_PRESSURE_TREND, null)
            dashboard.set(RideDashboard.KEY_STORM, null)
            return
        }

        dashboard.set(RideDashboard.KEY_PRESSURE, "${oneDecimal(pressure)} hPa")

        val peak = pressureReadings.maxOfOrNull { it.hectopascals }
        dashboard.set(
            RideDashboard.KEY_PRESSURE_TREND,
            if (peak == null || pressureReadings.size < 2) {
                "Zbieram historię (${pressureReadings.size} odczytów z 3 h)"
            } else {
                val delta = pressure - peak
                "Zmiana od szczytu w oknie 3 h: ${oneDecimal(delta)} hPa"
            },
        )

        val verdict = stormDetector.evaluate(pressureReadings, nowSeconds())
        dashboard.set(
            RideDashboard.KEY_STORM,
            when (verdict) {
                is pl.reactivebike.weather.StormVerdict.StormApproaching ->
                    "⚠ Storm Mode: ciśnienie spadło o ${oneDecimal(verdict.dropHpa)} hPa."
                pl.reactivebike.weather.StormVerdict.Calm -> "Ciśnienie stabilne."
                pl.reactivebike.weather.StormVerdict.InsufficientData -> "Za mało odczytów, żeby ocenić trend."
            },
        )
    }

    private fun renderWeather() {
        val current = conditions
        val status = weatherStatus

        if (current == null) {
            dashboard.set(RideDashboard.KEY_WEATHER, status ?: "Brak danych pogodowych.")
            dashboard.set(RideDashboard.KEY_TEMPERATURE, null)
            dashboard.set(RideDashboard.KEY_PRECIPITATION, null)
            dashboard.set(RideDashboard.KEY_WIND, null)
            dashboard.set(RideDashboard.KEY_WEATHER_AGE, null)
            return
        }

        dashboard.set(RideDashboard.KEY_WEATHER, current.description.replaceFirstChar { it.uppercase() })
        dashboard.set(
            RideDashboard.KEY_TEMPERATURE,
            current.temperatureCelsius?.let { "Temperatura ${oneDecimal(it)} °C" },
        )
        dashboard.set(
            RideDashboard.KEY_PRECIPITATION,
            current.precipitationMm?.let { "Opad ${oneDecimal(it)} mm" },
        )
        dashboard.set(
            RideDashboard.KEY_WIND,
            current.windSpeedKmh?.let { "Wiatr ${oneDecimal(it)} km/h" },
        )

        val cached = cachedWeather
        val ageMinutes = cached?.let { (nowSeconds() - it.forecastIssuedAtEpochSeconds) / 60 }
        dashboard.set(
            RideDashboard.KEY_WEATHER_AGE,
            status ?: ageMinutes?.let { "Prognoza sprzed ${it} min" },
        )
    }

    private fun renderWeights() {
        val source = offlinePolicy.resolve(cachedWeather, pressureReadings, nowSeconds())

        dashboard.set(
            RideDashboard.KEY_WEIGHTS_SOURCE,
            when (source) {
                is WeatherWeightsSource.StormMode -> "Storm Mode (spadek ${oneDecimal(source.dropHpa)} hPa)"
                is WeatherWeightsSource.Cached -> "Ze zbuforowanej prognozy"
                is WeatherWeightsSource.Default -> "Domyślne — bez adaptacji do pogody"
            },
        )

        dashboard.set(
            RideDashboard.KEY_WEIGHTS_REASON,
            when (source) {
                is WeatherWeightsSource.StormMode ->
                    "Bieżący pomiar ciśnienia ma pierwszeństwo przed prognozą."
                is WeatherWeightsSource.Cached ->
                    conditions?.let { LocalWeightsTranslator.explain(it) }
                is WeatherWeightsSource.Default ->
                    "Brak ważnej prognozy i brak sygnałów burzowych."
            },
        )

        val penalised = source.weights.surface.overrides
            .filter { it.value > EdgeWeight.NEUTRAL }
            .toList()
            .sortedByDescending { it.second }
            .take(4)

        dashboard.set(
            RideDashboard.KEY_WEIGHTS_DETAIL,
            if (penalised.isEmpty()) {
                "Żadna nawierzchnia nie jest odstraszana."
            } else {
                "Odstraszane: " + penalised.joinToString(", ") { (name, weight) ->
                    if (weight >= EdgeWeight.IMPASSABLE) "$name (zakaz)" else "$name ×${oneDecimal(weight)}"
                }
            },
        )
    }

    // --- pomocnicze ---

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return rounded.toString()
    }

    private fun fiveDecimals(value: Double): String {
        val rounded = (value * 100_000).roundToInt() / 100_000.0
        return rounded.toString()
    }

    private fun trimZero(value: Double): String {
        val rounded = (value * 100).roundToInt() / 100.0
        return if (abs(rounded - rounded.toInt()) < 1e-9) rounded.toInt().toString() else rounded.toString()
    }

    private companion object {
        const val REQUEST_LOCATION = 1

        const val TICK_MILLIS = 1_000L
        const val HTTP_TIMEOUT_MILLIS = 10_000

        /** Odstęp między zapisywanymi odczytami ciśnienia. */
        const val PRESSURE_SAMPLE_SECONDS = 60L

        /** Okno historii ciśnienia — zgodne z oknem detektora burzy. */
        const val PRESSURE_WINDOW_SECONDS = 3 * 60 * 60L

        /** Po tylu sekundach prognoza jest odświeżana samoczynnie. */
        const val WEATHER_REFRESH_SECONDS = 30 * 60L

        const val ACCELERATION_WINDOW_SAMPLES = 12
        const val MOTION_THRESHOLD = 0.6

        const val PREFS = "reactivebike"
        const val KEY_WEATHER_JSON = "last_weather_json"
        const val KEY_WEATHER_AT = "last_weather_at"
    }
}
