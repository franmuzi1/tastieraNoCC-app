package helium314.keyboard.cipher

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Riceve testo da `ACTION_PROCESS_TEXT` (via 2) e `ACTION_SEND` (via 3),
 * lo decifra e mostra il chiaro.
 *
 * SCHELETRO: nessuna implementazione, solo il flusso e i vincoli.
 */
class DecryptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prima di qualunque cosa che possa finire sullo schermo.
        // Blocca screenshot, registrazione schermo, e la miniatura che il
        // sistema salva per la schermata Recenti.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)

        // TODO: con launchMode=singleTask un secondo intent verso un'istanza
        //   gia' viva arriva in onNewIntent, non in onCreate. Senza
        //   sovrascriverlo, la seconda decifratura viene ignorata e resta a
        //   schermo il plaintext precedente. noHistory riduce la finestra ma
        //   non la chiude: l'Activity vive finche' e' visibile.

        val incoming = extractText(intent)
        if (incoming == null) {
            finish()
            return
        }

        // TODO: chiamare CipherCore.handleIncomingText(...) e distinguere:
        //  - NOT_OUR_BLOB   -> messaggio "questo testo non e' cifrato", esci
        //  - MESSAGE        -> mostra il chiaro (vedi showPlaintext)
        //  - IDENTITY_CARD  -> mostra fingerprint e chiedi conferma del pin
        //  - CONFLICT       -> schermata di conflitto, NON mostrare nulla
        //                      finche' l'utente non decide
        //  - TIER_UNSUPPORTED -> "messaggio creato con una versione piu' recente"
        //  - CRYPTO         -> un solo messaggio generico. Non esporre mai la
        //                      causa: il core non la distingue apposta, la UI
        //                      non deve reintrodurre la distinzione.
    }

    /**
     * `ACTION_PROCESS_TEXT` porta il testo in [Intent.EXTRA_PROCESS_TEXT];
     * `ACTION_SEND` in [Intent.EXTRA_TEXT].
     */
    private fun extractText(intent: Intent): CharSequence? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        else -> null
    }

    /**
     * ATTENZIONE — il contratto di `ACTION_PROCESS_TEXT` prevede che
     * l'Activity possa restituire al chiamante un testo sostitutivo, tramite
     * `setResult(RESULT_OK, Intent().putExtra(EXTRA_PROCESS_TEXT, nuovoTesto))`.
     *
     * Qui NON si fa MAI, per nessun motivo. Restituire il plaintext significa
     * consegnarlo all'app di chat da cui e' partita la selezione: cioe'
     * esattamente all'applicazione da cui l'intero progetto esiste per
     * tenerlo lontano. E' l'implementazione "naturale" di questo intent, ed e'
     * la peggiore possibile.
     *
     * Il chiaro si mostra solo nella nostra finestra, e finisce li'.
     *
     * Per lo stesso motivo non si chiama mai `setResult` con dati: si esce
     * senza risultato, e il testo nell'app chiamante resta cifrato.
     */
    private fun showPlaintext(plaintext: ByteArray) {
        // TODO: renderizzare il testo.
        //
        // Il core consegna ByteArray e non String proprio per poterlo azzerare;
        // ma per mostrarlo a schermo serve una CharSequence, quindi una copia
        // non azzerabile esiste comunque nella UI. La garanzia si ferma qui, ed
        // e' un limite noto: si mitiga con FLAG_SECURE, noHistory, e azzerando
        // il ByteArray appena costruita la stringa da mostrare.
        //
        // TODO: plaintext.fill(0) subito dopo l'uso.
    }

    /**
     * Copia del chiaro, se l'utente la chiede esplicitamente.
     *
     * Da Android 13 si puo' marcare il contenuto come sensibile: la clipboard
     * di sistema smette di mostrarlo in anteprima. Non e' protezione vera — il
     * testo e' comunque in clipboard in chiaro, leggibile da chi ha il fuoco —
     * ma evita che compaia nel popup di anteprima davanti a chi guarda.
     */
    // TRAPPOLA: la clipboard di sistema viene letta dall'IME predefinito —
    // cioe' da QUESTA stessa tastiera, che tiene una cronologia clipboard. E'
    // il motivo per cui la via 1 costa zero in privacy, e qui si ritorce
    // contro: il testo decifrato entrerebbe in quella cronologia, che puo'
    // essere persistita su disco. EXTRA_IS_SENSITIVE nasconde l'anteprima di
    // sistema ma non impedisce alla nostra cronologia di raccoglierlo, e sotto
    // Android 13 non esiste nemmeno.
    // Prima di abilitare questa funzione va escluso esplicitamente questo
    // contenuto dalla cronologia clipboard del fork.
    private fun copyPlaintext(text: CharSequence) {
        val clip = ClipData.newPlainText(null, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
}
