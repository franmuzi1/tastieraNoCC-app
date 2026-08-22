package helium314.keyboard.cipher

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Il messaggio decifrato **dentro la finestra della tastiera**.
 *
 * ## Perche' non basta l'Activity
 *
 * `DecryptActivity` resta la via per tutto cio' che arriva da fuori — barra di
 * selezione, condivisione, allegati — ma non sempre si puo' aprire. Su alcune
 * ROM il sistema rifiuta l'avvio di un'Activity da un'app senza finestre
 * visibili, e lo fa **in silenzio**: `startActivity` torna come se fosse andato
 * bene. Riscontrato su un telefono vero: la riga diagnostica diceva "messaggio
 * cifrato riconosciuto" e non compariva niente.
 *
 * La finestra della tastiera invece e' gia' nostra e non e' soggetta a quella
 * restrizione. Serve pero' una sessione di input attiva: senza un campo di
 * testo col fuoco un IME non ha una finestra da mostrare, ed e' il limite di
 * questa via.
 *
 * ## Perche' e' anche piu' giusto cosi'
 *
 * Il chiaro non esce dalla tastiera nemmeno per finire in una finestra
 * separata. Niente task, niente schermata Recenti, niente intent, nessun
 * chiamante da autenticare — tutta macchineria che in [DecryptActivity] esiste
 * *perche'* e' un'Activity esportata.
 *
 * ## FLAG_SECURE
 *
 * La finestra dell'IME non ce l'ha, e finche' mostra tasti va benissimo. Mentre
 * mostra un messaggio decifrato no: si mette all'apertura e si toglie alla
 * chiusura. Toglierlo conta quanto metterlo — lasciarlo acceso significherebbe
 * una tastiera che impedisce gli screenshot a tutte le app, per sempre, senza
 * che nulla lo spieghi.
 *
 * ## Cosa NON fa
 *
 * Solo i messaggi di testo andati a buon fine. Ogni altro esito — non e' nostro,
 * versione futura, non decifrabile, identita' card, allegato — resta a
 * [DecryptActivity], che li gestisce tutti e sei in un posto solo. Duplicarli
 * qui vorrebbe dire due implementazioni destinate a divergere al primo esito
 * aggiunto. E a quel punto l'Activity si apre comunque, perche' la finestra
 * della tastiera nel frattempo e' visibile.
 */
object CipherPanel {

    private var pannello: View? = null
    private var chi: TextView? = null
    private var quando: TextView? = null
    private var testo: TextView? = null
    private var servizio: InputMethodService? = null

    /**
     * Aggancia le viste. Chiamata a ogni `setInputView`, come
     * [CipherCompose.bind]: la gerarchia si ricostruisce a ogni cambio di tema o
     * rotazione, e un riferimento tenuto oltre punterebbe a viste morte.
     */
    fun bind(ime: InputMethodService, view: View) {
        servizio = ime
        val trovato = view.findViewById<View>(R.id.cipher_message_panel)
        pannello = trovato ?: return
        chi = trovato.findViewById(R.id.cipher_panel_who)
        quando = trovato.findViewById(R.id.cipher_panel_when)
        testo = trovato.findViewById(R.id.cipher_panel_text)

        val colori = Settings.getValues().mColors
        colori.setBackground(trovato, ColorType.STRIP_BACKGROUND)
        chi?.setTextColor(colori.get(ColorType.KEY_TEXT))
        quando?.setTextColor(colori.get(ColorType.KEY_HINT_TEXT))
        testo?.setTextColor(colori.get(ColorType.KEY_TEXT))
        trovato.findViewById<TextView>(R.id.cipher_panel_close)?.apply {
            setTextColor(colori.get(ColorType.KEY_TEXT))
            setOnClickListener { chiudi() }
        }
        // Chiuso all'aggancio: una gerarchia nuova non deve ereditare un
        // messaggio rimasto aperto in quella vecchia.
        chiudi()
    }

    /** C'e' una finestra della tastiera in cui mostrare qualcosa? */
    fun disponibile(): Boolean = pannello != null && servizio != null

    fun mostra(intestazione: String, data: String, messaggio: String) {
        val vista = pannello ?: return
        val ime = servizio ?: return
        // Prima di qualunque cosa che possa finire sullo schermo.
        runCatching {
            ime.window?.window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        chi?.text = intestazione
        quando?.text = data
        testo?.text = messaggio
        vista.isVisible = true
    }

    /**
     * Chiude e **dimentica**. Il testo si azzera qui e non solo si nasconde: una
     * vista invisibile che tiene ancora il chiaro e' il chiaro che resta in
     * memoria finche' la tastiera vive.
     */
    fun chiudi() {
        pannello?.isVisible = false
        chi?.text = ""
        quando?.text = ""
        testo?.text = ""
        runCatching {
            servizio?.window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Aperto adesso? Serve a chi deve decidere se il tasto indietro lo chiude. */
    fun isAperto(): Boolean = pannello?.isVisible == true
}
