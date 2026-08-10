package helium314.keyboard.cipher

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * Riga di composizione: il chiaro si scrive **dentro la tastiera**, e l'app
 * riceve solo il blob, quando si preme il lucchetto.
 *
 * ## Cosa chiude
 *
 * Senza, il testo in chiaro veniva digitato nel campo dell'app di chat e ci
 * restava fino alla cifratura. In quella finestra l'app lo vede tutto: e' il
 * suo `EditText`, riceve ogni battuta, puo' salvarlo come bozza sul proprio
 * server e intanto annuncia "sta scrivendo". Il progetto esiste per non
 * consegnare il chiaro all'app di chat, e glielo consegnava — solo per qualche
 * secondo, e con l'utente convinto del contrario.
 *
 * Con la riga attiva il campo dell'app resta **vuoto** fino al lucchetto.
 *
 * Resta fuori cio' che era gia' fuori dal threat model: un keylogger, un
 * accessibility service o uno screenshot vedono la tastiera come vedono
 * qualunque altra cosa. Questo sposta il confine fra tastiera e app di chat,
 * non fra tastiera e sistema.
 *
 * ## Come e' fatta
 *
 * Non c'e' un secondo motore di digitazione: si sostituisce la sola
 * `InputConnection`. HeliBoard scrive dove gli dice `getCurrentInputConnection`,
 * quindi basta che quella, a modalita' attiva, sia una connessione su un buffer
 * nostro perche' correzione, suggerimenti, cancellazione, cursore e gesti
 * continuino a funzionare **senza modifiche**. Reimplementarli avrebbe
 * significato riscrivere una tastiera dentro una tastiera.
 *
 * ## Il buffer appartiene a un'app
 *
 * Il destinatario e' per app. Un testo cominciato in una conversazione non
 * deve poter essere cifrato in un'altra: sarebbe cifrato per la persona
 * sbagliata, che e' il fallimento peggiore che questo sistema possa produrre.
 * Per questo il buffer ricorda il package per cui e' nato e si azzera quando il
 * fuoco passa a un'app diversa — non quando lo perde, altrimenti andare a
 * decifrare un messaggio e tornare indietro butterebbe via cio' che si stava
 * scrivendo.
 */
object CipherCompose {

    private var enabled = false

    /** Il package a cui appartiene il testo nel buffer. */
    private var owner: String = ""

    /** Il nostro, per non farci mai possedere il buffer. Vedi [onInputStarted]. */
    private var self: String = ""

    private var connection: CipherConnection? = null
    private var row: CipherComposeView? = null

    /**
     * Sospesa per il campo corrente. Vedi [onInputStarted]: su una password la
     * riga non deve comparire ne' ricevere niente.
     */
    private var suppressed = false

    fun reload(context: Context) {
        self = context.packageName
        val wanted = CipherSettings.isComposeMode(context)
        if (wanted == enabled) return
        enabled = wanted
        // Spegnendo la modalita' il buffer non ha piu' un posto dove mostrarsi:
        // lasciarlo pieno significherebbe tenere del chiaro in memoria che
        // nessuno puo' piu' vedere ne' cifrare.
        if (!enabled) clear()
        updateRow()
    }

    fun isEnabled(): Boolean = enabled

    /**
     * La connessione da dare a HeliBoard al posto di quella dell'app, o `null`
     * se il testo deve andare all'app come sempre.
     */
    fun connection(): InputConnection? = if (enabled && !suppressed) connection else null

    /**
     * Quanto e' alta la riga adesso, zero se non c'e'.
     *
     * Serve a `onComputeInsets`: il sistema decide da li' dove finisce l'app e
     * comincia la tastiera. Senza contarla, l'app veniva disegnata **sotto** la
     * riga, che le copriva la casella di testo — e con essa il pulsante per
     * allegare e quello del microfono.
     */
    fun rowHeight(): Int {
        val view = row ?: return 0
        return if (view.isVisible) view.height else 0
    }

    /** Il chiaro composto finora. */
    fun text(): String = connection?.buffer?.toString().orEmpty()

    fun isEmpty(): Boolean = text().isEmpty()

    /**
     * Svuota il buffer. `Editable.clear()` non e' un azzeramento della memoria —
     * l'array interno resta allocato e il contenuto sopravvive fino a che non
     * viene sovrascritto — ed e' lo stesso limite che vale per qualunque
     * `CharSequence` da questo lato del confine. Vedi CLAUDE.md: la garanzia di
     * zeroizzazione si ferma dove comincia la JVM.
     */
    fun clear() {
        connection?.buffer?.clear()
        owner = ""
        updateRow()
    }

    /**
     * Il fuoco e' andato su un campo di [editorInfo]. Se il testo in corso era
     * per un'altra app si butta: vedi la nota sul destinatario per app in cima.
     *
     * Due casi vanno **ignorati**, e non sono dettagli: cambiando proprietario
     * il buffer si svuota, quindi ogni falso proprietario e' un messaggio
     * perso mentre lo si sta scrivendo.
     *
     *  - `TYPE_NULL` — il fuoco e' su qualcosa che non e' un campo di testo.
     *    Succede di continuo: basta una schermata fatta di soli pulsanti;
     *  - le **nostre** schermate. Andare a decifrare un messaggio ricevuto e
     *    tornare indietro passa da qui, e senza questa riga buttava via cio'
     *    che si stava componendo — proprio nel flusso piu' comune, leggere e
     *    rispondere.
     */
    fun onInputStarted(editorInfo: EditorInfo?) {
        if (!enabled) return
        val app = editorInfo?.packageName.orEmpty()
        // Su una password la riga NON compare e non riceve niente: mostrerebbe
        // a schermo cio' che il campo nasconde con i pallini, e lo terrebbe in
        // un buffer nostro. Il campo dell'app e' l'unico posto giusto per una
        // password, ed e' anche l'unico che non ha bisogno di essere cifrato.
        suppressed = editorInfo != null && InputTypeUtils.isPasswordInputType(editorInfo.inputType)
        if (suppressed) {
            connection?.buffer?.clear()
            updateRow()
            return
        }
        if (editorInfo == null || editorInfo.inputType == InputType.TYPE_NULL) return
        if (app.isEmpty() || app == self) return
        if (owner.isNotEmpty() && owner != app) {
            connection?.buffer?.clear()
        }
        owner = app
        updateRow()
    }

    /**
     * Aggancia la riga alla vista appena creata. Va richiamata a ogni
     * `setInputView`: la gerarchia viene ricostruita a ogni cambio di tema,
     * rotazione o modalita', e un riferimento tenuto oltre punterebbe a una
     * vista non piu' attaccata a niente.
     */
    fun bind(view: View) {
        val found: CipherComposeView? = view.findViewById(R.id.cipher_compose_row)
        row = found
        if (found == null) return
        if (connection == null) connection = CipherConnection(view) { updateRow() }

        val colors = Settings.getValues().mColors
        colors.setBackground(found, ColorType.STRIP_BACKGROUND)
        found.setTextColor(colors.get(ColorType.KEY_TEXT))
        found.setHintTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        found.setCaretColor(colors.get(ColorType.KEY_TEXT))
        updateRow()
    }

    /**
     * Prende nel buffer il testo che era gia' nel campo dell'app.
     *
     * Senza, restava a schermo del testo che la tastiera non poteva piu'
     * toccare: la cancellazione e il cursore lavorano sul buffer, quindi
     * l'ultima parola scritta prima di accendere la modalita' — o una bozza
     * ripristinata dall'app — diventava incancellabile. Due caselle visibili e
     * una sola che risponde ai tasti e' peggio che non avere la riga.
     *
     * Si adotta solo a buffer vuoto: quello che si sta componendo non si tocca.
     */
    fun adopt(text: CharSequence) {
        val connection = connection ?: return
        if (!enabled || suppressed || text.isEmpty()) return
        if (connection.buffer.isNotEmpty()) return
        connection.buffer.append(text)
        Selection.setSelection(connection.buffer, connection.buffer.length)
        updateRow()
    }

    fun isEmptyBuffer(): Boolean = connection?.buffer?.isEmpty() != false

    private fun updateRow() {
        val view = row ?: return
        view.isVisible = enabled && !suppressed
        if (!enabled || suppressed) return
        val buffer = connection?.buffer
        val text = buffer?.toString().orEmpty()
        // La posizione del cursore viene dal buffer e non da noi: e' HeliBoard
        // a spostarla, scrivendo e cancellando, ed e' l'unica fonte di verita'
        // su dove finira' il prossimo carattere.
        val start = buffer?.let { Selection.getSelectionStart(it) } ?: 0
        val end = buffer?.let { Selection.getSelectionEnd(it) } ?: 0
        view.setComposed(text, minOf(start, end).coerceAtLeast(0), maxOf(start, end).coerceAtLeast(0))
    }

    /**
     * `BaseInputConnection` su un buffer nostro.
     *
     * `BaseInputConnection` implementa gia' su `getEditable()` tutto cio' che
     * serve — testo prima e dopo il cursore, composizione, cancellazione,
     * selezione, maiuscole automatiche — quindi qui restano solo due cose: dire
     * qual e' il buffer, e impedire che qualcosa sfugga verso l'app.
     */
    private class CipherConnection(
        view: View,
        private val onChanged: () -> Unit,
    ) : BaseInputConnection(view, true) {

        val buffer = SpannableStringBuilder().also { Selection.setSelection(it, 0) }

        override fun getEditable(): Editable = buffer

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.commitText(text, newCursorPosition).also { onChanged() }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.setComposingText(text, newCursorPosition).also { onChanged() }

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            super.setComposingRegion(start, end).also { onChanged() }

        override fun finishComposingText(): Boolean =
            super.finishComposingText().also { onChanged() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingText(beforeLength, afterLength).also { onChanged() }

        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean =
            super.deleteSurroundingTextInCodePoints(before, after).also { onChanged() }

        override fun setSelection(start: Int, end: Int): Boolean =
            super.setSelection(start, end).also { onChanged() }

        /**
         * `BaseInputConnection` girerebbe l'evento alla vista bersaglio, che qui
         * e' la tastiera: non finirebbe nel buffer e non produrrebbe niente.
         * Quindi i tasti che contano si applicano al buffer a mano.
         *
         * Si ritorna sempre `true`, anche per quelli che non si gestiscono: un
         * `false` invita chi chiama a cercare un'altra strada, e l'unica altra
         * strada disponibile porta all'app.
         */
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event == null || event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> deleteSurroundingText(1, 0)
                KeyEvent.KEYCODE_FORWARD_DEL -> deleteSurroundingText(0, 1)
                KeyEvent.KEYCODE_ENTER -> commitText("\n", 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(1)
                else -> {
                    val unicode = event.unicodeChar
                    if (unicode != 0) commitText(unicode.toChar().toString(), 1)
                }
            }
            return true
        }

        private fun moveCursor(delta: Int) {
            val position = (Selection.getSelectionEnd(buffer) + delta).coerceIn(0, buffer.length)
            Selection.setSelection(buffer, position)
            onChanged()
        }

        /**
         * Il tasto invio dell'app — "invia", "cerca", "vai".
         *
         * Non fa **niente**, ed e' il punto piu' importante di questa classe:
         * inoltrarlo consegnerebbe all'app il comando di spedire mentre il
         * chiaro non e' ancora cifrato. Qui non c'e' niente da spedire finche'
         * non si preme il lucchetto, e il modo giusto di dirlo e' non fare
         * nulla.
         */
        override fun performEditorAction(editorAction: Int): Boolean = true

        override fun performContextMenuAction(id: Int): Boolean = true

        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true

        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
    }
}
