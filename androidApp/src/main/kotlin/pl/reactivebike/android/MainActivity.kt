package pl.reactivebike.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import pl.reactivebike.gps.GpsState
import pl.reactivebike.gps.GpsStateMachine
import pl.reactivebike.geocoding.GeocodeResult
import pl.reactivebike.geocoding.Place
import pl.reactivebike.gps.RideSignals
import pl.reactivebike.maps.OfflineRegionEstimate
import pl.reactivebike.maps.OfflineRegionEstimator
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
import pl.reactivebike.routing.ArrivalDetector
import pl.reactivebike.routing.EtaBasis
import pl.reactivebike.routing.NavigationPhase
import pl.reactivebike.routing.RouteEta
import pl.reactivebike.routing.RouteProgress
import pl.reactivebike.routing.RoutePlan
import pl.reactivebike.routing.RouteRequest
import pl.reactivebike.routing.RouteResult
import pl.reactivebike.routing.RouteTracker
import pl.reactivebike.routing.valhalla.BicycleProfile
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
    private val geocoder = HttpGeocoder()
    private var searchInFlight = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Plan przejazdu: start, punkty pośrednie i cel. Jedyne źródło prawdy o trasie. */
    private var plan = RoutePlan()

    /** Rower, na którym jedzie użytkownik — decyduje o rodzaju wyznaczanych tras. */
    private var bicycleProfile = BicycleProfile.TREKKING

    /** Etap przejazdu: układanie trasy, jazda albo dojazd na miejsce. */
    private var phase = NavigationPhase.PLANNING

    private var currentRoute: Route? = null
    private var routeProgress: RouteProgress? = null
    private var routeStatus: String? = null
    private var routeRequestInFlight = false

    private val announcer = ManeuverAnnouncer()
    private val offRouteDetector = OffRouteDetector()
    private var textToSpeech: TextToSpeech? = null
    private var voiceReady = false
    private var voiceEnabled = true

    /** Ustawione, gdy syntezator nie ma polskiego głosu — wtedy przycisk mówi o tym wprost. */
    private var voiceUnavailable = false

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

        panel?.onDestinationPicked = { lat, lon -> addStop(GeoPoint(lat, lon)) }
        dashboard.clearRouteButton.setOnClickListener { clearRoute() }
        dashboard.undoStopButton.setOnClickListener { undoStop() }
        dashboard.startHereButton.setOnClickListener { toggleStart() }
        dashboard.profileButton.setOnClickListener { pickBicycleProfile() }
        dashboard.searchAddButton.setOnClickListener { searchPlace(asStart = false) }
        dashboard.searchStartButton.setOnClickListener { searchPlace(asStart = true) }
        dashboard.navigationButton.setOnClickListener { toggleNavigation() }
        setUpSearchField()

        // Pozycje z usługi wchodzą tą samą drogą co z odbiornika w aktywności, więc reszta
        // logiki nie musi wiedzieć, które źródło akurat pracuje.
        NavigationService.onLocation = { location -> handler.post { onLocationChanged(location) } }
        NavigationService.onStopRequested = { handler.post { stopNavigation("Nawigacja zakończona.") } }
        dashboard.voiceButton.setOnClickListener { toggleVoice() }
        restoreVoicePreference()

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

        // W trakcie jazdy nie rozbieramy stanu. Pozycje płyną z usługi pierwszoplanowej,
        // a pętla odświeżania musi dalej chodzić, bo to ona przelicza stan GPS i utrzymuje
        // treść powiadomienia. Poza jazdą zwalniamy wszystko, jak dotąd.
        if (phase.isGuiding) {
            // Czujniki zdejmujemy mimo wszystko: barometr i akcelerometr nie są potrzebne
            // do prowadzenia, a przy zgaszonym ekranie system i tak je ogranicza.
            // Konsekwencja: Storm Mode nie aktualizuje się w kieszeni — do poprawy.
            sensorManager?.unregisterListener(this)
            return
        }

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
        // Wskaźniki w usłudze są statyczne, więc bez wyzerowania trzymałyby zniszczoną
        // aktywność przy życiu przez cały przejazd.
        NavigationService.onLocation = null
        NavigationService.onStopRequested = null
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
                return@setOnClickListener
            }

            val start = { downloader.download(panel.activeStyleUrl, bounds) }
            val estimate = estimateOrNull(bounds)

            if (estimate != null && OfflineRegionEstimator.isLarge(estimate)) {
                confirmLargeDownload(estimate, start)
            } else {
                start()
            }
        }
        dashboard.offlineDeleteButton.setOnClickListener { downloader.deleteAll() }

        // Rozmiar pokazujemy przy każdym zatrzymaniu kamery, a nie dopiero po naciśnięciu
        // przycisku — użytkownik ma widzieć koszt, kiedy jeszcze wybiera obszar.
        panel.onVisibleAreaChanged = { showVisibleAreaEstimate(panel) }
        showVisibleAreaEstimate(panel)

        downloader.refresh()
    }

    private fun showVisibleAreaEstimate(panel: MapPanel) {
        val bounds = panel.visibleBounds() ?: return
        val estimate = estimateOrNull(bounds) ?: return

        val size = OfflineRegionEstimator.describeSize(estimate)
        dashboard.set(
            RideDashboard.KEY_OFFLINE_ESTIMATE,
            if (OfflineRegionEstimator.isLarge(estimate)) {
                "Widoczny obszar do pobrania: $size — przybliż mapę, żeby pobrać mniej."
            } else {
                "Widoczny obszar do pobrania: $size"
            },
        )
    }

    /**
     * Szacowanie nie może przewrócić aplikacji.
     *
     * To wyłącznie podpowiedź obok przycisku, więc gdyby MapLibre zwróciło granice, których
     * nie potrafimy zinterpretować, lepiej nie pokazać nic i pozwolić pobrać, niż zabić
     * ekran w trakcie jazdy.
     */
    private fun estimateOrNull(bounds: org.maplibre.android.geometry.LatLngBounds): OfflineRegionEstimate? =
        try {
            OfflineMapDownloader.estimate(bounds)
        } catch (_: Throwable) {
            null
        }

    private fun confirmLargeDownload(estimate: OfflineRegionEstimate, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Duży obszar")
            .setMessage(
                "Ten obszar to ${estimate.tileCount} kafelków, czyli ${OfflineRegionEstimator.describeSize(estimate)}. " +
                    "Przez sieć komórkową to zauważalny transfer i kilka minut czekania.\n\n" +
                    "Przybliż mapę, jeśli wystarczy ci mniejszy wycinek.",
            )
            .setPositiveButton("Pobierz mimo to") { _, _ -> onConfirm() }
            .setNegativeButton("Anuluj", null)
            .show()
    }


    // --- trasowanie ---

    /**
     * Czy urządzenie ma połączenie zdatne do wyznaczenia trasy.
     *
     * Pytamy o `NET_CAPABILITY_VALIDATED`, a nie o samo istnienie sieci — telefon podpięty
     * do hotspotu bez wyjścia na świat zgłasza połączenie, którym nic nie zrobimy.
     * Przy jakimkolwiek kłopocie z odczytem zakładamy, że sieć jest: lepiej spróbować
     * i pokazać błąd, niż odmówić trasowania komuś, kto ma zasięg.
     */
    private fun hasNetwork(): Boolean = try {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) {
        true
    }

    /**
     * Dokłada wskazany punkt do planu i przelicza trasę.
     *
     * Jeden gest buduje całą trasę: pierwszy punkt to cel, każdy następny przesuwa
     * dotychczasowy cel do punktów pośrednich. Kolejność wskazywania jest kolejnością jazdy.
     */
    private fun addStop(point: GeoPoint) {
        val extended = plan.withNextStop(point)
        if (extended == null) {
            Toast.makeText(
                this,
                "Limit ${RoutePlan.MAX_WAYPOINTS} punktów na trasę — usuń któryś, żeby dodać nowy.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        plan = extended
        syncPlanOnMap()
        requestRoute()
    }

    /** Cofa ostatnio wskazany punkt. */
    private fun undoStop() {
        if (plan.isEmpty) return
        plan = plan.withoutLastStop()

        if (plan.isComplete) {
            syncPlanOnMap()
            requestRoute()
        } else {
            // Bez celu nie ma czego wyznaczać, ale plan może jeszcze mieć punkt startowy —
            // dlatego czyścimy samą trasę, a nie cały plan.
            phase = NavigationPhase.PLANNING
            currentRoute = null
            routeProgress = null
            routeStatus = null
            mapPanel?.clearRoute()
            syncPlanOnMap()
            render()
        }
    }

    /**
     * Odrysowuje punkty planu na mapie.
     *
     * Wywoływane przy każdej zmianie planu, niezależnie od tego, czy da się już wyznaczyć
     * trasę. Wskazany punkt ma się pojawić od razu — także wtedy, gdy GPS jeszcze nie złapał
     * pozycji albo nie ma zasięgu i trasy nie będzie.
     */
    private fun syncPlanOnMap() {
        mapPanel?.showStops(plan.stops())
    }

    /** Przełącza start między bieżącą pozycją a punktem wskazanym na mapie. */
    private fun toggleStart() {
        if (plan.start != null) {
            plan = plan.startingFromCurrentPosition()
        } else {
            val here = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
            if (here == null) {
                Toast.makeText(this, "Czekam na pozycję — nie ma czego zapamiętać jako start.", Toast.LENGTH_SHORT).show()
                return
            }
            // Start „stąd" zamraża bieżącą pozycję, dzięki czemu trasa zostaje policzona
            // od miejsca, w którym stoi rowerzysta, nawet gdy ruszy przed jej wyznaczeniem.
            plan = plan.withStart(here)
        }

        syncPlanOnMap()
        if (plan.isComplete) requestRoute() else render()
    }

    /** Pozwala wybrać rower — ta sama para punktów daje inną trasę dla szosówki i „górala". */
    private fun pickBicycleProfile() {
        val profiles = BicycleProfile.entries
        AlertDialog.Builder(this)
            .setTitle("Rodzaj roweru")
            .setSingleChoiceItems(
                profiles.map { it.label }.toTypedArray(),
                profiles.indexOf(bicycleProfile),
            ) { dialog, which ->
                dialog.dismiss()
                if (profiles[which] != bicycleProfile) {
                    bicycleProfile = profiles[which]
                    if (plan.isComplete) requestRoute() else render()
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    /**
     * Zachowanie pola wyszukiwania wobec klawiatury.
     *
     * Na czas pisania chowamy mapę. Sama `adjustResize` z manifestu zmniejsza okno, ale
     * mapa dalej zabiera ponad połowę wysokości według wagi w układzie — bez tego na pole
     * i wyniki zostaje pasek kilku wierszy nad klawiaturą.
     */
    private fun setUpSearchField() {
        dashboard.searchField.setOnFocusChangeListener { view, hasFocus ->
            dashboard.setMapVisible(!hasFocus)
            if (hasFocus) {
                // Przewinięcie odkładamy na później: układ musi się najpierw przeliczyć
                // po schowaniu mapy, inaczej przewijalibyśmy do położenia sprzed zmiany.
                view.post {
                    view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false)
                }
            }
        }

        dashboard.searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchPlace(asStart = false)
                true
            } else {
                false
            }
        }
    }

    /** Zamyka klawiaturę i oddaje mapie jej miejsce. */
    private fun dismissKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        manager?.hideSoftInputFromWindow(dashboard.searchField.windowToken, 0)
        dashboard.searchField.clearFocus()
        dashboard.setMapVisible(true)
    }

    /**
     * Szuka miejsca po nazwie i dokłada je do planu.
     *
     * @param asStart czy znalezione miejsce ma zostać startem, czy kolejnym punktem trasy
     */
    private fun searchPlace(asStart: Boolean) {
        val query = dashboard.searchField.text?.toString().orEmpty().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Wpisz nazwę miejsca albo adres.", Toast.LENGTH_SHORT).show()
            return
        }
        if (searchInFlight) return

        if (!hasNetwork()) {
            Toast.makeText(this, "Brak sieci — wyszukiwanie miejsc wymaga połączenia.", Toast.LENGTH_LONG).show()
            return
        }

        searchInFlight = true
        setSearchEnabled(false)
        dismissKeyboard()

        // Bieżąca pozycja podbija trafność wyników: „Rynek" ma znaczyć rynek w okolicy,
        // a nie pierwszy z brzegu na świecie.
        val near = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }

        scope.launch {
            val result = geocoder.search(query, near)
            searchInFlight = false
            setSearchEnabled(true)

            when (result) {
                is GeocodeResult.Success -> showSearchResults(result.places, asStart)
                GeocodeResult.NoMatches ->
                    Toast.makeText(this@MainActivity, "Nic nie znalazłem dla: $query", Toast.LENGTH_LONG).show()
                GeocodeResult.Failure ->
                    Toast.makeText(this@MainActivity, "Wyszukiwanie nie powiodło się.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setSearchEnabled(enabled: Boolean) {
        dashboard.searchAddButton.isEnabled = enabled
        dashboard.searchStartButton.isEnabled = enabled
        dashboard.searchAddButton.text = if (enabled) "Szukaj punktu" else "Szukam…"
    }

    /**
     * Pokazuje wyniki do wyboru.
     *
     * Nazwy miejscowości się powtarzają, więc obok nazwy pokazujemy resztę adresu —
     * bez tego wybór między trzema Nowymi Wsiami byłby losowaniem.
     */
    private fun showSearchResults(places: List<Place>, asStart: Boolean) {
        val labels = places.map { place ->
            if (place.detail.isBlank()) place.name else "${place.name}\n${place.detail}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (asStart) "Wybierz start" else "Wybierz punkt trasy")
            .setItems(labels) { _, which -> applyFoundPlace(places[which], asStart) }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun applyFoundPlace(place: Place, asStart: Boolean) {
        if (asStart) {
            plan = plan.withStart(place.point)
        } else {
            val extended = plan.withNextStop(place.point)
            if (extended == null) {
                Toast.makeText(
                    this,
                    "Limit ${RoutePlan.MAX_WAYPOINTS} punktów na trasę — usuń któryś, żeby dodać nowy.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            plan = extended
        }

        dashboard.searchField.setText("")
        mapPanel?.focusOn(place.point.latitude, place.point.longitude)
        syncPlanOnMap()

        if (plan.isComplete) requestRoute() else render()
    }

    /**
     * Rozpoczyna albo kończy nawigację.
     *
     * Rozdzielenie układania trasy od jazdy jest po to, żeby aplikacja nie zaczynała mówić
     * i przeliczać trasy w chwili, gdy użytkownik dopiero ogląda warianty. Wyznaczona trasa
     * to jeszcze nie jazda.
     */
    private fun toggleNavigation() {
        if (phase.isGuiding) {
            stopNavigation(spoken = "Nawigacja zakończona.")
            return
        }

        if (currentRoute == null) {
            Toast.makeText(this, "Najpierw wyznacz trasę — wskaż cel na mapie albo wpisz nazwę.", Toast.LENGTH_LONG).show()
            return
        }

        // Bez zgody na powiadomienia usługa pierwszoplanowa wystartuje, ale użytkownik
        // nie zobaczy ani manewru, ani przycisku „Zakończ".
        requestNotificationPermissionIfNeeded()

        phase = NavigationPhase.NAVIGATING
        announcer.reset()
        offRouteDetector.reset()
        dismissKeyboard()
        mapPanel?.recenter()

        // Od tej chwili pozycje przychodzą z usługi, dzięki czemu nawigacja przeżywa
        // zgaszenie ekranu — bez niej `onStop` zdejmowało nasłuch i przejazd się kończył.
        stopLocationUpdates()
        NavigationService.start(this, gpsState.samplingIntervalSeconds)

        speak("Nawigacja rozpoczęta.")
        render()
    }

    /** Od Androida 13 powiadomienia wymagają zgody; bez niej przejazd byłby niewidoczny w tle. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun stopNavigation(spoken: String?) {
        if (!phase.isGuiding) return
        phase = NavigationPhase.PLANNING
        announcer.reset()
        offRouteDetector.reset()
        NavigationService.stop(this)
        // Aktywność przejmuje nasłuch z powrotem, o ile w ogóle jest widoczna.
        applyLocationUpdates()
        spoken?.let { speak(it) }
        render()
    }

    /** Wyznacza trasę dla bieżącego planu. */
    private fun requestRoute() {
        val here = lastLocation?.let { GeoPoint(it.latitude, it.longitude) }
        val waypoints = plan.waypoints(here)
        if (waypoints == null) {
            if (plan.isComplete) {
                Toast.makeText(this, "Czekam na pozycję — bez niej nie ma skąd wyznaczyć trasy.", Toast.LENGTH_SHORT).show()
            }
            render()
            return
        }
        if (routeRequestInFlight) return

        announcer.reset()
        offRouteDetector.reset()

        // ADR-0007: trasowanie jest sieciowe. Bez zasięgu mówimy o tym wprost, zamiast
        // kazać użytkownikowi czekać na timeout i domyślać się z komunikatu o błędzie.
        if (!hasNetwork()) {
            currentRoute = null
            routeStatus = "Brak sieci — wyznaczanie trasy wymaga połączenia. " +
                "Pobrana mapa i pozycja działają dalej."
            mapPanel?.showRoute(emptyList())
            render()
            return
        }

        routeRequestInFlight = true
        routeStatus = "Wyznaczam trasę…"
        mapPanel?.showRoute(emptyList())
        render()

        routeEngine.conditions = conditions
        routeEngine.profile = bicycleProfile

        scope.launch {
            val result = routeEngine.route(RouteRequest(waypoints))
            routeRequestInFlight = false

            when (result) {
                is RouteResult.Success -> {
                    currentRoute = result.route
                    routeStatus = null
                    mapPanel?.showRoute(result.route.geometry)
                }

                is RouteResult.Failure -> {
                    currentRoute = null
                    routeStatus = when (result.reason) {
                        RouteFailure.NO_ROUTE_FOUND ->
                            if (plan.via.isEmpty()) {
                                "Nie znalazłem trasy rowerowej do tego punktu."
                            } else {
                                "Nie znalazłem trasy rowerowej przez wszystkie wskazane punkty."
                            }
                        RouteFailure.MISSING_MAP_DATA -> "Brak danych mapowych dla tego obszaru."
                        RouteFailure.ENGINE_ERROR -> "Trasowanie niedostępne — sprawdź połączenie."
                    }
                }
            }
            render()
        }
    }

    private fun clearRoute() {
        plan = plan.cleared()
        // Nie ma trasy, nie ma czym nawigować — inaczej zostalibyśmy w stanie jazdy
        // bez niczego, po czym można by jechać.
        phase = NavigationPhase.PLANNING
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

        dashboard.set(RideDashboard.KEY_ROUTE_PLAN, plan.describe())
        dashboard.set(RideDashboard.KEY_BICYCLE_PROFILE, "Rower: ${bicycleProfile.label}")
        dashboard.profileButton.text = bicycleProfile.label
        dashboard.startHereButton.text = if (plan.start != null) "Start: wybrany" else "Start: stąd"
        dashboard.undoStopButton.isEnabled = !plan.isEmpty
        dashboard.clearRouteButton.isEnabled = !plan.isEmpty
        dashboard.navigationButton.isEnabled = route != null || phase.isGuiding
        dashboard.navigationButton.text = when {
            phase.isGuiding -> "Zakończ nawigację"
            phase == NavigationPhase.ARRIVED -> "Jesteś na miejscu — nawiguj ponownie"
            else -> "Rozpocznij nawigację"
        }

        if (route == null) {
            dashboard.set(
                RideDashboard.KEY_ROUTE_SUMMARY,
                status ?: "Przytrzymaj palec na mapie, żeby dodać punkt trasy.",
            )
            dashboard.set(RideDashboard.KEY_NEXT_MANEUVER, null)
            dashboard.set(RideDashboard.KEY_ROUTE_REMAINING, null)
            return
        }

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

        renderRouteProgress(route, progress)
        updateNavigationNotification(route, progress)
    }

    /**
     * Przenosi najbliższy manewr i dystans do celu do powiadomienia.
     *
     * To jedyny widok trasy, gdy telefon leży w kieszeni — bez tego usługa pierwszoplanowa
     * pokazywałaby, że coś działa, nie mówiąc co.
     */
    private fun updateNavigationNotification(route: Route, progress: RouteProgress?) {
        if (!phase.isGuiding) return

        val title = progress?.nextManeuver?.let { maneuver ->
            val distance = progress.distanceToNextManeuverMeters
                ?.let { " za ${formatDistance(it)}" }
                .orEmpty()
            "${maneuver.instruction}$distance"
        } ?: "Jedź dalej"

        val remaining = progress?.remainingDistanceMeters ?: route.distanceMeters
        val eta = progress
            ?.let { RouteEta.estimate(route, it, currentSpeedMetersPerSecond()).remainingDurationSeconds }
            ?.let { ", ok. ${formatDuration(it)}" }
            .orEmpty()

        NavigationNotification.update(this, title, "Do celu ${formatDistance(remaining)}$eta")
    }

    /**
     * Dane postępu: ile trasy za nami i kiedy będziemy na miejscu.
     *
     * Czas dojazdu liczony jest z tempa, którym rowerzysta faktycznie jedzie, a na postoju
     * z planu silnika. Podstawę podajemy wprost, bo „za 40 minut" znaczy co innego, gdy
     * wynika z pomiaru, a co innego, gdy z założeń profilu rowerowego.
     */
    private fun renderRouteProgress(route: Route, progress: RouteProgress?) {
        if (progress == null) {
            dashboard.set(RideDashboard.KEY_ROUTE_PROGRESS, null)
            dashboard.set(RideDashboard.KEY_ROUTE_ETA, null)
            return
        }

        val percent = (progress.completedFraction * 100).roundToInt().coerceIn(0, 100)
        dashboard.set(
            RideDashboard.KEY_ROUTE_PROGRESS,
            "Przejechane: ${formatDistance(progress.traveledDistanceMeters)} " +
                "z ${formatDistance(progress.totalDistanceMeters)} ($percent%)",
        )

        val estimate = RouteEta.estimate(route, progress, currentSpeedMetersPerSecond())
        val remaining = estimate.remainingDurationSeconds
        if (remaining == null) {
            dashboard.set(RideDashboard.KEY_ROUTE_ETA, null)
            return
        }

        val basis = when (estimate.basis) {
            EtaBasis.MEASURED_SPEED -> "wg tempa"
            EtaBasis.ENGINE_ESTIMATE -> "wg planu"
        }
        dashboard.set(
            RideDashboard.KEY_ROUTE_ETA,
            "Zostało ${formatDuration(remaining)} — na miejscu ok. ${formatClock(remaining)} ($basis)",
        )
    }

    /** Prędkość nadająca się na podstawę oszacowania; `null`, gdy odbiornik jej nie podaje. */
    private fun currentSpeedMetersPerSecond(): Double? =
        lastLocation?.takeIf { it.hasSpeed() }?.speed?.toDouble()

    /** Godzina oddalona o zadaną liczbę sekund, w formacie zegarowym. */
    private fun formatClock(inSeconds: Long): String {
        val arrival = java.util.Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() + inSeconds * 1_000
        }
        return String.format(
            Locale.getDefault(),
            "%02d:%02d",
            arrival.get(java.util.Calendar.HOUR_OF_DAY),
            arrival.get(java.util.Calendar.MINUTE),
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
                voiceUnavailable = true
                handler.post { updateVoiceButton() }
            }
        }
    }

    private fun toggleVoice() {
        voiceEnabled = !voiceEnabled
        if (!voiceEnabled) textToSpeech?.stop()

        // Zapamiętane, bo wyciszenie to decyzja o tym, jak się jeździ, a nie ustawienie
        // na jeden przejazd. Włączanie go od nowa po każdym uruchomieniu byłoby wrogie.
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(KEY_VOICE_ENABLED, voiceEnabled)
            .apply()

        updateVoiceButton()
    }

    private fun restoreVoicePreference() {
        voiceEnabled = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_VOICE_ENABLED, true)
        updateVoiceButton()
    }

    private fun updateVoiceButton() {
        // Komunikat o braku polskiego głosu wygrywa ze stanem przełącznika: bez tego
        // użytkownik widziałby „Dźwięk: wł." i nie słyszałby niczego.
        dashboard.voiceButton.text = when {
            voiceUnavailable -> "Brak polskiego głosu"
            voiceEnabled -> "Dźwięk: wł."
            else -> "Dźwięk: wył."
        }
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
        // Poza jazdą milczymy i nie ruszamy trasy — użytkownik może ją dopiero układać.
        if (!phase.isGuiding) return

        if (ArrivalDetector.hasArrived(progress)) {
            phase = NavigationPhase.ARRIVED
            announcer.reset()
            offRouteDetector.reset()
            NavigationService.stop(this)
            applyLocationUpdates()
            speak("Dojechałeś do celu.")
            render()
            return
        }

        announcer.announce(progress)?.let { speak(it) }

        if (offRouteDetector.update(progress)) {
            if (!plan.isComplete) return
            speak("Zjechałeś z trasy. Wyznaczam nową.")
            requestRoute()
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

        // W trakcie jazdy subskrypcję trzyma usługa pierwszoplanowa, nie aktywność.
        // Dublowanie nasłuchu podwajałoby pracę odbiornika, a przy wygaszonym ekranie
        // subskrypcja aktywności i tak byłaby przez system ograniczana.
        if (phase.isGuiding) {
            stopLocationUpdates()
            NavigationService.updateInterval(interval)
            return
        }

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
        consumeReachedStops(GeoPoint(location.latitude, location.longitude))
        requestWeather(force = false)
    }

    /**
     * Zdejmuje z planu punkty pośrednie, które rowerzysta ma już za sobą.
     *
     * Bez tego przeliczenie trasy po zjechaniu z niej zawracałoby do minietych punktów.
     * Samej trasy tu nie przeliczamy — ona już przez ten punkt prowadzi, więc nie ma czego
     * poprawiać; chodzi wyłącznie o to, żeby następne wyznaczenie ruszyło do przodu.
     */
    private fun consumeReachedStops(position: GeoPoint) {
        val remaining = plan.consumingReachedVia(position)
        if (remaining == plan) return

        plan = remaining
        syncPlanOnMap()
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
            // Odległość do manewru podajemy wyłącznie w trakcie jazdy. Wcześniej sama
            // wyznaczona trasa wpychała odbiornik w CRITICAL, czyli w odpytywanie co
            // sekundę, choć użytkownik dopiero układał trasę i nigdzie nie jechał —
            // dokładne przeciwieństwo tego, po co jest maszyna stanów z sekcji 7.
            distanceToManeuverMeters = routeProgress
                ?.takeIf { phase.isGuiding }
                ?.distanceToNextManeuverMeters,
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
        const val REQUEST_NOTIFICATIONS = 2

        /** Ustawienia przeżywające zamknięcie aplikacji. */
        const val PREFERENCES = "reactivebike"
        const val KEY_VOICE_ENABLED = "voiceEnabled"

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
