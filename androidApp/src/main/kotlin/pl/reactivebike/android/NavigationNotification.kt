package pl.reactivebike.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * Powiadomienie towarzyszące jadącej nawigacji.
 *
 * Wydzielone z usługi, żeby [MainActivity] mogła odświeżać jego treść bez wiązania się
 * z usługą: powiadomienie należy do aplikacji, więc ponowne `notify` z tym samym
 * identyfikatorem podmienia treść niezależnie od tego, który komponent je wysyła.
 *
 * Treść jest tu istotna, a nie ozdobna. Telefon leży w kieszeni albo na kierownicy
 * z wygaszonym ekranem, więc najbliższy manewr i dystans do celu w powiadomieniu to często
 * jedyny sposób, żeby sprawdzić trasę bez odblokowywania urządzenia.
 */
object NavigationNotification {

    const val ID = 1001

    private const val CHANNEL_ID = "reactivebike-navigation"

    /** Akcja przycisku „Zakończ" — kończy nawigację bez wracania do aplikacji. */
    const val ACTION_STOP = "pl.reactivebike.action.STOP_NAVIGATION"

    /**
     * Zakłada kanał powiadomień.
     *
     * Ważność celowo niska: powiadomienie ma być widoczne przez cały przejazd, ale nie może
     * dzwonić ani wibrować przy każdej aktualizacji dystansu.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nawigacja", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Trwający przejazd — pozycja i najbliższy manewr."
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    fun build(context: Context, title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            // Od API 31 flaga zmienności jest obowiązkowa; ten zamiar nie niesie danych,
            // więc niezmienny jest właściwy.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(
                // Ikona jawna, a nie null: akcja bez ikony bywa odrzucana w czasie działania
                // na części wersji Androida, a tego nie sprawdzę bez urządzenia.
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Zakończ",
                    stop,
                ).build(),
            )
            .build()
    }

    /** Podmienia treść trwającego powiadomienia; bez trwającej nawigacji nie robi nic. */
    fun update(context: Context, title: String, text: String?) {
        if (!NavigationService.isRunning) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(ID, build(context, title, text))
    }
}
