package pl.reactivebike.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder

/**
 * Utrzymuje jadącą nawigację przy życiu, gdy ekran jest wygaszony.
 *
 * Bez niej aplikacja przestawała nawigować w chwili zgaszenia ekranu: `onStop` aktywności
 * zdejmowało nasłuch GPS, więc nie było pozycji, zapowiedzi ani przeliczania trasy. Rower
 * to jednak dokładnie ten przypadek, w którym telefon leży w kieszeni, a prowadzi głos.
 *
 * **To usługa trzyma subskrypcję lokalizacji, nie aktywność.** Nie chodzi wyłącznie
 * o utrzymanie procesu: od Androida 10 dostęp do lokalizacji w tle jest ograniczany, a wyjątek
 * daje właśnie działająca usługa pierwszoplanowa typu `location`. Zostawienie subskrypcji
 * w aktywności działałoby przez przypadek i tylko na części urządzeń.
 *
 * Sama logika prowadzenia — zapowiedzi, wykrywanie zjechania z trasy, dojazd — zostaje
 * w [MainActivity], która nasłuchuje przekazywanych stąd pozycji.
 */
class NavigationService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var registeredIntervalSeconds: Int? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        NavigationNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == NavigationNotification.ACTION_STOP) {
            // Przycisk „Zakończ" w powiadomieniu: informujemy aplikację i gasimy usługę.
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        val interval = intent?.getIntExtra(EXTRA_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
            ?: DEFAULT_INTERVAL_SECONDS
        applyInterval(interval)

        isRunning = true

        // START_NOT_STICKY: po zabiciu procesu nie wskrzeszamy usługi z pustym stanem.
        // Nawigacja bez trasy i bez planu udawałaby, że prowadzi, nie prowadząc.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = NavigationNotification.build(
            this,
            title = "Nawigacja w toku",
            text = "Czekam na pozycję…",
        )

        // Od API 34 typ usługi jest obowiązkowy przy starcie, inaczej system ją odrzuca.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NavigationNotification.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NavigationNotification.ID, notification)
        }
    }

    /**
     * Wpina częstotliwość wynikającą z maszyny stanów GPS (sekcja 7).
     *
     * Interwał `null` znaczy postój i całkowite zdjęcie nasłuchu — usługa zostaje, żeby
     * nawigacja mogła ruszyć dalej bez pytania o uprawnienia, ale odbiornik milczy.
     */
    fun applyInterval(intervalSeconds: Int?) {
        if (!hasLocationPermission()) return
        if (intervalSeconds == null) {
            removeUpdates()
            return
        }
        if (registeredIntervalSeconds == intervalSeconds) return

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return

        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(provider, intervalSeconds * 1_000L, 0f, this)
            registeredIntervalSeconds = intervalSeconds
        } catch (_: SecurityException) {
            registeredIntervalSeconds = null
        }
    }

    private fun removeUpdates() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
            // i tak przestajemy nasłuchiwać
        }
        registeredIntervalSeconds = null
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onLocationChanged(location: Location) {
        onLocation?.invoke(location)
    }

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        removeUpdates()
        isRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        private const val EXTRA_INTERVAL_SECONDS = "intervalSeconds"

        /** Interwał na start — sekunda, dopóki maszyna stanów nie powie inaczej. */
        private const val DEFAULT_INTERVAL_SECONDS = 1

        /** Czy nawigacja działa w tle. Czytane też przy odświeżaniu powiadomienia. */
        @Volatile
        var isRunning = false
            private set

        private var instance: NavigationService? = null

        /** Kolejne pozycje z odbiornika — konsumowane przez [MainActivity]. */
        var onLocation: ((Location) -> Unit)? = null

        /** Naciśnięcie „Zakończ" w powiadomieniu. */
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, intervalSeconds: Int?) {
            val intent = Intent(context, NavigationService::class.java)
                .putExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds ?: DEFAULT_INTERVAL_SECONDS)
            context.startForegroundService(intent)
        }

        /**
         * Zmienia częstotliwość odpytywania już działającej usługi.
         *
         * Sięgamy po instancję zamiast wysyłać kolejny zamiar, bo `startForegroundService`
         * przy każdym wywołaniu wymaga ponownego `startForeground` w ciągu pięciu sekund —
         * a maszyna stanów zmienia interwał na tyle często, że robiłoby to niepotrzebny hałas.
         */
        fun updateInterval(intervalSeconds: Int?) {
            instance?.applyInterval(intervalSeconds)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
