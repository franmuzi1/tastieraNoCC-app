package helium314.keyboard.cipher

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.Date

/**
 * Riceve testo da `ACTION_PROCESS_TEXT` (via 2), dallo share sheet (via 3) e
 * dal tasto "decifra" della toolbar (via 4), lo decifra e mostra il chiaro.
 *
 * E' l'UNICO posto in cui il testo decifrato compare a schermo. Tutte e
 * quattro le vie convergono qui apposta: i sei esiti di
 * `nativeHandleIncomingText` vanno gestiti allo stesso modo comunque sia
 * arrivato il blob, e due implementazioni divergerebbero al primo esito
 * aggiunto.
 */
class DecryptActivity : ComponentActivity() {

    companion object {
        /** Richiesta del selettore "dove salvo". */
        private const val RICHIESTA_SALVA = 1

    }

    /**
     * Il package dell'app da cui arriva il testo, risolto UNA volta per
     * intent.
     *
     * Una volta e non a richiesta perche' il gettone di [CipherHandoff] e' a
     * uso singolo: la prima chiamata lo consuma, e ogni chiamata successiva
     * troverebbe "nessuna attribuzione". E' esattamente cio' che succedeva al
     * pulsante "scrivi a questo contatto", che spariva perche' interrogava una
     * seconda volta.
     */
    private var appDiProvenienza: String = ""

    /** Contenuto decifrato, in attesa che l'utente decida se salvarlo. */
    private var fileInAttesa: ByteArray? = null
    private var nomeInAttesa: String? = null

    /**
     * Vero mentre il selettore "dove salvo" e' davanti a noi.
     *
     * Serve a non chiudersi in quel momento. Vedi [onStop]: questa finestra si
     * chiude appena esce di scena, e il selettore la fa uscire di scena — cosi'
     * il file veniva creato dal sistema e nessuno ci scriveva dentro. Un
     * allegato salvato di zero byte, con scritto "salvato". Trovato provando il
     * giro completo, non leggendo il codice.
     */
    private var inAttesaDelSelettore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prima di qualunque cosa che possa finire sullo schermo.
        // Blocca screenshot, registrazione schermo, e la miniatura che il
        // sistema salva per la schermata Recenti.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        // `savedInstanceState != null` significa che questa e' una rinascita:
        // il sistema ci aveva distrutti e ci rimette in piedi con lo stesso
        // intent. Non si rielabora: decifrare non e' di sola lettura — fa
        // avanzare la catena, fissa la chiave, e su una richiesta di rogo
        // brucia — quindi la seconda passata non e' una ripetizione innocua ma
        // un secondo effetto.
        //
        // La rotazione, che era il caso normale, non arriva nemmeno piu' qui:
        // la gestisce `configChanges` nel manifest. Restano i casi in cui
        // Android ci uccide comunque (memoria, "non mantenere le attivita'"), e
        // li' non si puo' ricostruire cosa mostrare: il chiaro non si salva in
        // un Bundle, che e' memoria del sistema e non nostra. Quindi si dice di
        // riaprire, che e' l'unica cosa vera che si possa dire.
        if (savedInstanceState != null) {
            showNotice(R.string.cipher_reopen_needed)
            return
        }
        handle(intent)
    }

    /**
     * Con `launchMode=singleTask` un secondo intent verso un'istanza gia' viva
     * NON passa da [onCreate]. Senza questo override la seconda decifratura
     * verrebbe ignorata e resterebbe a schermo il plaintext precedente: il
     * peggio dei due mondi, perche' l'utente crede di star leggendo il
     * messaggio nuovo. `noHistory` restringe la finestra ma non la chiude —
     * l'Activity vive finche' e' visibile.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handle(intent)
        }
    }

    private fun handle(intent: Intent) {
        // Sono qui: chi mi ha lanciata non deve ripiegare sull'avviso. E se ci
        // si e' arrivati proprio dall'avviso, quello ha finito — via anche il
        // blob che il suo PendingIntent teneva in `system_server`.
        CipherNotification.dismiss(this)
        appDiProvenienza = resolveCallerPackage()

        // Un allegato arriva come flusso, non come testo. Si guarda prima: se
        // c'e' uno stream, quello e' il contenuto, e cercare del testo
        // nell'intent porterebbe al massimo al nome del file.
        val stream = extractStream(intent)
        if (stream != null) {
            if (stream.scheme != ContentResolver.SCHEME_CONTENT) {
                return showNotice(R.string.cipher_uri_rejected)
            }
            handleFile(stream)
            return
        }

        val incoming = extractText(intent)
        if (incoming == null) {
            finish()
            return
        }

        when (val state = CipherIdentity.ensureReady(this)) {
            CipherState.Ready -> Unit
            CipherState.Locked -> return showNotice(R.string.cipher_locked)
            is CipherState.Unreadable -> return showUnreadable(state.part)
            is CipherState.Unavailable -> return showNotice(R.string.cipher_unavailable)
        }

        val result = CipherCore.IncomingResult()
        val code = CipherCore.nativeHandleIncomingText(
            appDiProvenienza,
            incoming.toString(),
            System.currentTimeMillis() / 1000,
            result,
        )

        when (code) {
            // Esito NORMALE, non un errore: la maggior parte del testo che
            // arriva qui non e' nostro, ed e' il caso per cui il sentinel
            // esiste.
            CipherCore.NOT_OUR_BLOB -> return showNotice(R.string.cipher_not_our_blob)
            CipherCore.UNSUPPORTED_VERSION -> return showNotice(R.string.cipher_version_too_new)
            CipherCore.TIER_UNSUPPORTED -> return showNotice(R.string.cipher_tier_unsupported)
            // Un solo messaggio per qualunque fallimento crypto. Il core non
            // distingue "tag non valido" da "chiave sbagliata" da "nonce
            // corrotto" apposta — la distinzione e' un canale che aiuta chi
            // attacca — e questo strato non deve reintrodurla travestita da
            // messaggi diversi.
            CipherCore.CRYPTO, CipherCore.FORMAT, CipherCore.DECODE ->
                return showNotice(R.string.cipher_cannot_decrypt)
            // L'abbiamo scritto noi ma non si trova piu' a chi: e' un esito
            // normale, non un guasto, e dirlo com'e' evita di far cercare un
            // problema che non c'e'.
            CipherCore.OWN_MESSAGE -> return showNotice(R.string.cipher_own_message_no_peer)
            CipherCore.OK -> Unit
            else -> return showNotice(R.string.cipher_unavailable)
        }

        // Il keyring puo' essere cambiato: un mittente mai visto e' stato
        // appena fissato, e il destinatario corrente per questa app puo'
        // essersi spostato. Senza questa riga il pin vivrebbe solo in memoria
        // e sparirebbe al riavvio, riaprendo a ogni reboot la finestra di MITM
        // che il pin serve a chiudere.
        //
        // Qui il pin e' gia' avvenuto in memoria e non si puo' annullare:
        // l'unica cosa onesta e' dire che potrebbe non sopravvivere al riavvio.
        if (!CipherIdentity.persistKeyring(this)) {
            Toast.makeText(this, R.string.cipher_keyring_not_saved, Toast.LENGTH_LONG).show()
        }

        // Decifrare stabilisce il destinatario: chi legge e poi risponde ha
        // gia' scelto leggendo, ed e' la leva che rende automatico il caso
        // dominante. Quella scelta vive in memoria dentro il core, quindi qui
        // va anche scritta su disco, altrimenti il primo riavvio del servizio
        // la cancella.
        // Vale anche per un messaggio nostro: chi rilegge cio' che ha mandato a
        // Marco sta guardando la conversazione con Marco, e li' la chiave da
        // usare e' la stessa.
        val letturaDa = result.senderKey
        if (letturaDa != null &&
            (result.kind == CipherCore.KIND_MESSAGE || result.kind == CipherCore.KIND_OWN_MESSAGE)
        ) {
            CipherRecipients.remember(this, appDiProvenienza, letturaDa)
        }

        when (result.kind) {
            // Un rogo non ha testo: si dice cosa e' successo e si chiude.
            CipherCore.KIND_BURNED -> showNotice(
                getString(R.string.cipher_burned_incoming, senderLine(result))
            )
            CipherCore.KIND_MESSAGE -> showMessage(result)
            CipherCore.KIND_OWN_MESSAGE -> showMessage(result, mio = true)
            CipherCore.KIND_IDENTITY_CARD -> showIdentityCard(result)
            // La propria chiave: si dice e basta. Nessuna azione da offrire —
            // non c'e' niente da nominare, niente da scegliere come
            // destinatario, e un pulsante qui suggerirebbe che ci sia.
            CipherCore.KIND_OWN_IDENTITY_CARD -> showNotice(
                getString(R.string.cipher_own_card, result.senderFingerprint.orEmpty())
            )
            else -> showNotice(R.string.cipher_unavailable)
        }
    }

    /**
     * L'URI dell'allegato, cosi' come arriva. Chi chiama tiene solo i
     * `content://` — vedi la guardia in [handle], e qui sotto il perche'.
     *
     * Questa Activity e' esportata, e deve esserlo: e' il modo in cui le altre
     * app ci passano testo e file. Ma cio' che ci passano lo apriamo con il
     * NOSTRO uid, e finche' lo schema non si guardava questo bastava a farne un
     * confused deputy: qualunque app poteva mandarci un `file://` che lei non
     * ha il permesso di leggere — dentro la nostra `filesDir`, per dire — e
     * farselo aprire da noi.
     *
     * Con `content://` il problema non si pone: non e' un percorso ma un
     * riferimento a un provider, e il permesso di lettura viaggia con l'intent
     * (`FLAG_GRANT_READ_URI_PERMISSION`). Chi ce lo manda ci sta dando accesso a
     * roba sua, che e' esattamente il caso che vogliamo servire; passarci
     * qualcosa di altrui non gli aggiunge nessun potere.
     *
     * Rifiutare il resto non toglie nessun flusso reale, ed e' il motivo per cui
     * si puo' fare: la chat condivide l'allegato dal proprio `FileProvider`,
     * "Apri con" di un file manager fa lo stesso, il selettore dei documenti
     * restituisce sempre un URI di `DocumentsProvider`. Da Android 7 un `file://`
     * che attraversa il confine fra due app fa terminare CHI LO MANDA con
     * `FileUriExposedException`, quindi non e' un caso in uso che si sta
     * togliendo: e' un caso che nessuna app conforme puo' piu' produrre, e a
     * costruirlo a mano resta solo chi lo fa apposta.
     *
     * Cosa NON era gia' aperto, verificato e non supposto: il chiaro non torna
     * mai al chiamante (`setResult` non c'e', vedi `neverReturnPlaintext`) e un
     * file che non e' nostro produce solo un avviso a schermo. Si chiude il
     * deputy, non una perdita in corso.
     */
    private fun extractStream(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    /**
     * Apre un allegato cifrato.
     *
     * Il chiaro **non** torna all'app che ce l'ha mandato, come per i
     * messaggi: si mostra qui, in una finestra `FLAG_SECURE`, e da qui esce
     * solo se l'utente lo salva apposta.
     */
    private fun handleFile(uri: Uri) {
        when (val state = CipherIdentity.ensureReady(this)) {
            CipherState.Ready -> Unit
            CipherState.Locked -> return showNotice(R.string.cipher_locked)
            is CipherState.Unreadable -> return showUnreadable(state.part)
            is CipherState.Unavailable -> return showNotice(R.string.cipher_unavailable)
        }

        val result = CipherCore.IncomingResult()
        val code = CipherFiles.decrypt(this, uri, System.currentTimeMillis() / 1000, result)
        when (code) {
            CipherCore.OK -> Unit
            CipherCore.UNSUPPORTED_VERSION -> return showNotice(R.string.cipher_version_too_new)
            CipherCore.TIER_UNSUPPORTED -> return showNotice(R.string.cipher_tier_unsupported)
            CipherCore.CRYPTO -> return showNotice(R.string.cipher_cannot_decrypt)
            CipherCore.OWN_MESSAGE -> return showNotice(R.string.cipher_own_message_no_peer)
            // Un file qualunque che non e' nostro finisce qui, ed e' l'esito
            // piu' comune: il filtro dell'intent prende tutti gli
            // `application/octet-stream`, perche' e' l'unica cosa su cui puo'
            // discriminare.
            else -> return showNotice(R.string.cipher_not_our_blob)
        }

        // Un mittente mai visto e' stato appena fissato: il keyring va scritto,
        // altrimenti il pin vive solo in memoria.
        if (!CipherIdentity.persistKeyring(this)) {
            Toast.makeText(this, R.string.cipher_keyring_not_saved, Toast.LENGTH_LONG).show()
        }
        showFile(result, mio = result.kind == CipherCore.KIND_OWN_FILE)
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
     * Il package di chi ci ha invocati, che il core usa per ricordare il
     * destinatario per app.
     *
     * Stringa vuota se non determinabile, e non un'ipotesi: attribuire il
     * messaggio all'app sbagliata sposterebbe il destinatario corrente di
     * un'altra conversazione, e la prossima cifratura andrebbe alla persona
     * sbagliata senza che nulla lo segnali.
     */
    private fun resolveCallerPackage(): String {
        // Il gettone lo puo' avere messo solo la nostra tastiera: e' generato a
        // caso in memoria, nello stesso processo, e consumato una volta sola.
        // Un'app esterna puo' scrivere qualunque extra ma non puo' indovinarlo.
        CipherHandoff.consume(intent.getStringExtra(CipherHandoff.extraName()))
            ?.let { return it }

        // `callingActivity` e' l'unica attribuzione che assegna il SISTEMA, e
        // c'e' solo se il chiamante ha usato startActivityForResult — cioe' per
        // ACTION_PROCESS_TEXT.
        //
        // NON si usa `getReferrer()`: restituisce `Intent.EXTRA_REFERRER` se
        // presente, cioe' un extra scritto dal chiamante. La documentazione
        // Android dice esplicitamente che non e' una funzione di sicurezza e
        // che le applicazioni possono falsificarlo. Una versione precedente di
        // questo codice lo usava sia come guardia sia come attribuzione, e la
        // guardia non fermava niente: bastava dichiararsi com.whatsapp per
        // spostare il destinatario della vera conversazione WhatsApp.
        //
        // Senza attribuzione si ritorna "", che il core tratta come "non
        // determinabile" e che disabilita la scelta implicita del
        // destinatario. E' il verso giusto in cui fallire.
        return callingActivity?.packageName.orEmpty()
    }

    // ========================================================================
    // Schermate
    //
    // Le stesse dei contatti e della scelta del destinatario: un dialogo con
    // gli angoli smussati, righe che si toccano, e i pezzi condivisi di
    // CipherUi. Prima erano pulsanti di sistema dentro una finestra a parte, e
    // arrivandoci dalla stessa app si vedeva.
    // ========================================================================

    /** Cosa c'e' a schermo adesso. Un solo stato: due schermate insieme non esistono. */
    private var schermo by mutableStateOf<Schermo?>(null)

    private sealed interface Schermo {
        /** Un avviso e basta: errori, esiti normali, roghi. */
        class Avviso(val testo: String) : Schermo

        class Messaggio(
            val chi: String,
            val mio: Boolean,
            val quando: String,
            val testo: String,
        ) : Schermo

        class Presentazione(
            val gia: Boolean,
            val impronta: String,
            /** Il nome gia' dato a questa chiave, se ce l'ha. */
            val nome: String?,
            val comeDestinatario: (() -> Unit)?,
            /** Apre i contatti sul dialogo del nome, per cambiarlo. */
            val rinomina: (() -> Unit)?,
        ) : Schermo

        class Allegato(
            val chi: String,
            val mio: Boolean,
            val quando: String,
            val dettaglio: String,
            val anteprima: Bitmap?,
            val nome: String,
        ) : Schermo

        class NonLeggibile(val part: CipherPart) : Schermo
    }

    private fun mostra(nuovo: Schermo) {
        schermo = nuovo
        setContent {
            Theme {
                when (val corrente = schermo) {
                    null -> Unit
                    is Schermo.Avviso -> Dialogo { Text(corrente.testo) }
                    is Schermo.Messaggio -> SchermoMessaggio(corrente)
                    is Schermo.Presentazione -> SchermoPresentazione(corrente)
                    is Schermo.Allegato -> SchermoAllegato(corrente)
                    is Schermo.NonLeggibile -> SchermoNonLeggibile(corrente.part)
                }
            }
        }
    }

    /**
     * L'involucro di tutte le schermate.
     *
     * Un pulsante solo, "OK", che chiude. Le azioni — copia, contatti, salva —
     * sono righe dentro il contenuto e non pulsanti in fondo: e' la stessa
     * forma della scheda contatto, e quella con tre pulsanti in fila era la
     * cosa che stonava di piu'.
     */
    @Composable
    private fun Dialogo(
        titolo: String? = null,
        contenuto: @Composable () -> Unit,
    ) {
        ThreeButtonAlertDialog(
            onDismissRequest = { finish() },
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            scrollContent = true,
            reducePadding = true,
            title = titolo?.let { { Text(it) } },
            content = contenuto,
        )
    }

    @Composable
    private fun SchermoMessaggio(dati: Schermo.Messaggio) {
        Dialogo(titolo = dati.chi) {
            Column {
                if (dati.mio) Didascalia(stringResource(R.string.cipher_own_message_note))
                Didascalia(stringResource(R.string.cipher_composed_at, dati.quando))
                Spacer(Modifier.height(10.dp))
                // Selezionabile: capita di voler prendere un pezzo del
                // messaggio — un indirizzo, un numero — senza copiarlo tutto.
                SelectionContainer {
                    Text(text = dati.testo, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(10.dp))
                Riquadro {
                    Voce(stringResource(R.string.cipher_copy)) { copyPlaintext(dati.testo) }
                    Divisore()
                    Voce(stringResource(R.string.cipher_contacts)) { apriContatti() }
                }
            }
        }
    }

    @Composable
    private fun SchermoPresentazione(dati: Schermo.Presentazione) {
        // Nessun segno di verifica qui: quello significa "confrontato di
        // persona" e una presentazione non lo prova. Titolo diverso se la
        // chiave era gia' nota, perche' "Nuovo contatto" su un contatto vecchio
        // e' semplicemente falso.
        Dialogo(
            titolo = stringResource(
                if (dati.gia) R.string.cipher_card_known else R.string.cipher_card_title
            )
        ) {
            Column {
                // Il nome prima dell'impronta: su un contatto gia' noto e'
                // l'unica cosa che dice CHI e'. Un'impronta da sola non si
                // riconosce, ed e' il motivo per cui "contatto gia' noto"
                // senza nome lasciava l'utente a chiedersi quale.
                dati.nome?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Impronta(dati.impronta, selezionabile = false)
                Spacer(Modifier.height(6.dp))
                Didascalia(
                    stringResource(
                        if (dati.gia) R.string.cipher_card_known_hint else R.string.cipher_card_pinned
                    )
                )
                Spacer(Modifier.height(10.dp))
                Riquadro {
                    // Cambiare nome da qui: chi rilegge una presentazione gia'
                    // nota spesso lo fa proprio perche' il nome non gli torna,
                    // e mandarlo a cercare i contatti era un passaggio in piu'
                    // nel momento in cui serve meno.
                    dati.rinomina?.let { azione ->
                        Voce(stringResource(R.string.cipher_rename)) { azione() }
                    }
                    // La presentazione NON sceglie il destinatario da sola: non
                    // e' autenticata, quindi chiunque potrebbe mandarne una e
                    // dirottare per chi cifri. Qui c'e' il gesto esplicito che
                    // lo fa, e solo se sappiamo in quale app siamo.
                    if (dati.comeDestinatario != null) {
                        Voce(stringResource(R.string.cipher_use_as_recipient)) {
                            dati.comeDestinatario.invoke()
                        }
                        Divisore()
                    }
                    Voce(stringResource(R.string.cipher_contacts)) { apriContatti() }
                }
            }
        }
    }

    @Composable
    private fun SchermoAllegato(dati: Schermo.Allegato) {
        Dialogo(titolo = dati.chi) {
            Column {
                if (dati.mio) Didascalia(stringResource(R.string.cipher_own_message_note))
                Didascalia(stringResource(R.string.cipher_composed_at, dati.quando))
                Spacer(Modifier.height(10.dp))
                Text(text = dati.dettaglio, style = MaterialTheme.typography.bodyLarge)
                if (dati.anteprima != null) {
                    Spacer(Modifier.height(10.dp))
                    Image(
                        bitmap = dati.anteprima.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Riquadro {
                    Voce(stringResource(R.string.cipher_file_save)) { chiediDoveSalvare(dati.nome) }
                    Divisore()
                    Voce(stringResource(R.string.cipher_contacts)) { apriContatti() }
                }
            }
        }
    }

    @Composable
    private fun SchermoNonLeggibile(part: CipherPart) {
        // Nessuna voce "ripara": l'unica uscita e' resetIdentity, che distrugge
        // l'identita' e fa vedere a ogni contatto un cambio di chiave. Qui
        // verrebbe premuta per togliersi il messaggio di torno. Sta nella UI
        // contatti, dietro una schermata che spieghi cosa si sta buttando via.
        Dialogo(titolo = stringResource(R.string.cipher_unreadable_title)) {
            Text(
                stringResource(
                    if (part == CipherPart.IDENTITY) R.string.cipher_unreadable_identity
                    else R.string.cipher_unreadable_keyring
                )
            )
        }
    }

    /**
     * @param mio quando il messaggio l'abbiamo scritto noi: allora la persona
     *   mostrata e' il **destinatario**, e chiamarla "mittente" sarebbe
     *   semplicemente falso.
     */
    private fun showMessage(result: CipherCore.IncomingResult, mio: Boolean = false) {
        val bytes = result.plaintext
        if (bytes == null) {
            showNotice(R.string.cipher_cannot_decrypt)
            return
        }
        // Il core consegna ByteArray e non String proprio per poterlo azzerare.
        // Per mostrarlo a schermo serve una CharSequence, quindi una copia non
        // azzerabile esiste comunque nella UI: la garanzia si ferma qui. Si
        // mitiga con FLAG_SECURE, la chiusura in onStop, e azzerando l'array.
        val testo = try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
        val chi = senderLine(result)
        mostra(
            Schermo.Messaggio(
                // Il segno di "confrontato di persona" non si mostra su un
                // messaggio nostro: li' non c'e' nessuna identita' da
                // verificare, l'abbiamo scritto noi.
                chi = when {
                    mio -> getString(R.string.cipher_own_message_to, chi)
                    result.verified == 1 -> getString(R.string.cipher_sender_verified, chi)
                    else -> chi
                },
                mio = mio,
                quando = formatTimestamp(result.sentAtUnix),
                testo = testo,
            )
        )
    }

    private fun showIdentityCard(result: CipherCore.IncomingResult) {
        val peer = result.senderKey
        val app = appDiProvenienza
        val gia = result.alreadyPinned == 1

        // Prima volta: si va dritti nei contatti a dargli un nome. E' il gesto
        // che serve subito dopo — una chiave senza nome e' un contatto che non
        // si riconosce, e "la chiave di Marco e' cambiata" e' una frase che
        // esiste solo se Marco ha un nome. Mostrare la card e aspettare che
        // l'utente trovi da solo la strada per i contatti era un passaggio in
        // piu' proprio nel momento in cui serve meno.
        if (!gia && peer != null) {
            runCatching { startActivity(ContactsActivity.intentNomina(this, peer)) }
                .onSuccess { finish(); return }
        }

        mostra(
            Schermo.Presentazione(
                gia = gia,
                impronta = result.senderFingerprint.orEmpty(),
                // Il nome lo tiene il core, non l'intent: `senderLabel` e'
                // vuoto per una chiave appena fissata e pieno per una gia'
                // nota, che e' esattamente la distinzione che serve qui.
                nome = result.senderLabel?.takeIf { it.isNotBlank() },
                rinomina = peer?.let { chiave ->
                    {
                        runCatching { startActivity(ContactsActivity.intentNomina(this, chiave)) }
                            .onSuccess { finish() }
                    }
                },
                comeDestinatario = if (peer != null && app.isNotEmpty()) {
                    {
                        if (CipherCore.nativeSetCurrentPeer(app, peer) == CipherCore.OK) {
                            CipherRecipients.remember(this, app, peer)
                            Toast.makeText(this, R.string.cipher_recipient_set, Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    null
                },
            )
        )
    }

    /**
     * Mostra un allegato decifrato.
     *
     * Il contenuto resta in memoria e in questa finestra: **non** viene scritto
     * da nessuna parte finche' l'utente non lo salva apposta. Un file decifrato
     * lasciato in Download e' un file che finisce nella galleria e nel backup
     * cloud — cioe' proprio dove la cifratura serviva a non farlo arrivare.
     *
     * @param mio come in [showMessage]: allora la persona mostrata e' il destinatario.
     */
    private fun showFile(result: CipherCore.IncomingResult, mio: Boolean = false) {
        val content = result.fileContent
        val name = result.fileName.orEmpty()
        if (content == null) {
            showNotice(R.string.cipher_cannot_decrypt)
            return
        }
        fileInAttesa = content
        nomeInAttesa = name

        // Le immagini si guardano qui dentro, sotto FLAG_SECURE. Il tipo lo
        // dichiara chi ha mandato il file e non fa fede: se il contenuto non e'
        // un'immagine, decodificarlo fallisce e resta la sola riga di
        // descrizione.
        val anteprima = if (result.fileMime.orEmpty().startsWith("image/")) {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(content, 0, content.size)
            }.getOrNull()
        } else {
            null
        }

        val chi = senderLine(result)
        mostra(
            Schermo.Allegato(
                chi = when {
                    mio -> getString(R.string.cipher_own_message_to, chi)
                    result.verified == 1 -> getString(R.string.cipher_sender_verified, chi)
                    else -> chi
                },
                mio = mio,
                quando = formatTimestamp(result.sentAtUnix),
                dettaglio = getString(R.string.cipher_file_detail, name, content.size / 1024),
                anteprima = anteprima,
                nome = name,
            )
        )
    }

    /**
     * Ingresso alla UI contatti, che e' dove si da' un nome a una chiave e la
     * si conferma di persona.
     *
     * Sta qui perche' e' il momento in cui serve: l'utente ha appena visto
     * comparire un contatto. `ContactsActivity` non e' nel launcher, quindi
     * senza un aggancio come questo si arriverebbe solo dalle impostazioni.
     */
    private fun apriContatti() {
        runCatching { startActivity(ContactsActivity.intent(this)) }
        finish()
    }

    private fun chiediDoveSalvare(name: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            // Il nome arriva da chi ha mandato il file: autenticato, non
            // credibile. Si tiene solo l'ultimo segmento, cosi' un nome con
            // `../` o un separatore non puo' proporre un percorso.
            putExtra(Intent.EXTRA_TITLE, name.substringAfterLast('/').ifEmpty { "allegato" })
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        inAttesaDelSelettore = true
        if (runCatching { startActivityForResult(intent, RICHIESTA_SALVA) }.isFailure) {
            inAttesaDelSelettore = false
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        inAttesaDelSelettore = false
        val uri = data?.data
        if (requestCode != RICHIESTA_SALVA || resultCode != RESULT_OK || uri == null) return
        val content = fileInAttesa ?: return
        val scritto = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(content) } != null
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (scritto) R.string.cipher_file_saved else R.string.cipher_unavailable,
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Il chiaro non sopravvive alla finestra che lo mostrava.
     *
     * Non e' una garanzia forte — la GC puo' averne gia' fatto copie, e la
     * `Bitmap` decodificata resta finche' non viene raccolta — ma e' la stessa
     * regola del resto del progetto: azzerare cio' che si puo' azzerare.
     */
    /**
     * Il chiaro non resta raggiungibile tornando indietro.
     *
     * Lo faceva `noHistory` nel manifest, ed era troppo: quel flag chiude la
     * finestra **anche** quando davanti ci mettiamo noi il selettore "dove
     * salvo", e allora al ritorno non c'e' piu' nessuno a scrivere il file.
     * Qui la stessa proprieta' si ottiene chiudendo a mano, con l'unica
     * eccezione che serve.
     *
     * `isChangingConfigurations`: una rotazione non e' un'uscita di scena.
     */
    override fun onStop() {
        super.onStop()
        if (!inAttesaDelSelettore && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        fileInAttesa?.fill(0)
        fileInAttesa = null
        nomeInAttesa = null
        super.onDestroy()
    }

    private fun showUnreadable(part: CipherPart) = mostra(Schermo.NonLeggibile(part))

    private fun showNotice(resId: Int) = showNotice(getString(resId))

    private fun showNotice(testo: String) = mostra(Schermo.Avviso(testo))

    private fun senderLine(result: CipherCore.IncomingResult): String =
        result.senderLabel ?: result.senderFingerprint ?: getString(R.string.cipher_unknown_sender)

    /**
     * Il timestamp e' autenticato — sta dentro il cifrato — ma NON verificabile:
     * nessuno puo' dimostrare che l'orologio del mittente fosse giusto. Si
     * mostra perche' e' cio' che rende visibile a un umano un blob
     * ripubblicato mesi dopo; non lo si usa mai per decidere qualcosa.
     */
    private fun formatTimestamp(unix: Long): String {
        val date = Date(unix * 1000)
        return "${DateFormat.getDateFormat(this).format(date)} ${DateFormat.getTimeFormat(this).format(date)}"
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
     */
    @Suppress("unused")
    private fun neverReturnPlaintext() = Unit

    /**
     * Copia del chiaro, solo se l'utente la chiede esplicitamente.
     *
     * Resta l'operazione piu' pericolosa dell'app, ed e' bene sapere perche'
     * ora e' collegata mentre prima era dead code.
     *
     * TRAPPOLA, ora chiusa: la clipboard di sistema viene letta dall'IME
     * predefinito — cioe' da QUESTA stessa tastiera, che tiene una cronologia
     * persistibile su disco. E' il motivo per cui la via 1 costa zero in
     * privacy, e in copia si ritorceva contro: il testo decifrato sarebbe
     * finito in quell'archivio, che e' l'opposto di cio' per cui il messaggio
     * era cifrato. `CipherClipboard.markSensitive` lo esclude, su tutte le
     * versioni di Android.
     *
     * `EXTRA_IS_SENSITIVE` (Android 13+) resta, ma copre un'altra cosa: nasconde
     * l'anteprima nel popup di sistema, cosi' il testo non compare davanti a chi
     * ti sta guardando. Non e' protezione — il testo e' comunque in clipboard
     * in chiaro, leggibile da qualunque app abbia il fuoco — ed e' il residuo
     * che questa funzione non puo' eliminare: da qui in poi il chiaro e' fuori
     * dal nostro perimetro.
     */
    private fun copyPlaintext(text: CharSequence) {
        // PRIMA di setPrimaryClip: il listener della cronologia puo' scattare
        // durante quella chiamata, e un marcatore messo dopo arriverebbe tardi.
        CipherClipboard.markSensitive(text)
        val clip = ClipData.newPlainText(null, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(this, R.string.cipher_copied, Toast.LENGTH_SHORT).show()
    }
}
