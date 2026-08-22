package helium314.keyboard.cipher

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import java.security.MessageDigest

/**
 * Via 1: il blob arriva dalla clipboard.
 *
 * E' la via che funziona ovunque, e l'unica che non dipende da cosa fa l'app di
 * chat: le altre tre richiedono la barra di selezione standard, uno share
 * sheet, o che il blob sia gia' nel campo servito dall'IME.
 *
 * ## Perche' un IME puo' leggere la clipboard
 *
 * Da Android 10 l'accesso e' ristretto: *"unless your app is the default IME or
 * is the app that currently has focus, your app cannot access clipboard data"*.
 * Questa tastiera e' l'IME predefinito quando la si usa, quindi rientra
 * nell'eccezione. HeliBoard la legge gia' per la propria cronologia, quindi il
 * costo marginale in privacy di questa via e' nullo.
 *
 * ## Il toast di Android 12, e come non farlo comparire
 *
 * Da Android 12 il sistema mostra "app X ha incollato dagli appunti" la PRIMA
 * volta che un'app legge dati messi in clipboard da un'altra app. Comparirebbe
 * a ogni sessione di digitazione se controllassimo il contenuto per sapere se
 * c'e' qualcosa da decifrare.
 *
 * [ClipboardManager.getPrimaryClipDescription] NON lo fa comparire mai: dice il
 * tipo MIME senza consegnare il testo. Da qui la divisione fra i due metodi
 * sotto, che e' l'unica ragione per cui questo file esiste invece di due righe
 * dentro [CipherActions]:
 *
 *  - [hasText] guarda la descrizione. Si puo' chiamare quando si vuole;
 *  - [read] consegna il contenuto. Solo su gesto esplicito dell'utente.
 *
 * Chiamare [read] per "vedere se per caso c'e' un blob" e' il modo di
 * trasformare una funzione utile in un'app che sembra spiare gli appunti.
 */
internal object CipherClipboard {

    /**
     * C'e' del testo negli appunti? Non dice se e' un nostro blob — per
     * saperlo bisognerebbe leggerlo — dice solo se vale la pena chiedere.
     */
    fun hasText(context: Context): Boolean {
        val manager = manager(context) ?: return false
        return runCatching {
            manager.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        }.getOrDefault(false)
    }

    /**
     * Il contenuto degli appunti. **Solo dopo un gesto esplicito**: e' la
     * chiamata che fa comparire il toast di sistema, e soprattutto e' la
     * chiamata che legge davvero quello che l'utente ha copiato — che puo'
     * essere qualunque cosa, non solo roba nostra.
     */
    fun read(context: Context): CharSequence? {
        val manager = manager(context) ?: return null
        return runCatching {
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0)?.coerceToText(context)
        }.getOrNull()
    }

    private fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    // ========================================================================
    // Indizio: negli appunti c'e' qualcosa da decifrare
    // ========================================================================

    @Volatile
    private var clipLooksLikeOurs = false

    /**
     * Chiamata da `ClipboardHistoryManager` **sul testo che ha gia' letto**.
     *
     * E' l'unico modo onesto di alimentare l'indizio. Guardare da soli il
     * contenuto per sapere se c'e' qualcosa da decifrare vorrebbe dire leggere
     * la clipboard a ogni sessione di digitazione, e su Android 12+ ogni
     * lettura di un contenuto messo da un'altra app fa comparire il toast di
     * sistema: la tastiera sembrerebbe spiare gli appunti. Qui invece la
     * lettura e' gia' avvenuta per la cronologia, quindi il controllo costa
     * zero.
     *
     * Conseguenza dichiarata: **con la cronologia clipboard disattivata
     * l'indizio non si accende.** E' il prezzo di non leggere di nascosto, ed
     * e' preferibile al contrario.
     */
    fun noteClipboardContent(text: CharSequence) {
        clipLooksLikeOurs = CipherCore.available &&
            runCatching { CipherCore.nativeLooksLikeOurBlob(text.toString()) }.getOrDefault(false)
    }

    /**
     * Come [noteClipboardContent], ma dice anche cosa ha visto a chi tiene la
     * notifica del servizio keep-alive.
     *
     * Serve a distinguere due guasti che da fuori si somigliano: l'ascoltatore
     * degli appunti che **non scatta affatto** — perche' il processo non c'e',
     * o perche' non siamo piu' la tastiera predefinita e il sistema non ci
     * consegna il callback — e l'ascoltatore che scatta ma **non riconosce** il
     * blob. Nel primo caso la riga della notifica non cambia mai.
     */
    fun noteClipboardContent(context: Context, text: CharSequence) {
        noteClipboardContent(text)
        if (CipherSettings.isKeepAlive(context)) {
            runCatching { CipherKeepAlive.segnalaCopia(context, clipLooksLikeOurs) }
        }
    }

    /**
     * Se il tasto "decifra" debba mostrarsi acceso.
     *
     * Non promette niente: dice che negli appunti c'e' qualcosa che ha la
     * forma di un nostro blob. Potrebbe essere troncato, di un'altra versione,
     * o per un altro destinatario — lo si scopre decifrando, che e' un gesto
     * dell'utente.
     */
    fun clipboardLooksDecryptable(): Boolean = clipLooksLikeOurs

    // ========================================================================
    // Esclusione dalla cronologia della tastiera
    // ========================================================================

    /**
     * Impronta dell'ultimo contenuto che abbiamo messo noi in clipboard e che
     * non deve entrare nella cronologia.
     *
     * **Un hash e non il testo.** Tenere il plaintext in un campo statico
     * dell'IME sarebbe esattamente la fuga che questo meccanismo esiste per
     * evitare: sopravviverebbe alla chiusura della finestra che lo mostrava,
     * resterebbe in heap fino alla GC, e finirebbe in qualunque dump di
     * memoria. Un digest basta a riconoscere il contenuto e non permette di
     * ricostruirlo.
     */
    @Volatile
    private var sensitiveDigest: ByteArray? = null

    /**
     * Dichiara che il prossimo contenuto della clipboard non va storicizzato.
     *
     * **Va chiamata PRIMA di `setPrimaryClip`**: il listener della cronologia
     * puo' scattare durante quella chiamata, e un marcatore messo dopo
     * arriverebbe a cose fatte.
     */
    fun markSensitive(text: CharSequence) {
        sensitiveDigest = digest(text)
    }

    /**
     * Il cuore della trappola descritta in `DecryptActivity.copyPlaintext`: la
     * clipboard di sistema viene letta dall'IME predefinito, cioe' da QUESTA
     * stessa tastiera, che ne tiene una cronologia persistibile su disco. E' il
     * motivo per cui la via 1 costa zero in privacy, e in copia si ritorce
     * contro.
     *
     * `EXTRA_IS_SENSITIVE` non basta: nasconde l'anteprima di sistema, non
     * impedisce alla nostra cronologia di raccogliere il testo, e sotto
     * Android 13 non esiste. Questo controllo invece vale su tutte le versioni.
     */
    fun isSensitive(text: CharSequence): Boolean {
        val expected = sensitiveDigest ?: return false
        val actual = digest(text) ?: return false
        return expected.contentEquals(actual)
    }

    private fun digest(text: CharSequence): ByteArray? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray())
    }.getOrNull()
}
