package helium314.keyboard.cipher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.IBinder
import androidx.core.app.NotificationCompat
import helium314.keyboard.latin.R

/**
 * Un servizio in primo piano il cui unico scopo e' **esistere**.
 *
 * ## Perche' una cosa del genere
 *
 * Il riconoscimento di un blob negli appunti vive in
 * `ClipboardHistoryManager`, cioe' nel processo della tastiera. Se quel
 * processo non c'e', copiare non produce niente — e non c'e' modo di
 * accorgersene: **Android non ha nessun evento per gli appunti**. Nessun
 * broadcast dichiarabile nel manifest, nessun observer, nessun permesso che lo
 * sblocchi. L'unico gancio e' `OnPrimaryClipChangedListener`, che funziona solo
 * mentre il processo e' gia' vivo e registrato. Non e' una restrizione da
 * aggirare: il gancio per essere risvegliati non esiste.
 *
 * A fermare la tastiera e' tipicamente il gestore batteria del produttore, che
 * fa un force-stop vero — misurato: il pacchetto passa a `stopped=true` e il
 * sistema ripiega perfino su un'altra tastiera predefinita. Un servizio in
 * primo piano e' la sola cosa che quei gestori di solito rispettano, perche'
 * ha una notifica visibile che l'utente puo' vedere.
 *
 * ## Cosa NON fa
 *
 * Non gira in loop, non sveglia la CPU, non tiene wakelock, non legge gli
 * appunti e non parla con nessuno. `onStartCommand` mette la notifica e
 * ritorna. Il lavoro utile lo fa il fatto stesso che il processo resti in
 * piedi, e quindi che l'ascoltatore registrato dall'IME resti registrato.
 *
 * ## Il prezzo, dichiarato
 *
 * Una notifica permanente in barra di stato e il permesso di mostrarla. Per
 * questo e' **spento di default** e la voce nelle impostazioni dice di provare
 * prima a togliere la restrizione batteria, che ottiene lo stesso risultato
 * senza costare niente.
 */
class CipherKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Guarda quando cambia la tastiera predefinita.
     *
     * Senza, la riga di stato della notifica sarebbe cieca proprio nel caso
     * peggiore: perdendo lo stato di predefinita, `LatinIME.onCreate` non gira
     * piu' e l'ascoltatore degli appunti non scatta piu', quindi niente
     * ripasserebbe di qui ad aggiornare il testo — che resterebbe a dire "la
     * tastiera resta viva" per sempre.
     *
     * Un observer e non un controllo periodico: e' un evento, non uno stato da
     * sondare, e questo servizio deve continuare a non fare lavoro.
     */
    private val osservatore = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            runCatching { mostraNotifica() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                osservatore,
            )
        }
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(osservatore) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // L'utente puo' aver spento l'interruttore mentre il sistema ci
        // riavviava: in quel caso ci si toglie di mezzo invece di ricomparire.
        if (!CipherSettings.isKeepAlive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { mostraNotifica() }.onFailure { stopSelf() }
        // START_STICKY: se il sistema ci uccide per memoria vogliamo tornare,
        // che e' esattamente il punto del servizio.
        return START_STICKY
    }

    private fun mostraNotifica() {
        creaCanale()
        // Il testo dice lo STATO, non una rassicurazione fissa.
        //
        // Tenere vivo il processo non basta a guardare gli appunti: quel
        // permesso ce l'abbiamo in quanto **pacchetto tastiera predefinita**,
        // non in quanto processo in piedi. E la predefinita si perde da sola —
        // basta un force-stop del gestore batteria e il sistema ripiega su
        // un'altra tastiera. Il servizio pero' riparte lo stesso
        // (`START_STICKY`), quindi senza questo controllo la notifica direbbe
        // "la tastiera resta viva" mentre non guarda piu' niente: una
        // rassicurazione falsa, peggio del silenzio.
        val stato = if (sonoLaPredefinita()) {
            getString(R.string.cipher_keep_alive_notice_text)
        } else {
            getString(R.string.cipher_keep_alive_not_default)
        }
        val copia = when (ultimaCopiaEraNostra) {
            null -> getString(R.string.cipher_keep_alive_none)
            true -> getString(R.string.cipher_keep_alive_blob)
            false -> getString(R.string.cipher_keep_alive_other)
        }
        val testo = "$stato\n$copia"
        val notifica = NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_cipher_encrypt)
            .setContentTitle(getString(R.string.cipher_keep_alive_notice))
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            // Non c'e' niente di riservato da nascondere: la notifica non dice
            // nulla oltre alla propria esistenza. Resta comunque fuori dalla
            // schermata di blocco, per non aggiungere ingombro dove non serve.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notifica, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notifica)
        }
    }

    /**
     * Siamo noi la tastiera predefinita in questo momento?
     *
     * Si legge dalle impostazioni sicure, che sono leggibili senza permessi. Il
     * confronto e' sul package e non sul componente: quello che conta per
     * l'accesso agli appunti e' il pacchetto.
     */
    private fun sonoLaPredefinita(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
    }.getOrDefault(false)

    private fun creaCanale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CANALE) != null) return
        val canale = NotificationChannel(
            CANALE,
            getString(R.string.cipher_keep_alive_channel),
            // MIN: la notifica deve esserci, non farsi notare. E' un effetto
            // collaterale del meccanismo, non un messaggio per l'utente.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        runCatching { manager.createNotificationChannel(canale) }
    }

    companion object {
        private const val CANALE = "cipher_keep_alive"

        /**
         * Esito dell'ultima copia vista, per la riga di stato della notifica.
         * `null` finche' l'ascoltatore non ha visto passare niente — ed e'
         * proprio quel `null` che distingue "non scatta" da "scatta e non
         * riconosce", due guasti che da fuori si somigliano.
         */
        @Volatile
        private var ultimaCopiaEraNostra: Boolean? = null

        /** Chiamata dal percorso degli appunti a ogni testo che passa. */
        fun segnalaCopia(context: Context, eraNostra: Boolean) {
            ultimaCopiaEraNostra = eraNostra
            aggiorna(context)
        }
        private const val ID = 4712

        /**
         * Allinea il servizio alla preferenza. Si puo' chiamare quante volte si
         * vuole: avviare un servizio gia' avviato ripassa da `onStartCommand`
         * e non duplica niente.
         *
         * Silenziosa se il sistema rifiuta l'avvio: da Android 12 un servizio
         * in primo piano non si puo' far partire da background, e questa
         * chiamata arriva anche da posti che background lo sono. Chi ha acceso
         * l'interruttore lo fa da una schermata visibile, che e' il caso che
         * conta.
         */
        fun aggiorna(context: Context) {
            val intent = Intent(context.applicationContext, CipherKeepAlive::class.java)
            if (CipherSettings.isKeepAlive(context)) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.applicationContext.startForegroundService(intent)
                    } else {
                        context.applicationContext.startService(intent)
                    }
                }
            } else {
                runCatching { context.applicationContext.stopService(intent) }
            }
        }
    }
}
