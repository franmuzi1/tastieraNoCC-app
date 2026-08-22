package helium314.keyboard.cipher

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R

/**
 * L'ultima spiaggia: un avviso da toccare, quando il messaggio copiato e' stato
 * riconosciuto ma il sistema non ci lascia aprire la schermata.
 *
 * ## Perche' esiste, perche' era stato tolto, e perche' e' tornato
 *
 * Copiando un blob la tastiera prova ad aprire [DecryptActivity]. Da Android 10
 * un'app senza finestre visibili non dovrebbe poterlo fare, ma **misurato su
 * Android 14 (emulatore AOSP) si apre lo stesso**: il sistema tratta l'IME
 * predefinito come un caso a parte. Su quella misura l'avviso era stato tolto,
 * perche' costava `POST_NOTIFICATIONS` per un ramo che sembrava non scattare
 * mai.
 *
 * Era una misura su **un dispositivo solo**, e su un telefono vero e' andata
 * diversamente: la riga diagnostica della notifica keep-alive diceva "messaggio
 * cifrato riconosciuto" e non si apriva niente. Cioe' l'ascoltatore scatta, il
 * blob si riconosce, e l'avvio viene rifiutato — in silenzio, perche'
 * `startActivity` non segnala il rifiuto.
 *
 * Quindi l'avviso torna. Il permesso ormai c'e' comunque, dichiarato per il
 * servizio che tiene viva la tastiera: non costa piu' niente di nuovo.
 *
 * ## Cosa non finisce qui dentro
 *
 * Titolo e testo sono fissi: niente blob, niente decifrato. Un avviso lo legge
 * chiunque guardi lo schermo, e la barra di stato non ha nessuna delle
 * protezioni per cui [DecryptActivity] gira sotto `FLAG_SECURE`. Per lo stesso
 * motivo `VISIBILITY_SECRET`: a telefono bloccato non compare.
 *
 * Il blob viaggia nel `PendingIntent` perche' e' cifrato ed e' gia' negli
 * appunti di sistema. Resta pero' in memoria a `system_server` finche' quel
 * `PendingIntent` vive, ed e' il motivo per cui [dismiss] lo annulla davvero
 * invece di limitarsi a togliere l'avviso.
 */
internal object CipherNotification {

    private const val CHANNEL = "cipher_clipboard"
    private const val ID = 4711
    private const val REQUEST = 4711

    /** Dopo cinque minuti si toglie da solo: il blob e' comunque negli appunti. */
    private const val TIMEOUT_MS = 5L * 60L * 1000L

    /**
     * L'ultimo `PendingIntent` postato, per annullarlo davvero.
     *
     * `cancel()` sull'avviso lo toglie dalla barra ma lascia vivo il
     * `PendingIntent` — e il blob nei suoi extra — dentro `system_server`.
     */
    @Volatile
    private var pending: PendingIntent? = null

    // Il permesso lo controlla [allowed], una riga sotto. Lint non lo segue
    // fuori dal metodo e blocca la build di release: e' l'unica ragione della
    // soppressione.
    @SuppressLint("MissingPermission")
    fun offer(context: Context, blob: String) {
        if (!allowed(context)) return
        ensureChannel(context)

        val intent = Intent(context, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, blob)
            // Niente gettone di CipherHandoff: qui non c'e' nessuna app in cui
            // si stia scrivendo, e un gettone emesso ora resterebbe pendente
            // fino al tocco. DecryptActivity ricade su "nessuna app", che e' il
            // verso giusto in cui fallire: chiede invece di indovinare.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val tocco = PendingIntent.getActivity(context, REQUEST, intent, flags)
        pending = tocco

        val avviso = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cipher_decrypt)
            .setContentTitle(context.getString(R.string.cipher_notif_title))
            .setContentText(context.getString(R.string.cipher_notif_text))
            .setContentIntent(tocco)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // Deve comparire davanti — altrimenti "ho copiato e non e' successo
            // niente" resta vero — ma non deve suonare: e' una comodita', non
            // un allarme.
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, avviso) }
    }

    /**
     * Via l'avviso e via il blob che si portava dietro. Chiamata da
     * [DecryptActivity] appena il messaggio e' a schermo: a quel punto ha finito
     * il suo lavoro.
     */
    fun dismiss(context: Context) {
        pending?.cancel()
        pending = null
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val canale = NotificationChannel(
            CHANNEL,
            context.getString(R.string.cipher_notif_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.cipher_notif_channel_desc)
            setSound(null, null)
            enableVibration(false)
            // Il badge sopravviverebbe all'avviso: l'app resterebbe "con
            // qualcosa da leggere" a messaggio gia' letto.
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        }
        runCatching { manager.createNotificationChannel(canale) }
    }
}
