package helium314.keyboard.cipher

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.isVisible
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
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
    private var barra: View? = null
    private var destinatario: TextView? = null

    /** La tendina della selezione. Vedi [mostraMenu]. */
    private var menu: View? = null

    /**
     * Il servizio, per poter aprire la scelta del destinatario dal tocco sul
     * nome. Non si ricava dalla vista: dentro la finestra di un IME il contesto
     * e' un wrapper con il tema, non il servizio, e il controllo falliva in
     * silenzio — il nome si toccava e non succedeva niente.
     */
    private var servizio: InputMethodService? = null

    /**
     * Sospesa per il campo corrente. Vedi [onInputStarted]: su una password la
     * riga non deve comparire ne' ricevere niente.
     */
    private var suppressed = false

    /** Vedi [CipherSettings.PREF_BLOCK_COPY]. */
    private var bloccaCopia = CipherSettings.DEFAULT_BLOCK_COPY

    fun reload(context: Context) {
        self = context.packageName
        // Fuori dall'uscita anticipata qui sotto: quella confronta la sola
        // modalita' composizione, e cambiare il solo interruttore del copia non
        // la muove — la preferenza nuova non verrebbe mai riletta.
        bloccaCopia = CipherSettings.isBlockCopy(context)
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
     * Il servizio, per chi deve tornare alla tastiera da un'Activity.
     *
     * Stesso processo, quindi non e' un riferimento che attraversa un confine:
     * `RecipientActivity` lo usa per far cifrare subito dopo la scelta, invece
     * di lasciare all'utente un secondo tocco sul lucchetto.
     */
    fun servizio(): InputMethodService? = servizio

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
        val view = barra ?: return 0
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
        updateRecipient(app)
    }

    /**
     * Aggancia la riga alla vista appena creata. Va richiamata a ogni
     * `setInputView`: la gerarchia viene ricostruita a ogni cambio di tema,
     * rotazione o modalita', e un riferimento tenuto oltre punterebbe a una
     * vista non piu' attaccata a niente.
     */
    fun bind(ime: InputMethodService, view: View) {
        servizio = ime
        val found: CipherComposeView? = view.findViewById(R.id.cipher_compose_row)
        row = found
        barra = view.findViewById(R.id.cipher_compose_bar)
        destinatario = view.findViewById(R.id.cipher_recipient)
        // Toccare il nome cambia destinatario: e' il posto dove l'utente sta
        // gia' guardando quando si chiede "ma a chi lo sto mandando?".
        destinatario?.setOnClickListener {
            servizio?.let { ime -> CipherActions.chiediDestinatarioOra(ime) }
        }
        if (found == null) return
        if (connection == null) connection = CipherConnection(view) { updateRow() }
        found.onSelezione = { inizio, fine -> spostaSelezione(inizio, fine) }
        found.onMenu = { x -> mostraMenu(x) }
        found.onMenuDaChiudere = { chiudiMenu() }
        // Toccare la riga vuol dire "rispondo": il pannello del messaggio
        // decifrato copre i tasti, quindi si toglie di mezzo da solo.
        found.onToccata = { CipherPanel.chiudi() }

        val colors = Settings.getValues().mColors
        barra?.let { colors.setBackground(it, ColorType.STRIP_BACKGROUND) }
        colors.setBackground(found, ColorType.STRIP_BACKGROUND)
        found.setTextColor(colors.get(ColorType.KEY_TEXT))
        found.setHintTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        found.setCaretColor(colors.get(ColorType.KEY_TEXT))
        collegaMenu(view, colors)
        updateRow()
        updateRecipient(owner)
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

    /**
     * Copiare adesso porterebbe fuori del chiaro, e l'utente ha chiesto di non
     * poterlo fare.
     *
     * Tre condizioni, tutte necessarie. Il buffer deve avere qualcosa, e
     * soprattutto deve essere **lui** a ricevere le battute: su un campo
     * password la riga e' sospesa e la tastiera scrive di nuovo nel campo
     * dell'app, quindi cio' che si copierebbe e' roba dell'app — guardare
     * [isEnabled] invece della connessione vera bloccherebbe il copia dove non
     * c'e' niente da proteggere.
     */
    fun copiaDelChiaroVietata(): Boolean =
        bloccaCopia && connection() != null && !isEmptyBuffer()

    /**
     * Il dito ha spostato il cursore o selezionato un pezzo di testo.
     *
     * Il passaggio che conta non e' spostare la selezione — quello e' una riga —
     * ma **dirlo a HeliBoard**. Per un campo vero e' il sistema a chiamare
     * `onUpdateSelection` quando il cursore si muove da solo, e da li' la
     * tastiera capisce che la parola che stava componendo non e' piu' quella e
     * che adesso c'e' del testo selezionato. Qui il sistema non c'e': il buffer
     * e' nostro, e nessuno la chiamerebbe.
     *
     * E' anche cio' che fa funzionare il cancella. HeliBoard cancella una
     * selezione intera solo se sa che esiste, e lo sa da quella chiamata: senza,
     * il tasto continuerebbe a togliere un carattere per volta con mezza frase
     * evidenziata a schermo — cioe' esattamente il problema per cui la selezione
     * e' stata aggiunta.
     */
    private fun spostaSelezione(inizio: Int, fine: Int) {
        val connection = connection ?: return
        val buffer = connection.buffer
        val vecchioInizio = Selection.getSelectionStart(buffer)
        val vecchiaFine = Selection.getSelectionEnd(buffer)
        val a = inizio.coerceIn(0, buffer.length)
        val b = fine.coerceIn(a, buffer.length)
        // La parola in composizione finisce qui: il cursore se n'e' andato
        // altrove, e lasciarla aperta significherebbe che la prossima battuta
        // riscrive testo che sta da un'altra parte.
        connection.finishComposingText()
        Selection.setSelection(buffer, a, b)
        updateRow()
        avvisaDelloSpostamento(vecchioInizio, vecchiaFine)
    }

    /**
     * Incolla dentro la riga, invece di lasciar partire il finto CTRL+V.
     *
     * Il tasto incolla di HeliBoard non consegna testo: manda un evento CTRL+V
     * sperando che il campo dell'app lo interpreti. Qui il campo e' un buffer
     * nostro, quindi quell'evento non produceva niente — il tasto sembrava
     * rotto. Incollare **dentro** la riga e' sicuro: e' testo che entra, il
     * contrario del copia che lo porta fuori.
     *
     * Leggere gli appunti qui non tradisce la regola di non spiarli: e' il gesto
     * esplicito dell'utente, non una lettura di nostra iniziativa.
     *
     * @return vero se il tasto e' stato gestito qui, falso se deve seguire la
     * strada normale verso l'app.
     */
    fun incolla(context: Context): Boolean {
        // La connessione **in uso**, non quella che esiste: su un campo password
        // la riga e' sospesa e chi scrive e' di nuovo l'app, quindi incollare
        // qui dentro metterebbe il testo in un buffer che nessuno vede e
        // toglierebbe all'utente il suo incolla.
        val connection = connection() as? CipherConnection ?: return false
        val appunti = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // `toString`: nel buffer si tiene testo semplice, gli span verrebbero
        // dietro senza servire a niente.
        val testo = appunti?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        // Appunti vuoti: si gestisce lo stesso, e non si fa niente. Lasciar
        // proseguire manderebbe il CTRL+V all'app, che incollerebbe nel proprio
        // campo cio' che l'utente voleva qui.
        if (testo.isNullOrEmpty()) return true
        val buffer = connection.buffer
        val vecchioInizio = Selection.getSelectionStart(buffer)
        val vecchiaFine = Selection.getSelectionEnd(buffer)
        // `commitText` sostituisce la selezione, come ci si aspetta da un
        // incolla, e passa dalla connessione perche' e' li' che vive
        // l'aggiornamento della riga.
        connection.commitText(testo, 1)
        avvisaDelloSpostamento(vecchioInizio, vecchiaFine)
        return true
    }

    /**
     * Dice a HeliBoard dov'e' finito il cursore.
     *
     * In differita, come farebbe il sistema. Per un campo vero
     * `onUpdateSelection` non arriva mai dentro la chiamata che ha spostato il
     * cursore, ma dopo: "seleziona tutto" e "incolla" arrivano qui **da dentro**
     * HeliBoard, e richiamarlo subito lo farebbe rientrare in se' stesso mentre
     * sta ancora leggendo il testo.
     */
    private fun avvisaDelloSpostamento(vecchioInizio: Int, vecchiaFine: Int) {
        val buffer = connection?.buffer ?: return
        val a = Selection.getSelectionStart(buffer).coerceAtLeast(0)
        val b = Selection.getSelectionEnd(buffer).coerceAtLeast(a)
        val avvisa = Runnable { servizio?.onUpdateSelection(vecchioInizio, vecchiaFine, a, b, -1, -1) }
        if (row?.post(avvisa) != true) avvisa.run()
    }

    /**
     * Aggiorna il nome del destinatario mostrato accanto alla riga.
     *
     * Si interroga il core a ogni giro invece di tenerne una copia: cambia
     * quando si decifra un messaggio, quando lo si sceglie a mano, e quando il
     * servizio riparte. Una copia in memoria sarebbe l'ennesimo stato da
     * mantenere allineato, e sbagliarlo qui significherebbe mostrare un nome
     * mentre si cifra per un altro.
     */
    fun updateRecipient(appPackage: String) {
        val view = destinatario ?: return
        val nome = if (CipherCore.available && appPackage.isNotEmpty()) {
            CipherCore.nativeCurrentPeerName(appPackage)
        } else {
            null
        }
        val colori = Settings.getValues().mColors
        view.text = when {
            nome != null -> "→ $nome"
            else -> view.context.getString(R.string.cipher_no_recipient_short)
        }
        view.setTextColor(
            colori.get(if (nome != null) ColorType.KEY_TEXT else ColorType.KEY_HINT_TEXT)
        )
    }

    // ========================================================================
    // Il menu a tendina della selezione
    // ========================================================================

    /**
     * Aggancia le cinque voci, una volta per gerarchia.
     *
     * **Nessuna azione e' scritta qui.** Ognuna manda il codice tasto che
     * HeliBoard usa gia' per la stessa cosa, e finisce in `InputLogic` dove le
     * regole stanno scritte una volta sola — compreso il divieto di copiare il
     * chiaro, che altrimenti avrebbe due implementazioni destinate a divergere.
     * Vedi `InputLogic.rifiutaCopiaDelChiaro`.
     */
    private fun collegaMenu(view: View, colori: helium314.keyboard.latin.common.Colors) {
        val tendina = view.findViewById<View>(R.id.cipher_selection_menu)
        menu = tendina ?: return
        colori.setBackground(tendina, ColorType.POPUP_KEYS_BACKGROUND)
        val voci = listOf(
            R.id.cipher_menu_copy to KeyCode.CLIPBOARD_COPY,
            R.id.cipher_menu_paste to KeyCode.CLIPBOARD_PASTE,
            R.id.cipher_menu_cut to KeyCode.CLIPBOARD_CUT,
            R.id.cipher_menu_delete to KeyCode.DELETE,
            R.id.cipher_menu_select_all to KeyCode.CLIPBOARD_SELECT_ALL,
        )
        for ((id, codice) in voci) {
            val voce = tendina.findViewById<TextView>(id) ?: continue
            voce.setTextColor(colori.get(ColorType.KEY_TEXT))
            voce.setOnClickListener {
                // Prima si chiude: "seleziona tutto" lascia una selezione viva,
                // e una tendina che resta aperta sopra il risultato nasconde
                // proprio cio' che si e' appena ottenuto.
                chiudiMenu()
                (servizio as? LatinIME)?.onEvent(
                    Event.createSoftwareKeypressEvent(
                        codice, 0, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false,
                    )
                )
            }
        }
    }

    /**
     * Apre la tendina sotto il punto in cui il dito si e' alzato.
     *
     * La x arriva dalla riga e non si usa cosi' com'e': va rientrata perche' la
     * tendina non esca dallo schermo a destra. La y non serve — la riga e' alta
     * 56dp e sta in cima, quindi la tendina scende sempre da li' sui tasti, che
     * e' l'unico spazio disponibile: sopra c'e' l'app.
     */
    private fun mostraMenu(x: Float) {
        val tendina = menu ?: return
        val riga = row ?: return
        tendina.visibility = View.VISIBLE
        // Misurata prima di poterla posizionare: a vista appena mostrata la
        // larghezza e' ancora zero, e rientrare qualcosa di largo zero non
        // rientra niente.
        tendina.post {
            val padre = tendina.parent as? View ?: return@post
            val margine = (x + riga.left - tendina.width / 2f)
                .coerceIn(0f, (padre.width - tendina.width).coerceAtLeast(0).toFloat())
            tendina.translationX = margine
        }
    }

    private fun chiudiMenu() {
        menu?.visibility = View.GONE
    }

    /**
     * Chiude la tendina se la selezione non c'e' piu'.
     *
     * Chiamata a ogni aggiornamento della riga: cancellare, incollare o
     * scrivere fanno collassare la selezione, e un menu che sopravvive a cio'
     * su cui agiva offre azioni che non hanno piu' un bersaglio.
     */
    private fun chiudiMenuSenzaSelezione(inizio: Int, fine: Int) {
        if (fine <= inizio) chiudiMenu()
    }

    private fun updateRow() {
        val view = row ?: return
        barra?.isVisible = enabled && !suppressed
        view.isVisible = enabled && !suppressed
        if (!enabled || suppressed) return
        val buffer = connection?.buffer
        val text = buffer?.toString().orEmpty()
        // La posizione del cursore viene dal buffer e non da noi: e' HeliBoard
        // a spostarla, scrivendo e cancellando, ed e' l'unica fonte di verita'
        // su dove finira' il prossimo carattere.
        val start = buffer?.let { Selection.getSelectionStart(it) } ?: 0
        val end = buffer?.let { Selection.getSelectionEnd(it) } ?: 0
        val a = minOf(start, end).coerceAtLeast(0)
        val b = maxOf(start, end).coerceAtLeast(0)
        chiudiMenuSenzaSelezione(a, b)
        view.setComposed(text, a, b)
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

        /**
         * Il menu contestuale, che qui arriva da un tasto della toolbar.
         *
         * Si onora **solo** "seleziona tutto": e' la scorciatoia per buttare via
         * quello che si stava scrivendo senza tenere premuto cancella, ed e'
         * sulla toolbar da sempre — semplicemente, in questa modalita' non
         * faceva niente.
         *
         * Il resto continua a non fare nulla e a rispondere `true`: un `false`
         * invita chi chiama a cercare un'altra strada, e l'unica altra strada
         * disponibile porta all'app.
         */
        override fun performContextMenuAction(id: Int): Boolean {
            if (id == android.R.id.selectAll) spostaSelezione(0, buffer.length)
            return true
        }

        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true

        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
    }
}
