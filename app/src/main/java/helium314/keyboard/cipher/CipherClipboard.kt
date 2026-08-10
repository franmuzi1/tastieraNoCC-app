package helium314.keyboard.cipher

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

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
}
