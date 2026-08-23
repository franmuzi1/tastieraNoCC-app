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
import android.view.ViewGroup
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
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.settings.Settings

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

    /** I tasti che seguono la riga. `COMPOSE` non c'e': quello resta sempre. */
    private val TASTI_CIFRATURA = setOf(
        ToolbarKey.ENCRYPT,
        ToolbarKey.DECRYPT,
        ToolbarKey.SEND_PLAIN,
        ToolbarKey.ATTACH,
        ToolbarKey.CONTACTS,
    )

    /** Vedi `CipherSettings.PREF_LEARN`. Riletta in [reload]. */
    private var apprendi = false

    /**
     * Il campo su cui l'utente ha chiesto la riga **a mano**, scavalcando la
     * classificazione automatica. `null` quando non c'e' nessuna forzatura.
     *
     * Si ricorda il campo preciso — pacchetto piu' `fieldId` — e non un
     * semplice booleano: una forzatura che sopravvive al cambio di campo
     * farebbe comparire la riga dove non l'ha chiesta nessuno, e il posto piu'
     * probabile dove finirebbe e' il campo successivo di quella stessa app.
     * Chiedere la riga su una barra di ricerca non e' chiederla per sempre.
     */
    private var forzataSu: Pair<String, Int>? = null

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
     * Le due barre dei tasti della toolbar — quella espandibile e quella dei
     * tasti fissati. Servono a nascondere i tasti della cifratura sui campi
     * dove la riga non c'e': vedi [aggiornaTastiCifratura].
     */
    private var barreTasti: List<ViewGroup> = emptyList()

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
        // Come sopra, fuori dall'uscita anticipata: e' una preferenza sua e
        // cambiarla non muove la modalita' composizione. E si tiene in un campo
        // invece di leggerla al volo perche' [apprendimentoVietato] viene
        // chiamata a ogni parola composta.
        apprendi = CipherSettings.isLearn(context)
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
        val view = barra ?: return 0
        return if (view.isVisible) view.height else 0
    }

    /** Il chiaro composto finora. */
    fun text(): String = connection?.buffer?.toString().orEmpty()

    fun isEmpty(): Boolean = text().isEmpty()

    /**
     * Svuota il buffer, sovrascrivendo prima cio' che conteneva. Vedi
     * [svuotaSovrascrivendo] per cosa questo garantisce e cosa no.
     */
    fun clear() {
        svuotaSovrascrivendo(connection?.buffer)
        // `owner` NON si azzera qui, ed e' il difetto che questa riga aveva.
        //
        // Il proprietario dice a quale app appartiene il testo nel buffer.
        // Svuotare il buffer non cambia quale app ha il fuoco: e' ancora
        // quella, ed e' sua anche la prossima battuta. Azzerando, restava un
        // buffer senza proprietario proprio nell'istante che segue OGNI invio
        // riuscito — e la guardia in [onInputStarted] non svuota quando il
        // proprietario e' vuoto. Chi scriveva subito dopo un invio e cambiava
        // chat si portava dietro quel testo, che veniva cifrato per il
        // destinatario dell'ALTRA app: il fallimento peggiore che questo
        // sistema possa produrre, per la strada piu' battuta che ci sia.
        updateRow()
    }

    /**
     * Toglie il testo dal buffer dopo averci scritto sopra.
     *
     * `SpannableStringBuilder.clear()` da solo non azzera niente: sposta gli
     * indici e lascia il chiaro dov'era, dentro l'array di caratteri che il
     * buffer continua a tenersi. Il messaggio appena abbandonato — cambiando
     * app, o entrando in un campo password — restava cosi' leggibile in heap
     * per tutto il tempo che serviva alla GC o alla battuta successiva per
     * coprirlo. In un progetto che azzera i `ByteArray` dei segreti appena
     * finito di usarli, era l'unico segreto lasciato sul posto.
     *
     * La sostituzione ha la STESSA lunghezza apposta: il buffer non ha bisogno
     * di ridimensionarsi, quindi i NUL finiscono nell'array che teneva il
     * chiaro invece che in uno nuovo — che lascerebbe il vecchio intatto e
     * peggiorerebbe le cose.
     *
     * Cio' che e' gia' uscito di qui resta fuori portata: ogni `String` prodotta
     * da [text] e' immutabile e nessuno la puo' azzerare. Questo copre la copia
     * di lavoro, non tutte le copie — la garanzia di zeroizzazione si ferma
     * dove comincia la JVM, e questa e' la parte che si puo' fare.
     */
    private fun svuotaSovrascrivendo(buffer: Editable?) {
        if (buffer == null) return
        val lunghezza = buffer.length
        if (lunghezza > 0) buffer.replace(0, lunghezza, String(CharArray(lunghezza)))
        buffer.clear()
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
        // La password era l'unica esclusione, e non bastava: vedi [CipherFields]
        // per l'elenco dei campi che non sono compositori di messaggi e per il
        // motivo per cui il criterio e' "spegni su prova contraria" e non
        // "accendi su prova a favore".
        // Tre casi, non due. Vietata: password, e non si discute. Forzata a mano
        // su QUESTO campo: l'utente ha premuto il tasto qui, e la sua richiesta
        // vale piu' della nostra classificazione. Altrimenti decide
        // [CipherFields].
        val identita = editorInfo?.let { app to it.fieldId }
        if (forzataSu != null && forzataSu != identita) forzataSu = null
        suppressed = when {
            editorInfo == null -> false
            CipherFields.vietata(editorInfo) -> true
            forzataSu != null -> false
            else -> CipherFields.nonComponeMessaggi(editorInfo)
        }
        if (suppressed) {
            svuotaSovrascrivendo(connection?.buffer)
            updateRow()
            return
        }
        if (editorInfo == null || editorInfo.inputType == InputType.TYPE_NULL) return
        if (app.isEmpty() || app == self) return
        // Nessuna scorciatoia su `owner` vuoto: un buffer che non si sa a chi
        // appartenga non va ereditato da un'app nuova. Svuotare quando e' gia'
        // vuoto non costa niente, mentre tenerlo per attribuirlo poi a
        // chiunque arrivi costa il messaggio mandato alla persona sbagliata.
        if (owner != app) {
            svuotaSovrascrivendo(connection?.buffer)
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

        barreTasti = listOfNotNull(
            view.findViewById<ViewGroup>(R.id.toolbar),
            view.findViewById<ViewGroup>(R.id.pinned_keys),
        )
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
     * La riga e' sospesa per il campo corrente: non c'e' a schermo, non riceve
     * le battute, e a scrivere e' tornata a essere l'app. Vedi [onInputStarted].
     *
     * Diverso da `!isEnabled()`, che parla della preferenza: qui la modalita'
     * e' accesa e la riga esiste, semplicemente **questo** campo non la usa.
     * Serve a chi porta via il testo dal campo dell'app — vedi
     * `CipherActions.adoptFieldText`: con la riga sospesa non c'e' nessun posto
     * dove portarlo, e [adopt] lo rifiuterebbe comunque.
     */
    fun isSuppressed(): Boolean = suppressed

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
     * La tastiera non deve IMPARARE cio' che si sta scrivendo qui.
     *
     * Il resto della tastiera non sa che questo testo sara' cifrato: riceve le
     * battute come sempre, perche' `getCurrentInputConnection` e' deviata sulla
     * riga (vedi `LatinIME.setInputView`). Cosi' il chiaro finiva nel dizionario
     * personale — che vive in `filesDir`, quindi dentro il backup automatico di
     * Android — e ne riemergeva come suggerimento **mentre si scrive in chiaro
     * in un'altra app**. Cifrare il messaggio e poi vedersi suggerire il nome
     * che conteneva e' peggio che non averlo cifrato: la cifratura fa credere
     * che il testo sia rimasto qui.
     *
     * La condizione e' la stessa che decide se le battute finiscono nel nostro
     * buffer: `connection() != null` e' vero esattamente quando la riga esiste
     * ed e' attiva per questo campo. Non si duplica il criterio, si riusa —
     * perche' due criteri che devono coincidere prima o poi divergono.
     *
     * Chi la usa la tratta come la modalita' anonima gia' esistente
     * (`mIncognitoModeEnabled`), che e' il meccanismo che HeliBoard ha gia' per
     * "non imparare": stessa strada, gia' collaudata.
     *
     * NON copre la raccolta dati dei gesti (`GestureDataGathering`), che ha una
     * sua decisione separata ed e' spenta di default.
     *
     * L'utente puo' riaprire l'apprendimento con `CipherSettings.PREF_LEARN`,
     * spento di default. Il divieto e' il default e non l'unica strada, perche'
     * il costo e' reale — la tastiera non migliora sul vocabolario di chi
     * scrive spesso cifrato — ma chi non apre le impostazioni deve avere la
     * promessa mantenuta.
     */
    fun apprendimentoVietato(): Boolean = connection() != null && !apprendi

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

    /**
     * I tasti della cifratura seguono la riga: dove la riga non c'e', spariscono
     * anche loro — tranne l'interruttore, che e' l'unico modo di richiamarla.
     *
     * Su una barra di ricerca una tastiera con i lucchetti e senza la riga in
     * cui scrivere non e' "modalita' cifrata": e' un pannello di comandi che
     * non comandano niente. "Cifra" prenderebbe cio' che c'e' nel campo
     * dell'app, che li' e' la ricerca dell'utente.
     *
     * Si nascondono le VISTE invece di rifare la toolbar. La toolbar si
     * costruisce una volta sola, quando nasce la vista della tastiera, e per
     * cambiarne l'elenco servirebbe ricostruire tutto a ogni passaggio fra una
     * chat e una ricerca: molto piu' caro, e proprio nel momento in cui
     * l'utente sta aprendo un campo.
     *
     * I tasti si riconoscono dal `tag`, che e' la `ToolbarKey` con cui sono
     * stati creati.
     */
    private fun aggiornaTastiCifratura() {
        val mostra = enabled && !suppressed
        for (barra in barreTasti) {
            for (i in 0 until barra.childCount) {
                val figlio = barra.getChildAt(i) ?: continue
                val chiave = figlio.tag as? ToolbarKey ?: continue
                if (chiave == ToolbarKey.COMPOSE) continue
                if (chiave in TASTI_CIFRATURA) figlio.isVisible = mostra
            }
        }
    }

    /**
     * La riga e' a schermo adesso. La usa [CipherSchermoProtetto] per decidere
     * se la finestra va protetta dagli screenshot: qui si scrive del chiaro, e
     * il pannello di lettura sulla stessa finestra e' protetto da sempre.
     */
    fun rigaASchermo(): Boolean = enabled && !suppressed

    /**
     * La riga e' vietata sul campo corrente: nessuna forzatura la fara'
     * comparire. Serve al tasto in toolbar per dire il vero invece di
     * promettere una riga che non arrivera'.
     */
    fun rigaVietataQui(ime: InputMethodService): Boolean =
        ime.currentInputEditorInfo?.let { CipherFields.vietata(it) } ?: false

    /**
     * L'utente chiede la riga su questo campo, scavalcando la classificazione.
     *
     * Ritorna `false` se il campo la vieta — password — cosi' chi chiama puo'
     * spiegarlo invece di far finta di niente. La forzatura vale per QUESTO
     * campo e cade da sola al primo cambio, vedi [forzataSu].
     */
    fun forza(ime: InputMethodService): Boolean {
        val editorInfo = ime.currentInputEditorInfo ?: return false
        if (CipherFields.vietata(editorInfo)) return false
        val app = editorInfo.packageName.orEmpty()
        // Il proprietario va aggiornato QUI, e saltarlo sarebbe un difetto
        // grave: su un campo sospeso `onInputStarted` esce prima di assegnarlo,
        // quindi `owner` e' ancora quello dell'app di prima. Senza questa riga
        // il testo scritto nella riga appena forzata risulterebbe di
        // quell'altra app — e tornandoci, la guardia sul cambio non lo
        // svuoterebbe: si cifrerebbe per il destinatario sbagliato.
        if (owner.isNotEmpty() && owner != app) svuotaSovrascrivendo(connection?.buffer)
        owner = app
        forzataSu = app to editorInfo.fieldId
        suppressed = false
        updateRow()
        updateRecipient(app)
        return true
    }

    private fun updateRow() {
        val view = row ?: return
        barra?.isVisible = enabled && !suppressed
        view.isVisible = enabled && !suppressed
        // La riga puo' essere appena comparsa o appena sparita: il flag della
        // finestra si ricalcola qui e non nei singoli chiamanti, che sono
        // tanti e ne dimenticherebbero uno.
        CipherSchermoProtetto.aggiorna(servizio)
        aggiornaTastiCifratura()
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
