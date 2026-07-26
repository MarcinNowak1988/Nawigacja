package pl.reactivebike.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Stan pobierania map offline, w postaci gotowej do pokazania na pulpicie. */
data class OfflineMapsState(
    val summary: String,
    val detail: String? = null,
    val busy: Boolean = false,
)

/**
 * Pobieranie kafelków do użytku bez zasięgu.
 *
 * Korzysta z `OfflineManager` MapLibre, który zapisuje kafelki, czcionki i sprite'y stylu
 * we własnej bazie na urządzeniu. Nie są to pliki `.mbtiles` z sekcji 3 specyfikacji —
 * powody zmiany opisuje ADR-0006.
 *
 * Postęp odczytujemy **odpytywaniem** (`getStatus`), a nie przez obserwatora regionu.
 * Wynik jest ten sam, a interfejs jest węższy, co ma znaczenie przy kodzie pisanym bez
 * możliwości lokalnej kompilacji.
 */
class OfflineMapDownloader(context: Context) {

    private val manager: OfflineManager = OfflineManager.getInstance(context)
    private val handler = Handler(Looper.getMainLooper())

    private var listener: ((OfflineMapsState) -> Unit)? = null
    private var activeRegion: OfflineRegion? = null
    private var polling = false

    fun observe(listener: (OfflineMapsState) -> Unit) {
        this.listener = listener
    }

    /** Odczytuje, co jest już pobrane — wywoływane przy starcie i po każdej zmianie. */
    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty()
                if (regions.isEmpty()) {
                    emit(OfflineMapsState("Brak pobranych map — mapa wymaga zasięgu."))
                    return
                }
                regions.first().getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        emit(describe(status, regions.size))
                    }

                    override fun onError(error: String?) {
                        emit(OfflineMapsState("Pobrane regiony: ${regions.size}"))
                    }
                })
            }

            override fun onError(error: String?) {
                emit(OfflineMapsState("Nie udało się odczytać pobranych map.", error))
            }
        })
    }

    /**
     * Pobiera widoczny fragment mapy.
     *
     * Zakres powiększeń celowo kończy się na [MAX_ZOOM]: każdy kolejny poziom to
     * czterokrotnie więcej kafelków, a do jazdy rowerem szczegółowość z tego poziomu
     * w zupełności wystarcza.
     */
    fun download(styleUrl: String, bounds: LatLngBounds) {
        emit(OfflineMapsState("Przygotowuję pobieranie…", busy = true))

        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            MIN_ZOOM,
            MAX_ZOOM,
            PIXEL_RATIO,
        )

        manager.createOfflineRegion(
            definition,
            REGION_METADATA.toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion?) {
                    val region = offlineRegion ?: return
                    activeRegion = region
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    startPolling(region)
                }

                override fun onError(error: String?) {
                    emit(OfflineMapsState("Nie udało się rozpocząć pobierania.", error))
                }
            },
        )
    }

    fun deleteAll() {
        emit(OfflineMapsState("Usuwam pobrane mapy…", busy = true))
        stopPolling()

        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty()
                if (regions.isEmpty()) {
                    refresh()
                    return
                }
                var remaining = regions.size
                regions.forEach { region ->
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            remaining -= 1
                            if (remaining <= 0) refresh()
                        }

                        override fun onError(error: String?) {
                            remaining -= 1
                            if (remaining <= 0) refresh()
                        }
                    })
                }
            }

            override fun onError(error: String?) {
                emit(OfflineMapsState("Nie udało się usunąć map.", error))
            }
        })
    }

    fun stop() {
        stopPolling()
        activeRegion?.setDownloadState(OfflineRegion.STATE_INACTIVE)
    }

    // --- postęp ---

    private fun startPolling(region: OfflineRegion) {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        emit(describe(status, regions = 1))
                        if (status != null && status.isComplete) {
                            stopPolling()
                        }
                    }

                    override fun onError(error: String?) {
                        emit(OfflineMapsState("Błąd pobierania.", error))
                        stopPolling()
                    }
                })
                if (polling) handler.postDelayed(this, POLL_MILLIS)
            }
        })
    }

    private fun stopPolling() {
        polling = false
    }

    private fun describe(status: OfflineRegionStatus?, regions: Int): OfflineMapsState {
        if (status == null) return OfflineMapsState("Pobrane regiony: $regions")

        val megabytes = status.completedResourceSize / 1_048_576.0
        val size = "${(megabytes * 10).toLong() / 10.0} MB"

        return if (status.isComplete) {
            OfflineMapsState(
                summary = "Mapa dostępna offline ($size)",
                detail = "Kafelki: ${status.completedResourceCount}. Ten obszar wyświetli się bez zasięgu.",
            )
        } else {
            val required = status.requiredResourceCount
            val percent = if (required > 0) {
                (status.completedResourceCount * 100 / required).coerceIn(0, 100)
            } else {
                0
            }
            OfflineMapsState(
                summary = "Pobieram mapę… $percent%",
                detail = "${status.completedResourceCount} z ~$required plików ($size)",
                busy = true,
            )
        }
    }

    private fun emit(state: OfflineMapsState) {
        handler.post { listener?.invoke(state) }
    }

    private companion object {
        const val MIN_ZOOM = 10.0

        /** Każdy kolejny poziom to czterokrotnie więcej kafelków — 15 wystarcza do jazdy. */
        const val MAX_ZOOM = 15.0

        const val PIXEL_RATIO = 1.0f
        const val POLL_MILLIS = 1_000L
        const val REGION_METADATA = "reactivebike-region"
    }
}
