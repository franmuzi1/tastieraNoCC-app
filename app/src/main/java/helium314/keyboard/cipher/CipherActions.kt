package helium314.keyboard.cipher

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.prefs

/**
 * I due gesti della tastiera: cifra il campo, manda il campo a decifrare.
 *
 * Sta nel package `cipher` e non in `latin` perche' il fork deve poter fare
 * merge da upstream senza conflitti: qui dentro upstream non tocca niente. Il
 * prezzo e' due righe in `KeyboardActionListenerImpl`, che sono anche l'unico
 * punto in cui HeliBoard sa che questa roba esiste.
 */
object CipherActions {

    /**
     * Limite di lettura del campo. Serve un tetto — `InputConnection` viaggia
     * su Binder e una transazione troppo grande fallisce — ma un tetto usato
     * per troncare in silenzio significherebbe cifrare mezzo messaggio e
     * spedirlo: il mittente vede il testo sparire, il destinatario riceve un
     * troncone, e nessuno dei due capisce perche'. Quindi se il campo arriva
     * al limite si rifiuta e lo si dice.
     */
    private const val MAX_FIELD_CHARS = 8192

    /**
     * Quanto puo' essere lungo il blob perche' una chat lo accetti.
     *
     * Telegram si ferma a 4096 caratteri per messaggio, ed e' il piu' stretto
     * fra quelli che contano (WhatsApp arriva a 65536). Sopra quella soglia il
     * messaggio non parte, oppure l'app lo spezza — e **un blob spezzato non si
     * decifra piu'**: la meta' che arriva non e' un messaggio piu' corto, e'
     * spazzatura.
     *
     * Si controlla PRIMA di sostituire il campo. Accorgersene dopo vorrebbe
     * dire aver gia' cancellato il testo dell'utente per rimpiazzarlo con
     * qualcosa che non si puo' mandare.
     */
    private const val MAX_BLOB_CHARS = 4096

    /**
     * Involucro del blob, in byte, contato sul formato vero.
     *
     * Fuori dal cifrato: 4 di prefisso, 32 di chiave nell'intestazione, 24 di
     * nonce, 16 di tag. Dentro: 8 di marca temporale e 32 di prekey. Totale
     * **116**.
     *
     * Era 84, cioe' l'ingombro del solo schema statico, che `encrypt_for_app`
     * **non usa piu'**: entrambi i rami mettono 32 byte di chiave dentro il
     * cifrato. La stima usciva corta di ~49 caratteri, e un messaggio fra 2443
     * e 2472 byte passava questo controllo per poi produrre un blob oltre i
     * 4096 di Telegram — cioe' proprio il caso che il controllo esiste per
     * fermare, e che si scopre quando il campo dell'utente e' gia' stato
     * svuotato.
     *
     * Se un giorno un ramo dovesse ingombrare di piu', questa costante va
     * alzata e non abbassata: sbagliare per eccesso rifiuta un messaggio che
     * sarebbe passato, sbagliare per difetto ne manda uno che si spezza.
     */
    private const val OVERHEAD_BYTES = 116

    /**
     * `kc/`, tre caratteri. NON contiene la versione: il core la tiene fuori di
     * proposito, cosi' un blob di una versione futura viene comunque
     * riconosciuto come nostro invece di sembrare testo qualunque.
     */
    private const val SENTINEL_LEN = 3

    /**
     * Quanto si aspetta prima di dare per rifiutato l'avvio della schermata.
     *
     * Il processo e' gia' vivo — e' quello della tastiera — quindi l'Activity si
     * presenta in poche decine di millisecondi. Un secondo e' abbondante:
     * sbagliare per eccesso costa un ripiego in ritardo, sbagliare per difetto
     * costa un ripiego che non serviva, sopra una schermata gia' aperta.
     */
    private const val ATTESA_APERTURA_MS = 1000L

    /** Quanto si da' alla finestra della tastiera per comparire. */
    private const val ATTESA_FINESTRA_MS = 400L

    /**
     * Sostituisce il contenuto del campo con il blob cifrato.
     *
     * Il destinatario NON si indovina: lo decide il core in base all'app
     * (ultimo mittente letto, o memoria per package). Se non c'e', si chiede
     * invece di scegliere — cifrare per la persona sbagliata e' il fallimento
     * peggiore che questo sistema possa produrre.
     */
    fun encrypt(ime: InputMethodService) {
        if (!ready(ime)) return
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        if (pacchetto.isNotEmpty() && !CipherCore.nativeHasCurrentPeer(pacchetto)) {
            chiediDestinatario(ime)
            return
        }
        val ic = appConnection(ime) ?: return

        // Con la riga di composizione attiva il chiaro non e' mai stato nel
        // campo dell'app: sta nel buffer della tastiera, e il campo va
        // riempito, non sostituito. Se il buffer e' vuoto si guarda comunque il
        // campo — puo' esserci del testo scritto prima di accendere la
        // modalita', e buttarlo via sarebbe una sorpresa.
        val composed = if (CipherCompose.isEnabled() && !CipherCompose.isEmpty()) {
            Field(CipherCompose.text(), 0, 0)
        } else {
            null
        }
        val field = composed ?: readField(ime, ic) ?: return
        if (field.text.isEmpty()) {
            toast(ime, R.string.cipher_nothing_to_encrypt)
            return
        }

        // Da qui in poi il chiaro esiste anche come String, che e' immutabile e
        // non azzerabile: la garanzia del core si ferma al confine con
        // InputConnection, che parla CharSequence. L'array lo azzeriamo
        // comunque — e' la copia che vive piu' a lungo.
        // Il limite si dice prima di cifrare, con quanto tagliare: dopo, il
        // campo sarebbe gia' stato svuotato per far posto a un blob che la
        // chat rifiuta.
        val stimato = stimaBlob(field.text)
        if (stimato > MAX_BLOB_CHARS) {
            val daTogliere = ((stimato - MAX_BLOB_CHARS) * 5 / 8).coerceAtLeast(1)
            toast(ime, R.string.cipher_message_too_long)
            KeyboardSwitcher.getInstance().showToast(
                ime.getString(R.string.cipher_message_too_long_detail, daTogliere),
                false,
            )
            return
        }

        val plaintext = field.text.toByteArray()
        val blob = try {
            CipherCore.nativeEncryptForApp(
                ime.currentInputEditorInfo?.packageName.orEmpty(),
                plaintext,
                System.currentTimeMillis() / 1000,
                CipherSettings.isForwardSecrecy(ime),
            )
        } finally {
            plaintext.fill(0)
        }

        if (blob == null) {
            // Un vicolo cieco diventa il punto d'ingresso: chiedere "a chi?" e
            // non offrire il modo di rispondere e' il motivo per cui il tasto
            // sembrava rotto.
            chiediDestinatario(ime)
            return
        }
        // Su disco SUBITO, prima che il blob esca. Con la forward secrecy
        // accesa cifrare non e' piu' un'operazione di sola lettura: genera una
        // chiave temporanea nuova, e se il processo muore prima che sia
        // salvata la risposta dell'altro arriva cifrata verso una chiave che
        // non esiste piu'. Il costo e' una scrittura per messaggio inviato.
        // L'esito si guarda. Con la forward secrecy accesa cifrare ha appena
        // generato una chiave usa-e-getta, e se non arriva su disco il
        // messaggio parte lo stesso ma la risposta non si aprira' mai: un
        // fallimento che si manifesta ore dopo, a carico dell'altra persona.
        // Qui si e' ancora in tempo — il campo non e' stato toccato — quindi si
        // annulla invece di spedire.
        if (!CipherIdentity.persistKeyring(ime)) {
            toast(ime, R.string.cipher_keyring_not_saved_send)
            return
        }
        if (composed != null) {
            // Il campo dell'app e' vuoto per costruzione: qui non si sostituisce
            // niente, si consegna. E il buffer si svuota solo DOPO che il blob
            // e' stato consegnato — svuotarlo prima, con la consegna che poi
            // fallisce, cancellerebbe un messaggio che non e' mai partito.
            ic.beginBatchEdit()
            ic.finishComposingText()
            ic.commitText(blob, 1)
            ic.endBatchEdit()
            CipherCompose.clear()
            deliver(ime, ic)
            return
        }
        if (!replaceField(ic, field, blob)) {
            // Il campo non e' stato sostituito: nel dubbio l'utente deve
            // saperlo, perche' il fallimento silenzioso qui e' il peggiore
            // possibile — si crede di aver cifrato e si preme invio sul chiaro.
            toast(ime, R.string.cipher_replace_failed)
        }
    }

    /**
     * Chiede all'app di spedire quello che le abbiamo appena messo nel campo.
     * Ritorna `false` se non si e' potuto: chi chiama decide cosa dire.
     *
     * ## Perche' non sempre funziona
     *
     * "Invia" e' un pulsante dell'**app**, e una tastiera non puo' premerlo.
     * L'unica leva che esiste e' `performEditorAction`, cioe' l'azione che il
     * campo dichiara di avere — la stessa che HeliBoard usa per il tasto invio.
     * Nelle chat quel campo e' spesso multiriga e **non dichiara nessuna
     * azione**, perche' l'invio sta accanto e l'invio col tasto invio e'
     * un'opzione dell'utente. In Telegram si accende da Impostazioni → Chat →
     * *Invia con Invio*.
     *
     * Si riusa `getImeOptionsActionIdFromEditorInfo` invece di rileggere
     * `imeOptions` a mano: e' la stessa funzione che decide cosa fa il tasto
     * invio, comprese le eccezioni per app note, e due letture diverse dello
     * stesso campo sarebbero due comportamenti diversi per lo stesso gesto.
     *
     * *Non si ricade sul tasto invio simulato.* In un campo multiriga
     * inserirebbe un a capo dentro il messaggio appena consegnato invece di
     * spedirlo: rovinerebbe il blob, e in silenzio.
     */
    private fun deliver(ime: InputMethodService, ic: InputConnection): Boolean {
        if (!CipherSettings.isAutoSend(ime)) return false
        val info = ime.currentInputEditorInfo ?: return false
        val action = InputTypeUtils.getImeOptionsActionIdFromEditorInfo(info)
        val perAzione = when {
            action == InputTypeUtils.IME_ACTION_CUSTOM_LABEL -> ic.performEditorAction(info.actionId)
            action != EditorInfo.IME_ACTION_NONE -> ic.performEditorAction(action)
            else -> false
        }
        if (perAzione) return true

        // Ripiego: il tasto invio vero.
        //
        // Serve per le chat, che sono il caso che conta. Un campo di
        // messaggistica e' multiriga e **non dichiara nessuna azione**: quando
        // l'utente accende "invia con invio", l'app non cambia cio' che
        // dichiara, intercetta il tasto. `performEditorAction` cade nel vuoto e
        // l'invio automatico sembrava rotto — lo era, in Telegram.
        //
        // L'avevo escluso temendo che in un campo multiriga inserisse un a capo
        // dentro il messaggio appena consegnato. Succede, se l'invio con invio
        // e' spento: ma un a capo **in coda** al blob non lo rovina, perche' il
        // riconoscimento tollera spazi e contesto — e' scritto in CLAUDE.md ed
        // e' provato dai test del formato. Il costo del ripiego e' quindi una
        // riga vuota in fondo al campo; il costo di non averlo era una funzione
        // che non funziona dove serve.
        val premuto = ic.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        ) && ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        // `sendKeyEvent` dice solo che l'evento e' partito, non che l'app abbia
        // spedito: non c'e' modo di saperlo, e prometterlo sarebbe peggio che
        // tacere.
        if (!premuto) {
            val pacchetto = info.packageName?.toString().orEmpty()
            avvisoUnaVolta(ime, "invio_$pacchetto", R.string.cipher_send_unavailable)
        }
        return premuto
    }

    /**
     * La connessione verso l'app, non quella verso il buffer di composizione.
     *
     * A modalita' composizione attiva `currentInputConnection` e' dirottata sul
     * buffer della tastiera: per tutto cio' che deve arrivare davvero all'app —
     * il blob cifrato, la presentazione — serve quella vera. Leggere il campo
     * per decifrare passa di qui per lo stesso motivo: il blob da decifrare sta
     * nell'app, non in cio' che si sta scrivendo.
     */
    private fun appConnection(ime: InputMethodService): InputConnection? =
        (ime as? LatinIME)?.appInputConnection ?: ime.currentInputConnection

    /**
     * Scrive nel campo la propria identity card.
     *
     * E' il bootstrap del primo contatto, e chiude l'unico buco che il TOFU
     * lascia aperto: ogni messaggio cifrato porta gia' `sender_pub` in chiaro,
     * quindi *ricevere* un messaggio fissa automaticamente la chiave del
     * mittente — resta scoperta solo la primissima direzione, prima che l'altro
     * abbia scritto qualcosa.
     *
     * Passa dal campo e non dagli appunti per l'asimmetria che governa tutto il
     * progetto: **inserire nel campo e' nativo per un IME, leggere no.** La
     * tastiera vede il campo di input, mai la cronologia della chat. Per questo
     * la cifratura non tocca la clipboard e la decifratura si'.
     *
     * Costo di bootstrap risultante: un tocco lungo, una volta per contatto, in
     * una sola direzione. L'utente poi preme invio come per qualunque
     * messaggio.
     *
     * Dall'esterno la card e' indistinguibile da un messaggio: stesso sentinel,
     * il tipo sta in un byte DENTRO il cifrato, e la lunghezza e' randomizzata
     * apposta perche' non sia isolabile con una regex. Un sentinel dedicato
     * alle presentazioni sarebbe un marcatore in chiaro di "utente che aggancia
     * un contatto nuovo", raccolto a costo zero da uno scanning di massa.
     *
     * *Nota di scopribilita':* un tocco lungo e' nascosto. Il punto d'ingresso
     * visibile e' la UI contatti (Impostazioni -> Contatti); questo e' il gesto
     * veloce, non l'unico previsto.
     */
    fun insertIdentityCard(ime: InputMethodService) {
        if (!ready(ime)) return
        // Nel campo dell'app: la card serve a essere spedita.
        val ic = appConnection(ime) ?: return
        val card = CipherCore.nativeIdentityCard()
        if (card == null) {
            toast(ime, R.string.cipher_unavailable)
            return
        }
        // Inserimento al cursore, non sostituzione: se il campo contiene gia'
        // del testo non c'e' motivo di buttarlo via. Il riconoscimento
        // tollera il contesto — `parse` cerca il sentinel dentro la stringa e
        // prende la sequenza massima di caratteri dell'alfabeto che segue —
        // quindi una card in mezzo ad altro resta leggibile.
        ic.commitText(card, 1)
        avvisoUnaVolta(ime, "card", R.string.cipher_card_inserted)
    }

    /**
     * Sposta nella riga il testo che era gia' nel campo dell'app.
     *
     * Serve al caso piu' banale e piu' fastidioso: apri una chat, l'app
     * ripristina la bozza — oppure avevi scritto prima di accendere la
     * modalita' — e quel testo resta a schermo senza che la tastiera possa
     * toccarlo, perche' cancellazione e cursore lavorano sul buffer. Due
     * caselle visibili e una sola che risponde ai tasti sono peggio che non
     * avere la riga.
     *
     * **Si adotta solo se il campo si e' davvero svuotato.** Se la
     * cancellazione non riesce, il testo resterebbe in tutti e due i posti: al
     * momento di cifrare finirebbe nel blob *e* accanto ad esso, in chiaro.
     * Meglio non adottare niente e lasciare le cose come stanno.
     */
    @JvmOverloads
    fun adoptFieldText(ime: InputMethodService, primaryCode: Int = 0) {
        // MAI sui tasti della cifratura. Sono gli unici che leggono il campo
        // dell'app per conto proprio — "decifra" cerca li' il blob, "cifra" ci
        // ricade quando la riga e' vuota — e adottare un istante prima glielo
        // toglierebbe di sotto: il tasto decifra smetteva di funzionare del
        // tutto, perche' trovava il campo vuoto.
        // L'estremo basso e' l'ULTIMO codice della cifratura, non il primo
        // aggiunto: aggiungendone uno nuovo senza spostarlo, quel tasto
        // resterebbe fuori dall'intervallo e tornerebbe ad adottare il campo.
        if (primaryCode <= KeyCode.CIPHER_ENCRYPT && primaryCode >= KeyCode.CIPHER_GALLERY) {
            return
        }
        // MAI sull'invio. E' il tasto con cui si SPEDISCE cio' che sta nel
        // campo, quindi adottare li' vuol dire portarselo via un istante prima
        // che parta: si preme la freccia, il messaggio sparisce dal campo e
        // ricompare nella riga della tastiera, e non si e' spedito niente. E'
        // il caso di chi ha appena premuto "consegna in chiaro" e vuole
        // mandarlo — cioe' il gesto immediatamente successivo, ogni volta.
        //
        // Il resto continua ad adottare, cancellazione compresa: correggere un
        // refuso in cio' che si e' appena consegnato e' la ragione per cui
        // l'adozione esiste.
        if (primaryCode == Constants.CODE_ENTER ||
            primaryCode == KeyCode.SHIFT_ENTER ||
            primaryCode == KeyCode.ACTION_NEXT ||
            primaryCode == KeyCode.ACTION_PREVIOUS
        ) {
            return
        }
        if (!CipherSettings.isEnabled(ime)) return
        if (!CipherCompose.isEnabled() || !CipherCompose.isEmptyBuffer()) return
        val ic = appConnection(ime) ?: return
        val field = readField(ime, ic) ?: return
        if (field.text.isEmpty()) return
        if (!replaceField(ic, field, "")) return
        CipherCompose.adopt(field.text)
    }

    /**
     * Accende e spegne la riga di composizione dalla toolbar.
     *
     * La stessa cosa che fa l'interruttore nelle impostazioni, ma a portata di
     * pollice: decidere dove si scrive non e' una configurazione che si fa una
     * volta, e' una scelta che cambia da conversazione a conversazione — con
     * chi ha la tastiera si scrive dentro, con tutti gli altri no. Se per
     * cambiarla bisogna uscire dalla chat e attraversare le impostazioni, non
     * la cambia nessuno.
     *
     * `setThemeNeedsReload` e non `reloadKeyboard`: i tasti della striscia si
     * costruiscono una volta sola, e questo tocco fa comparire o sparire tutta
     * la cifratura — lucchetti compresi — oltre a cambiare l'altezza della
     * tastiera.
     */
    fun toggleCompose(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        val prefs = ime.prefs()
        val wanted = !CipherSettings.isComposeMode(prefs)
        prefs.edit().putBoolean(CipherSettings.PREF_COMPOSE_MODE, wanted).apply()
        CipherCompose.reload(ime)
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        // La riga che compare o sparisce e' gia' la risposta: la spiegazione
        // serve solo la prima volta.
        avvisoUnaVolta(
            ime,
            if (wanted) "riga_accesa" else "riga_spenta",
            if (wanted) R.string.cipher_compose_on else R.string.cipher_compose_off,
        )
    }

    /**
     * Decifra e mostra dentro la tastiera. `false` se non si e' potuto, e allora
     * chi chiama prosegue con l'Activity.
     *
     * Torna `false` per **qualunque** esito che non sia un messaggio di testo
     * riuscito: gli altri cinque li gestisce [DecryptActivity], che li ha gia'
     * tutti. Qui non si duplica quella logica — due implementazioni degli stessi
     * esiti divergono al primo che se ne aggiunge.
     */
    /**
     * Cosa e' successo al blob copiato.
     *
     * Tre esiti e non un booleano perche' i casi sono tre davvero: mostrato nel
     * pannello (e allora la finestra va chiesta), gia' sbrigato per conto suo
     * (e allora chi chiama non deve fare NIENTE, o rielaborerebbe il blob), o
     * non nostro da gestire qui.
     */
    private enum class Esito { PANNELLO, GESTITO, NO }

    private fun mostraNelPannello(ime: InputMethodService, blob: String): Esito {
        if (CipherIdentity.ensureReady(ime) != CipherState.Ready) return Esito.NO
        val result = CipherCore.IncomingResult()
        val code = CipherCore.nativeHandleIncomingText(
            ime.currentInputEditorInfo?.packageName.orEmpty(),
            blob,
            System.currentTimeMillis() / 1000,
            result,
        )
        if (code != CipherCore.OK) return Esito.NO
        // ## La presentazione mai vista non si puo' rielaborare
        //
        // `nativeHandleIncomingText` NON e' senza effetti: una chiave mai vista
        // la fissa (TOFU) proprio mentre la legge. Rimandarla all'Activity
        // significherebbe richiamare il core sullo stesso blob, e la seconda
        // volta risulterebbe **gia' nota**: niente dialogo del nome, e il
        // contatto salvato senza nome — cioe' l'opposto di cio' che serve nel
        // momento in cui qualcuno si presenta per la prima volta.
        //
        // Quindi si apre di qui la stessa schermata che aprirebbe
        // `DecryptActivity.showIdentityCard`, con lo stesso ingresso. La chiave
        // del peer e' PUBBLICA: non e' un segreto che viaggia in un intent.
        //
        // Se invece era gia' nota, rielaborarla da' lo stesso esito e se ne
        // occupa l'Activity come sempre.
        if (result.kind == CipherCore.KIND_IDENTITY_CARD) {
            val peer = result.senderKey
            if (result.alreadyPinned != 0 || peer == null) return Esito.NO
            // Il pin appena avvenuto va su disco: vive solo in memoria finche'
            // qualcuno non lo scrive, e al riavvio il TOFU ricomincerebbe.
            if (!CipherIdentity.persistKeyring(ime)) {
                toast(ime, R.string.cipher_keyring_not_saved)
            }
            scalaDiApertura(ime, blob, ContactsActivity.intentNomina(ime, peer))
            return Esito.GESTITO
        }
        // Anche un messaggio nostro riaperto: e' testo in chiaro come l'altro, e
        // trattarlo diversamente vorrebbe dire che rileggere cio' che hai
        // scritto tu apre una schermata mentre leggere quello di un altro no.
        val mio = result.kind == CipherCore.KIND_OWN_MESSAGE
        if (result.kind != CipherCore.KIND_MESSAGE && !mio) return Esito.NO
        val bytes = result.plaintext ?: return Esito.NO
        // Il core consegna ByteArray e non String proprio per poterlo azzerare.
        // Per mostrarlo serve una CharSequence, quindi una copia non azzerabile
        // esiste comunque: la garanzia si ferma qui, e si mitiga con FLAG_SECURE
        // sulla finestra e azzerando l'array.
        val chiaro = try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
        // Il pin appena fissato va su disco, esattamente come nell'Activity.
        if (!CipherIdentity.persistKeyring(ime)) {
            toast(ime, R.string.cipher_keyring_not_saved)
        }
        val nome = result.senderLabel ?: result.senderFingerprint.orEmpty()
        // Su un messaggio nostro non si mostra "confrontato di persona": li' non
        // c'e' nessuna identita' da verificare, l'abbiamo scritto noi. Stessa
        // regola di DecryptActivity.showMessage.
        val intestazione = when {
            mio -> ime.getString(R.string.cipher_own_message_to, nome)
            result.verified == 1 -> ime.getString(R.string.cipher_sender_verified, nome)
            else -> nome
        }
        val quando = runCatching {
            android.text.format.DateFormat.getDateFormat(ime).format(java.util.Date(result.sentAtUnix * 1000)) +
                " " +
                android.text.format.DateFormat.getTimeFormat(ime).format(java.util.Date(result.sentAtUnix * 1000))
        }.getOrDefault("")
        CipherPanel.mostra(intestazione, quando, chiaro)
        return Esito.PANNELLO
    }

    /**
     * Quanto sara' lungo il blob per questo testo, prima di produrlo.
     *
     * Si stima invece di cifrare e misurare: cifrare per poi scoprire che non
     * si puo' mandare significherebbe aver gia' consumato il nonce e, peggio,
     * aver gia' toccato il campo. La stima e' esatta a meno di un carattere,
     * perche' z-base-32 e' deterministico: otto caratteri ogni cinque byte.
     */
    private fun stimaBlob(testo: String): Int {
        val byte = testo.toByteArray().size + OVERHEAD_BYTES
        return SENTINEL_LEN + (byte * 8 + 4) / 5
    }

    /**
     * Il messaggio appena copiato e' uno dei nostri: aprilo.
     *
     * Chiamata da `ClipboardHistoryManager` quando arriva un elemento nuovo,
     * cioe' nel solo momento in cui una tastiera puo' accorgersi di qualcosa
     * senza andarselo a cercare. Il testo li' e' gia' stato letto per la
     * cronologia: nessun secondo accesso agli appunti, nessun toast di sistema.
     *
     * Il riconoscimento passa dal core (`nativeLooksLikeOurBlob`): guarda solo
     * il sentinel e la lunghezza minima, non decifra e non tocca il keyring.
     * Scrivere quel controllo qui sarebbe una seconda fonte di verita' sul
     * formato.
     */
    fun autoDecrypt(ime: InputMethodService, testo: CharSequence) {
        if (!CipherSettings.isAutoOpen(ime)) return
        val contenuto = testo.toString()
        if (!CipherCore.available || !CipherCore.nativeLooksLikeOurBlob(contenuto)) return
        // ## Prima di tutto: il pannello dentro la tastiera
        //
        // Non e' un avvio di Activity, quindi non passa da nessuna restrizione:
        // la finestra della tastiera e' gia' nostra. Copre il caso comune — un
        // messaggio di testo che si decifra — e lascia all'Activity tutto il
        // resto, che e' anche il modo di non avere due implementazioni dei sei
        // esiti.
        //
        // **La finestra va chiesta.** Riempire il pannello non lo fa vedere: se
        // la tastiera non e' a schermo resta li' invisibile, e l'utente lo trova
        // solo aprendo la tastiera a mano — che e' esattamente cio' che
        // succedeva. `requestShowSelf` non e' un avvio di Activity e non passa
        // da nessuna restrizione: e' la tastiera che chiede la propria finestra.
        // Serve pero' una sessione di input attiva, quindi puo' non bastare: si
        // guarda com'e' andata e in caso si ripiega sulla scala qui sotto.
        val esito = if (CipherPanel.disponibile()) mostraNelPannello(ime, contenuto) else Esito.NO
        if (esito == Esito.GESTITO) return
        if (esito == Esito.PANNELLO) {
            if (ime.isInputViewShown) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { ime.requestShowSelf(0) }
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (ime.isInputViewShown) return@postDelayed
                // La finestra non e' arrivata: il pannello resterebbe carico e
                // invisibile, col chiaro dentro. Si svuota e si prosegue.
                CipherPanel.chiudi()
                scalaDiApertura(ime, contenuto)
            }, ATTESA_FINESTRA_MS)
            return
        }
        scalaDiApertura(ime, contenuto)
    }

    /**
     * Le tre vie per far comparire [DecryptActivity], quando il pannello dentro
     * la tastiera non e' praticabile.
     */
    private fun scalaDiApertura(
        ime: InputMethodService,
        contenuto: String,
        daAprire: Intent? = null,
    ) {
        val intent = daAprire ?: Intent(ime, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, contenuto)
            putExtra(
                CipherHandoff.extraName(),
                CipherHandoff.issue(ime.currentInputEditorInfo?.packageName.orEmpty()),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // ## Tre tentativi, dal meno invadente al piu' rumoroso
        //
        // **1. Aprire e basta.** Da Android 10 un'app senza finestre visibili
        // non dovrebbe poter avviare un'Activity, ma misurato su Android 14
        // (emulatore AOSP) si apre lo stesso: il sistema tratta l'IME
        // predefinito come un caso a parte. Dove vale, finisce qui.
        //
        // **2. Mostrare la tastiera, poi riprovare.** Su un telefono vero
        // l'avvio viene invece rifiutato — riscontrato con la riga diagnostica
        // della notifica keep-alive: "messaggio cifrato riconosciuto" e niente
        // a schermo. `requestShowSelf` pero' **non e' un avvio di Activity**: e'
        // la tastiera che chiede la propria finestra, e non passa da quelle
        // restrizioni. E una volta che quella finestra e' visibile l'app ha una
        // finestra a schermo, quindi il secondo `startActivity` non e' piu' "in
        // background". Costa zero permessi. Funziona solo se in quel momento
        // c'e' un campo di testo attivo: in una chat con la tastiera chiusa puo'
        // non esserci, ed e' il motivo per cui esiste anche il terzo.
        //
        // **3. L'avviso da toccare.** Ultima spiaggia, e nient'altro funziona.
        //
        // L'esito non si puo' controllare direttamente: un avvio rifiutato non
        // lancia niente, `startActivity` torna come se fosse andato bene. Quindi
        // si guarda se qualcuno si e' presentato — vedi `DecryptActivity.Apertura`.
        val tentativo = DecryptActivity.Apertura.ora()
        runCatching { ime.startActivity(intent) }
        if (ime.isInputViewShown) return

        val mano = Handler(Looper.getMainLooper())
        mano.postDelayed({
            if (DecryptActivity.Apertura.avvenutaDopo(tentativo)) return@postDelayed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { ime.requestShowSelf(0) }
            }
            mano.postDelayed({
                if (DecryptActivity.Apertura.avvenutaDopo(tentativo)) return@postDelayed
                runCatching { ime.startActivity(intent) }
                mano.postDelayed({
                    if (!DecryptActivity.Apertura.avvenutaDopo(tentativo)) {
                        CipherNotification.offer(ime, contenuto)
                    }
                }, ATTESA_APERTURA_MS)
            }, ATTESA_FINESTRA_MS)
        }, ATTESA_APERTURA_MS)
    }

    /**
     * Apre la scelta del destinatario per l'app in cui si sta scrivendo.
     *
     * Il package viaggia nel gettone di [CipherHandoff], non come extra
     * qualunque: gli extra li scrive chiunque, e attribuire la scelta all'app
     * sbagliata dirotterebbe per chi si cifra.
     */
    /** Come [chiediDestinatario], ma chiamabile da fuori: dal nome nella riga. */
    fun chiediDestinatarioOra(ime: InputMethodService) = chiediDestinatario(ime)

    /**
     * Manda un file cifrato, dalla tastiera.
     *
     * Il tasto **non sceglie il destinatario**: apre l'elenco dei contatti, e
     * la persona la indica l'utente (decisione G4). Il destinatario ricordato
     * per l'app qui non si usa nemmeno quando c'e' — un file mandato alla
     * persona sbagliata non si ritira, e a differenza di un messaggio resta sul
     * telefono di chi lo riceve.
     *
     * Un'Activity e non un pannello della tastiera: serve il selettore di
     * documenti del sistema, che un IME non puo' aprire per conto proprio, e
     * serve `FLAG_SECURE` sulle impronte.
     */
    fun allegato(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intentAllegato(ime, soloMedia = false)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }

    /**
     * Come [allegato], ma il selettore parte gia' filtrato su immagini e video.
     *
     * Due tasti e non uno con la pressione lunga: mandare una foto e mandare un
     * documento sono i due gesti piu' frequenti qui dentro, e nascondere il
     * primo dietro un gesto che va scoperto significa che non lo usa nessuno.
     */
    fun galleria(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intentAllegato(ime, soloMedia = true)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }

    /** Apre l'elenco dei contatti cifrati. */
    fun contatti(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        runCatching { ime.startActivity(ContactsActivity.intent(ime)) }
            .onFailure { toast(ime, R.string.cipher_unavailable) }
    }

    private fun chiediDestinatario(ime: InputMethodService) {
        val pacchetto = ime.currentInputEditorInfo?.packageName.orEmpty()
        if (pacchetto.isEmpty()) {
            toast(ime, R.string.cipher_no_recipient)
            return
        }
        runCatching { ime.startActivity(RecipientActivity.intent(ime, pacchetto)) }
            .onFailure { toast(ime, R.string.cipher_no_recipient) }
    }

    /**
     * Consegna all'app il testo composto **in chiaro**, senza cifrarlo.
     *
     * Esiste solo in modalita' composizione, e serve al caso banale e
     * frequentissimo che senza questo tasto costringerebbe a spegnere la
     * modalita': mandare "arrivo", un indirizzo, un link a qualcuno che non ha
     * questa tastiera. Senza, l'unica via sarebbe passare dalle impostazioni,
     * e una funzione che si aggira dalle impostazioni e' una funzione che
     * verra' spenta e mai piu' riaccesa.
     *
     * Consegna il testo al campo e poi, **se l'invio automatico e' acceso**,
     * chiede all'app di spedirlo — esattamente come fa il lucchetto. Questa
     * documentazione diceva il contrario ("non spedisce mai"): era vera prima
     * che l'invio automatico esistesse, ed e' rimasta indietro. Chi vuole
     * rileggere prima di mandare spegne *Invia subito* in Impostazioni →
     * Cifratura.
     */
    fun sendPlain(ime: InputMethodService) {
        if (!CipherSettings.isEnabled(ime)) return
        if (!CipherCompose.isEnabled()) return
        val ic = appConnection(ime) ?: return
        val text = CipherCompose.text()
        if (text.isEmpty()) {
            toast(ime, R.string.cipher_nothing_to_send)
            return
        }
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.commitText(text, 1)
        ic.endBatchEdit()
        // Dopo la consegna, come per il blob: svuotare prima significherebbe
        // perdere il testo se la consegna fallisse.
        CipherCompose.clear()
        if (!deliver(ime, ic)) avvisoUnaVolta(ime, "chiaro", R.string.cipher_sent_plain)
    }

    /**
     * Manda il contenuto del campo a [DecryptActivity].
     *
     * Il chiaro NON torna nel campo, ed e' il punto centrale: il campo
     * appartiene all'app di chat, quindi scriverci il testo decifrato lo
     * consegnerebbe esattamente all'applicazione da cui questo progetto esiste
     * per tenerlo lontano. Il chiaro si vede solo nella nostra finestra, che e'
     * `FLAG_SECURE`, e finisce li'.
     */
    fun decrypt(ime: InputMethodService) = decrypt(ime, clipboardOnly = false)

    /**
     * Pressione lunga sul tasto "decifra": salta il campo e va dritto agli
     * appunti.
     *
     * Serve quando il campo NON e' vuoto ma contiene altro — una risposta gia'
     * cominciata, per dirne una. Senza, la pressione breve userebbe quel testo
     * e direbbe "non e' cifrato", che e' vero e inutile.
     */
    fun decryptFromClipboard(ime: InputMethodService) = decrypt(ime, clipboardOnly = true)

    private fun decrypt(ime: InputMethodService, clipboardOnly: Boolean) {
        if (!ready(ime)) return
        val text = source(ime, clipboardOnly) ?: return

        // ACTION_SEND e non un'azione nostra: DecryptActivity la gestisce gia'
        // per lo share sheet, e avere una via sola dentro l'Activity significa
        // una sola implementazione dei sei esiti.
        val intent = Intent(ime, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            // Il package dell'app in cui si sta scrivendo, che solo l'IME
            // conosce. Senza, DecryptActivity lo dedurrebbe da `referrer` e
            // otterrebbe il package DELLA TASTIERA, perche' per questa via il
            // chiamante siamo noi: il destinatario finirebbe registrato sotto
            // "helium314.keyboard" e la regola "decifrare stabilisce il
            // destinatario" non scatterebbe mai per l'app giusta.
            // Un gettone, non il package: gli extra sono scrivibili da
            // chiunque, un valore casuale generato in questo processo no.
            putExtra(
                CipherHandoff.extraName(),
                CipherHandoff.issue(ime.currentInputEditorInfo?.packageName.orEmpty()),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
    }

    /**
     * Da dove prendere il testo da decifrare: prima il campo, poi gli appunti.
     *
     * Quest'ordine perche' il campo e' gratis e non ha effetti collaterali,
     * mentre leggere gli appunti su Android 12+ fa comparire un toast di
     * sistema e tira dentro qualunque cosa l'utente abbia copiato. Si arriva
     * agli appunti solo quando il campo non ha niente da offrire.
     *
     * `null` significa "gia' detto all'utente cosa non va": chi chiama esce e
     * basta.
     */
    private fun source(ime: InputMethodService, clipboardOnly: Boolean): CharSequence? {
        if (!clipboardOnly) {
            val ic = appConnection(ime)
            if (ic != null) {
                // Distinzione che conta: `null` qui vuol dire "campo troppo
                // lungo, gia' segnalato", non "campo vuoto". Ricadere sugli
                // appunti in quel caso decifrerebbe una cosa diversa da quella
                // che l'utente stava guardando.
                val field = readField(ime, ic) ?: return null
                if (field.text.isNotEmpty()) return field.text
            }
        }
        // La descrizione prima del contenuto: e' l'unica chiamata che non fa
        // comparire il toast di sistema. Vedi CipherClipboard.
        if (!CipherClipboard.hasText(ime)) {
            toast(ime, R.string.cipher_nothing_to_decrypt)
            return null
        }
        val clip = CipherClipboard.read(ime)
        if (clip.isNullOrEmpty()) {
            toast(ime, R.string.cipher_nothing_to_decrypt)
            return null
        }
        return clip
    }

    /**
     * Inizializza il core e traduce un esito diverso da `Ready` in un
     * messaggio. Tre casi, tre messaggi: dire "non disponibile" a chi ha solo
     * il telefono bloccato manderebbe a cercare un guasto che non c'e'.
     */
    private fun ready(ime: InputMethodService): Boolean =
        // L'interruttore generale prima di tutto: a cifratura spenta i tasti
        // non ci sono, ma un codice puo' arrivare lo stesso da una scorciatoia
        // personalizzata rimasta in un profilo salvato.
        if (!CipherSettings.isEnabled(ime)) false
        else when (CipherIdentity.ensureReady(ime)) {
            CipherState.Ready -> true
            CipherState.Locked -> {
                toast(ime, R.string.cipher_locked)
                false
            }
            // TODO: Unreadable merita una schermata, non un toast: l'unica
            //   uscita e' resetIdentity, che distrugge l'identita' e va
            //   spiegata. Arriva con la UI contatti.
            else -> {
                toast(ime, R.string.cipher_unavailable)
                false
            }
        }

    /**
     * Il campo letto, con quanto ne sta prima e quanto dopo il cursore.
     *
     * I due conteggi servono alla sostituzione: senza, non resta che indovinare
     * quanto cancellare per ciascun lato, e indovinare troppo poco lascia il
     * chiaro nel campo accanto al blob.
     */
    private class Field(val text: String, val before: Int, val after: Int)

    /**
     * Il campo intero, non solo cio' che sta prima del cursore: si cifra il
     * messaggio, non la parte scritta finora.
     *
     * Due vie, e l'ordine non e' indifferente.
     *
     * `getExtractedText` chiede il contenuto **completo** in una sola chiamata,
     * ed e' l'unica che non dipende da come l'app tratta la regione di
     * composizione. Le tre chiamate dell'altra via — prima, selezione, dopo —
     * vanno ricucite da noi, e un'app che ne restituisca una parziale produce
     * un messaggio mutilato senza che nessuno dei due lati se ne accorga.
     *
     * `partialStartOffset >= 0` significa che l'app ha risposto con una
     * porzione invece che col tutto: quel risultato non e' utilizzabile e si
     * passa alla seconda via, che almeno e' esplicita su cosa sta chiedendo.
     */
    private fun readField(ime: InputMethodService, ic: InputConnection): Field? {
        // Puo' lanciare se la connessione muore fra il controllo e la chiamata:
        // in una tastiera un'eccezione non gestita significa un dispositivo su
        // cui non si puo' piu' scrivere.
        val extracted = runCatching { ic.getExtractedText(extractRequest(), 0) }.getOrNull()
        val whole = extracted?.text
        if (whole != null && extracted.partialStartOffset < 0) {
            if (whole.length >= MAX_FIELD_CHARS) {
                toast(ime, R.string.cipher_text_too_long)
                return null
            }
            // selectionStart/End arrivano dall'app: possono essere -1, o
            // invertiti se la selezione e' stata fatta da destra a sinistra.
            // Si normalizzano prima di usarli come lunghezze.
            val start = minOf(extracted.selectionStart, extracted.selectionEnd)
                .coerceIn(0, whole.length)
            val end = maxOf(extracted.selectionStart, extracted.selectionEnd)
                .coerceIn(start, whole.length)
            return Field(whole.toString(), start, whole.length - end)
        }

        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) ?: ""
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) ?: ""
        // `getSelectedText` e' indispensabile, non un raffinamento: le due
        // chiamate qui sopra restituiscono cio' che sta PRIMA dell'inizio e
        // DOPO la fine della selezione, non la selezione stessa. Senza questa
        // riga, cifrare con del testo selezionato produceva un blob privo della
        // parte selezionata, che poi veniva cancellata dalla sostituzione: il
        // mittente spediva un messaggio mutilato credendo di aver cifrato
        // tutto, e nessuno dei due lati poteva accorgersene.
        val selected = ic.getSelectedText(0) ?: ""
        if (before.length >= MAX_FIELD_CHARS || after.length >= MAX_FIELD_CHARS) {
            toast(ime, R.string.cipher_text_too_long)
            return null
        }
        return Field(
            before.toString() + selected.toString() + after.toString(),
            before.length,
            after.length,
        )
    }

    /**
     * Cancella quello che c'era e mette il blob. Ritorna `false` se al termine
     * il campo non contiene il solo blob.
     *
     * In un solo batch: senza, l'app vede il campo passare per lo stato vuoto,
     * e le app che reagiscono a ogni modifica (indicatore "sta scrivendo",
     * bozze salvate) registrerebbero uno stato intermedio che non e' mai
     * esistito per l'utente.
     *
     * `finishComposingText` e non `commitText("")`: il primo dichiara conclusa
     * la parola in composizione **lasciandola nel campo**, dov'e' gia' stata
     * contata da `before`; il secondo la cancella, e da quel momento i due
     * conteggi non descrivono piu' il campo. Era il motivo per cui prima si
     * cancellava "abbondando" da entrambi i lati, cioe' senza sapere davvero
     * quanto si stesse togliendo.
     *
     * ## Perche' c'e' una verifica
     *
     * Cancellare e' una richiesta all'app, non un'operazione che facciamo noi:
     * `deleteSurroundingText` puo' essere ignorata, o applicata solo in parte,
     * da qualunque editor con una `InputConnection` propria. Ed e' il modo
     * peggiore in cui questa funzione possa fallire — il chiaro resta nel
     * campo accanto al blob, sembra che sia stata cifrata solo l'ultima
     * parola, e chi preme invio spedisce il messaggio in chiaro.
     *
     * Quindi si rilegge, e se il campo non e' il solo blob si riprova
     * selezionando tutto: `commitText` sostituisce la selezione, che e' una
     * strada diversa dalla cancellazione per lunghezze e fallisce in casi
     * diversi. Se non basta nemmeno quella, chi chiama lo dice all'utente.
     */
    private fun replaceField(ic: InputConnection, field: Field, blob: String): Boolean {
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(field.before, field.after)
        ic.commitText(blob, 1)
        ic.endBatchEdit()

        if (fieldIs(ic, blob)) return true

        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(blob, 1)
        ic.endBatchEdit()

        return fieldIs(ic, blob)
    }

    /**
     * Se il campo contiene esattamente [expected].
     *
     * Quando non si riesce a rileggere si risponde `true`: senza lettura non
     * c'e' niente su cui basare una correzione, e un secondo tentativo alla
     * cieca raddoppierebbe il blob invece di rimediare.
     */
    private fun fieldIs(ic: InputConnection, expected: String): Boolean {
        val extracted = runCatching { ic.getExtractedText(extractRequest(), 0) }.getOrNull()
        val whole = extracted?.text
        if (whole != null && extracted.partialStartOffset < 0) {
            return whole.toString() == expected
        }
        val before = runCatching { ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) }.getOrNull()
            ?: return true
        val after = runCatching { ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) }.getOrNull() ?: ""
        return before.toString() + after.toString() == expected
    }

    /**
     * `hintMaxChars`/`hintMaxLines` a zero: nessun limite suggerito, vogliamo
     * il campo intero. Sono suggerimenti, non garanzie — per questo chi legge
     * controlla comunque `partialStartOffset`.
     */
    private fun extractRequest() = ExtractedTextRequest().apply {
        token = 0
        hintMaxChars = 0
        hintMaxLines = 0
    }

    /**
     * Messaggi all'utente, per la via che funziona davvero.
     *
     * **Non** `Toast.makeText`: da Android 13 un toast viene soppresso se le
     * notifiche dell'app sono disattivate, e una tastiera quel permesso non lo
     * chiede mai. Il risultato era che la tastiera rispondeva — "scegli prima
     * un destinatario", "niente da decifrare" — e sullo schermo non compariva
     * niente: il guasto peggiore da diagnosticare, perche' sembra che il tasto
     * non faccia nulla.
     *
     * `KeyboardSwitcher.showToast` sceglie da se': toast vero fino ad Android
     * 12, e da li' in poi un avviso disegnato dentro la finestra della
     * tastiera, che nessun permesso puo' sopprimere. HeliBoard aveva gia'
     * risolto il problema per se'; qui si riusa la sua soluzione invece di
     * inventarne una seconda.
     */
    /**
     * Avvisi che **spiegano come funziona una cosa**: si mostrano la prima
     * volta e poi mai piu'.
     *
     * Ripetere una spiegazione a chi l'ha gia' letta non e' un servizio, e' un
     * ostacolo che compare sopra quello che sta facendo. Restano invece sempre
     * visibili gli avvisi che dicono che qualcosa **non** e' successo — quelli
     * non sono istruzioni, sono l'esito di questo tentativo, e non saperlo
     * significherebbe credere di aver mandato un messaggio che non e' partito.
     *
     * La chiave e' una stringa scelta a mano e non l'id della risorsa: gli id
     * cambiano a ogni compilazione, quindi un utente si ritroverebbe gli
     * avvisi daccapo a ogni aggiornamento.
     */
    private fun avvisoUnaVolta(ime: InputMethodService, chiave: String, resId: Int) {
        val prefs = ime.prefs()
        val pref = "cipher_visto_$chiave"
        if (prefs.getBoolean(pref, false)) return
        prefs.edit().putBoolean(pref, true).apply()
        toast(ime, resId)
    }

    private fun toast(ime: InputMethodService, resId: Int) {
        KeyboardSwitcher.getInstance().showToast(ime.getString(resId), true)
    }
}
