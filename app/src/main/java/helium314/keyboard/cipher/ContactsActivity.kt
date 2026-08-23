package helium314.keyboard.cipher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.Date

/**
 * Gestione contatti: elenco peer, fingerprint, etichette, conflitti di chiave,
 * propria identita'.
 *
 * Non esportata e non nel launcher: un'icona sarebbe un secondo marcatore
 * visibile del sistema, e non farebbe niente che una voce nelle impostazioni
 * non faccia gia'.
 *
 * ## Perche' Compose e non viste costruite a mano
 *
 * Era l'unica schermata dell'app fatta di `LinearLayout` e `Button` di sistema,
 * e si vedeva: ci si arriva da un elenco di impostazioni in Material 3 e si
 * finiva su qualcosa che sembrava un'altra applicazione. Qui si usano lo stesso
 * [Theme] e gli stessi dialoghi delle impostazioni, quindi colori, tipografia e
 * angoli sono quelli — non una seconda approssimazione degli stessi.
 *
 * Cio' che NON e' cambiato, ed e' il motivo per cui questo file va letto prima
 * di toccarlo: `FLAG_SECURE`, l'ordine delle operazioni sul keyring, la
 * persistenza subito dopo ogni modifica, e la posizione dei pulsanti
 * distruttivi (vedi [DialogoDistruttivo]).
 */
class ContactsActivity : ComponentActivity() {

    /**
     * A chi va il file scelto nel selettore che sta per aprirsi.
     *
     * Vive fra due Activity, quindi puo' essere azzerato da una ricreazione:
     * in quel caso non si cifra niente e non si indovina nessun destinatario.
     */
    private var destinatarioFile: ByteArray? = null

    /** Viva solo fra la richiesta della passphrase e il ritorno del selettore. */
    private var passphraseInAttesa: ByteArray? = null

    /**
     * Cambia quando il keyring cambia, e basta a far rileggere tutto.
     *
     * Il keyring vive nel core, non qui: non c'e' niente di osservabile da
     * abbonare, quindi la ricomposizione la si chiede a mano dopo ogni scrittura
     * — che e' esattamente cio' che faceva `render()` prima.
     */
    private var revisione by mutableIntStateOf(0)

    private var dialogo by mutableStateOf<Dialogo>(Dialogo.Nessuno)

    /** L'impronta di una chiave: `Peer` porta la chiave, non la sua forma leggibile. */
    private fun improntaDi(peer: Peer): String =
        CipherCore.nativeFingerprintOf(peer.key).orEmpty()

    /** Vero fra la conferma della sostituzione e la scrittura del nome. */
    private var conflittoAccettato = false

    /**
     * L'app da cui e' arrivata la presentazione, dal gettone monouso.
     *
     * Un gettone e non un extra: gli extra li scrive chiunque, e da questo
     * valore dipende per chi si cifrera'.
     */
    private fun appDiProvenienza(): String? =
        CipherHandoff.consume(intent?.getStringExtra(CipherHandoff.extraName()))
            ?.takeIf { it.isNotEmpty() }

    /**
     * Si e' arrivati qui dal tasto "allegato" della tastiera.
     *
     * La schermata resta la stessa — l'elenco dei contatti — perche' il
     * destinatario di un file si sceglie **sempre a mano** (decisione G4) e
     * questo e' il posto dove le persone stanno gia'. Cambia solo cosa fa il
     * tocco.
     */
    private val perAllegato: Boolean by lazy { intent?.getBooleanExtra(EXTRA_ALLEGATO, false) == true }

    /**
     * Il selettore parte gia' su immagini e video.
     *
     * E' un filtro di comodita', non una restrizione: il contenitore cifrato
     * porta qualunque byte, e il tipo dichiarato dal mittente vale quanto la
     * sua parola. Serve solo a far trovare le foto dove uno le cerca.
     */
    private val soloMedia: Boolean by lazy { intent?.getBooleanExtra(EXTRA_SOLO_MEDIA, false) == true }

    /**
     * La chiave da nominare subito, se si arriva qui da una presentazione
     * appena fissata.
     *
     * Il nome e' il gesto che serve in quel momento: una chiave senza nome e'
     * un contatto che non si riconosce, e "la chiave di Marco e' cambiata" e'
     * una frase che esiste solo se Marco ha un nome.
     */
    private val daNominare: ByteArray? by lazy { intent?.getByteArrayExtra(EXTRA_NOMINA) }

    private val selettoreEsporta =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) azzeraPassphrase() else esporta(uri)
        }

    private val selettoreImporta =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) azzeraPassphrase() else importa(uri)
        }

    private val selettoreFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) destinatarioFile = null else cifraEInvia(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Non mostra plaintext, ma mostra fingerprint: roba che non deve
        // finire negli screenshot automatici dei Recenti. Prima di qualunque
        // contenuto, come nelle altre Activity che mostrano roba riservata.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nominaSubito()
        setContent {
            Theme {
                Surface {
                    Schermo()
                }
            }
        }
    }

    /**
     * Se si arriva da una presentazione appena fissata, il dialogo del nome e'
     * gia' aperto quando la schermata compare.
     *
     * Il peer si cerca nell'elenco invece di costruirlo dai byte: cosi' se per
     * qualunque ragione non e' nel keyring — un pin non persistito, una corsa
     * fra processi — non si apre un dialogo su una chiave che non c'e'.
     */
    private fun nominaSubito() {
        val chiave = daNominare ?: return
        val peer = CipherCore.nativeListPeers()
            ?.let { PeerList.parse(it) }
            ?.firstOrNull { it.key.contentEquals(chiave) }
            ?: return
        dialogo = Dialogo.Nome(peer)
    }

    // ========================================================================
    // Schermo
    // ========================================================================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Schermo() {
        // `ensureReady` e la lettura del keyring sono chiamate al core, quindi
        // si rifanno solo quando qualcosa e' cambiato davvero.
        val stato = remember(revisione) { CipherIdentity.ensureReady(this) }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { bordi ->
            Column(Modifier.padding(bordi)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.cipher_contacts)) },
                    windowInsets = WindowInsets(0),
                    navigationIcon = { BackButton { finish() } },
                )
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    when (stato) {
                        CipherState.Ready -> Contatti()
                        CipherState.Locked -> Avviso(stringResource(R.string.cipher_locked))
                        is CipherState.Unavailable -> Avviso(stringResource(R.string.cipher_unavailable))
                        is CipherState.Unreadable -> NonLeggibile(stato.part)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        Dialoghi()
    }

    @Composable
    private fun Contatti() {
        val impronta = remember(revisione) { CipherCore.nativeMyFingerprint().orEmpty() }
        val peers = remember(revisione) { CipherCore.nativeListPeers()?.let { PeerList.parse(it) } }

        if (perAllegato) {
            // Solo l'elenco: chi sta mandando un file non ha niente da fare con
            // la propria identita', il QR o i backup, e mostrarli qui allunga
            // la strada verso l'unica cosa che serve — la persona.
            Titolo(
                stringResource(
                    if (soloMedia) R.string.cipher_media_pick_recipient
                    else R.string.cipher_file_pick_recipient
                )
            )
            Riquadro { ElencoContatti(peers) }
            return
        }

        Titolo(stringResource(R.string.cipher_my_identity))
        Riquadro {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Impronta(impronta, selezionabile = true)
                Didascalia(
                    stringResource(R.string.cipher_my_identity_hint),
                    Modifier.padding(top = 6.dp),
                )
            }
            Divisore()
            Voce(stringResource(R.string.cipher_show_qr)) { dialogo = Dialogo.Qr }
            Divisore()
            Voce(stringResource(R.string.cipher_backup_export)) {
                dialogo = Dialogo.Passphrase(esporta = true)
            }
            Divisore()
            Voce(stringResource(R.string.cipher_backup_import)) {
                dialogo = Dialogo.Passphrase(esporta = false)
            }
        }

        Titolo(stringResource(R.string.cipher_contacts))
        Riquadro { ElencoContatti(peers) }
    }

    @Composable
    private fun ElencoContatti(peers: List<Peer>?) {
        when {
            peers == null -> Vuoto(stringResource(R.string.cipher_unavailable))
            peers.isEmpty() -> Vuoto(stringResource(R.string.cipher_no_contacts))
            else -> peers.forEachIndexed { indice, peer ->
                if (indice > 0) Divisore()
                RigaContatto(peer)
            }
        }
    }

    @Composable
    private fun RigaContatto(peer: Peer) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        Contatto(
            nome = if (peer.verified) stringResource(R.string.cipher_sender_verified, nome) else nome,
            impronta = fingerprintOf(peer),
            visto = stringResource(R.string.cipher_first_seen, formatDate(peer.firstSeenUnix)),
        ) {
            // Arrivando dal tasto "allegato" il tocco sceglie **il
            // destinatario del file**, non apre la scheda: chi ha premuto la
            // graffetta ha gia' detto cosa vuole fare, e fargli attraversare
            // un menu per ripeterlo e' un passaggio in piu' su un'azione che
            // ne ha gia' due (contatto, poi file).
            if (perAllegato) inviaFile(peer) else dialogo = Dialogo.Scheda(peer)
        }
    }

    // ========================================================================
    // Dialoghi
    // ========================================================================

    /**
     * Il dialogo aperto, se ce n'e' uno.
     *
     * `chiudi` chiude **solo se nel frattempo non e' cambiato niente**, e non e'
     * una precauzione teorica: [ThreeButtonAlertDialog] chiude sempre dopo la
     * conferma, quindi un'azione che ne apre un altro — dare un nome che finisce
     * in conflitto di chiave — se lo vedrebbe cancellare mezzo istante dopo
     * averlo aperto. Il conflitto e' proprio il caso che non deve poter passare
     * inosservato.
     */
    @Composable
    private fun Dialoghi() {
        val aperto = dialogo
        val chiudi = { if (dialogo === aperto) dialogo = Dialogo.Nessuno }
        when (val corrente = aperto) {
            Dialogo.Nessuno -> Unit
            is Dialogo.Scheda -> SchedaContatto(corrente.peer, chiudi)
            is Dialogo.Nome -> ChiediNome(corrente.peer, chiudi)
            is Dialogo.Conflitto -> Conflitto(corrente, chiudi)
            is Dialogo.Verifica -> Verifica(corrente.peer, chiudi)
            is Dialogo.Rogo -> ChiediDiBruciare(corrente.peer, chiudi)
            is Dialogo.Oblio -> ChiediDiDimenticare(corrente.peer, chiudi)
            is Dialogo.Sostituzione -> ChiediSeSostituire(corrente, chiudi)
            Dialogo.Qr -> Qr(chiudi)
            is Dialogo.Passphrase -> ChiediPassphrase(corrente.esporta, chiudi)
            is Dialogo.ConfermaImport -> ConfermaImport(corrente, chiudi)
            Dialogo.Reset -> ChiediReset(chiudi)
        }
    }

    @Composable
    private fun SchedaContatto(peer: Peer, chiudi: () -> Unit) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.cancel),
            reducePadding = true,
            title = { Text(nome) },
            content = {
                Column {
                    SelectionContainer {
                        Impronta(fingerprintOf(peer), selezionabile = false, Modifier.padding(horizontal = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Voce(stringResource(R.string.cipher_assign_label)) { dialogo = Dialogo.Nome(peer) }
                    Voce(stringResource(R.string.cipher_mark_verified)) { dialogo = Dialogo.Verifica(peer) }
                    Voce(stringResource(R.string.cipher_file_send)) { inviaFile(peer) }
                    Voce(stringResource(R.string.cipher_burn), distruttiva = true) {
                        dialogo = Dialogo.Rogo(peer)
                    }
                    Voce(stringResource(R.string.cipher_forget), distruttiva = true) {
                        dialogo = Dialogo.Oblio(peer)
                    }
                }
            },
        )
    }

    @Composable
    private fun ChiediNome(peer: Peer, chiudi: () -> Unit) {
        val iniziale = peer.label.orEmpty()
        // Il nome vecchio va SELEZIONATO, non solo mostrato: chi apre questo
        // dialogo per rinominare si aspetta che la prima lettera sostituisca.
        // Col cursore in fondo si ottiene "GiuliaMarco" e sembra che la
        // rinomina non funzioni, mentre non era mai partita. E' il motivo per
        // cui qui non si usa `TextInputDialog`, che il cursore lo mette in
        // fondo.
        var valore by remember {
            mutableStateOf(TextFieldValue(iniziale, TextRange(0, iniziale.length)))
        }
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { assegnaNome(peer, valore.text.trim()) },
            checkOk = { valore.text.isNotBlank() },
            title = { Text(stringResource(R.string.cipher_assign_label)) },
            content = {
                Column {
                    Text(stringResource(R.string.cipher_assign_label_hint))
                    Spacer(Modifier.height(8.dp))
                    val fuoco = remember { FocusRequester() }
                    // Il cursore parte QUI. Senza, il primo tocco andava speso
                    // per mettere il fuoco nel campo, e chi scriveva subito si
                    // ritrovava le lettere nella riga di composizione della
                    // tastiera: tre gesti per un nome, di cui uno per rimediare.
                    LaunchedEffect(Unit) { fuoco.requestFocus() }
                    OutlinedTextField(
                        value = valore,
                        onValueChange = { valore = it },
                        singleLine = true,
                        // Invio conferma, come OK. Dare un nome costava due
                        // tocchi — scrivere, poi cercare il pulsante — e questo
                        // dialogo compare proprio quando l'utente vuole finire
                        // in fretta, subito dopo aver conosciuto qualcuno.
                        //
                        // `Done` cambia anche l'icona del tasto invio, quindi
                        // il gesto si vede prima di provarlo. La condizione e'
                        // la stessa di `checkOk`: invio non deve poter
                        // confermare un nome che il pulsante rifiuta.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (valore.text.isNotBlank()) {
                                    assegnaNome(peer, valore.text.trim())
                                    chiudi()
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(fuoco),
                    )
                }
            },
        )
    }

    /**
     * "Safety number changed".
     *
     * E' il momento giusto per mostrarlo perche' e' l'unico in cui l'utente sta
     * dichiarando di chi si tratta: il pin, da solo, non puo' essere un
     * conflitto — quando arriva una chiave mai vista il sistema non ha modo di
     * sapere se sia un contatto nuovo o un contatto noto che ha cambiato
     * telefono. Lo sa solo l'utente, e lo dice qui.
     *
     * Quattro vincoli, tutti deliberati:
     *
     *  - si mostrano ENTRAMBI i fingerprint, quello gia' fissato e quello
     *    nuovo;
     *  - si spiegano le due letture possibili senza sceglierne una: il peer ha
     *    reinstallato l'app, oppure qualcuno si sta interponendo;
     *  - il default e' non fare niente. La vecchia chiave tiene il nome;
     *  - la sostituzione sta sul pulsante lontano dal pollice, non su quello
     *    che si preme per chiudere.
     */
    @Composable
    private fun Conflitto(conflitto: Dialogo.Conflitto, chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_conflict_title),
            corpo = stringResource(
                R.string.cipher_conflict_body,
                conflitto.nome,
                conflitto.improntaEsistente,
                conflitto.improntaNuova,
            ),
            azione = stringResource(R.string.cipher_conflict_replace),
            rinuncia = stringResource(R.string.cipher_conflict_keep),
            chiudi = chiudi,
        ) {
            confermaCambioChiave(conflitto.chiaveEsistente, conflitto.peer)
        }
    }

    @Composable
    private fun Verifica(peer: Peer, chiudi: () -> Unit) {
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { segnaVerificato(peer) },
            title = { Text(stringResource(R.string.cipher_mark_verified)) },
            content = { Text(stringResource(R.string.cipher_mark_verified_body, fingerprintOf(peer))) },
        )
    }

    /**
     * Brucia la conversazione, dopo conferma.
     *
     * L'avviso dice due cose diverse, e devono restare distinte: **da questo
     * telefono e' definitivo**, e sull'altro e' una richiesta che la sua app
     * puo' onorare o no. Presentarla come cancellazione garantita sarebbe la
     * bugia piu' facile da raccontare qui, e la piu' dannosa: qualcuno
     * potrebbe contarci per qualcosa di serio.
     */
    @Composable
    private fun ChiediDiBruciare(peer: Peer, chiudi: () -> Unit) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        // A forward secrecy accesa il rogo distrugge qualcosa che se n'era gia'
        // andato da solo: i messaggi si aprono una volta sola e la cronologia
        // non esiste. L'azione resta — butta comunque lo stato, e nasconderla
        // senza spiegare perche' fa sembrare l'app rotta — ma il testo lo dice,
        // altrimenti si promette una distruzione che e' gia' avvenuta.
        val corpo = if (CipherSettings.isForwardSecrecy(this)) {
            stringResource(R.string.cipher_burn_warning_fs) +
                "\n\n" + stringResource(R.string.cipher_burn_warning)
        } else {
            stringResource(R.string.cipher_burn_warning)
        }
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_burn_title, nome),
            corpo = corpo,
            azione = stringResource(R.string.cipher_burn),
            chiudi = chiudi,
        ) { brucia(peer) }
    }

    /**
     * Dimentica un contatto, dopo conferma.
     *
     * La conferma non e' cortesia. Cancellare un contatto **perde il pin**: il
     * prossimo messaggio da quella persona ricomparira' come mittente mai
     * visto e verra' rifissato in silenzio — che e' esattamente cio' che si
     * vedrebbe se qualcuno si stesse spacciando per lei. Chi lo fa deve
     * saperlo prima, non scoprirlo dopo.
     */
    /**
     * Stai per dare a questa chiave il nome di una chiave diversa che avevi
     * dimenticato.
     *
     * Non afferma che qualcuno stia mentendo: l'app conosce chiavi, non
     * persone, e "ha cambiato telefono" e "si sta spacciando per lui" sono
     * indistinguibili dall'interno. Dice di fermarsi e confrontare l'impronta
     * fuori banda, che e' l'unica cosa che scioglie il dubbio.
     */
    @Composable
    private fun ChiediSeSostituire(dati: Dialogo.Sostituzione, chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_substitute_title, dati.nomeVecchio),
            corpo = stringResource(R.string.cipher_substitute_warning, dati.nomeVecchio),
            azione = stringResource(R.string.cipher_substitute_ok),
            chiudi = chiudi,
        ) {
            conflittoAccettato = true
            assegnaNome(dati.peer, dati.nome)
        }
    }

    @Composable
    private fun ChiediDiDimenticare(peer: Peer, chiudi: () -> Unit) {
        val nome = peer.label ?: stringResource(R.string.cipher_unnamed_peer)
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_forget_title, nome),
            corpo = stringResource(R.string.cipher_forget_warning),
            azione = stringResource(R.string.cipher_forget),
            chiudi = chiudi,
        ) { dimentica(peer) }
    }

    /**
     * Il QR della propria identity card, per lo scambio di persona.
     *
     * E' l'unica via che chiude il MITM al primo contatto, che il TOFU da solo
     * non chiude: da qui in poi il pin protegge, ma quel primo scambio resta
     * scoperto se avviene solo attraverso il canale che si sta cercando di non
     * far leggere a nessuno.
     *
     * Sotto il codice resta la stringa: si legge ad alta voce se l'altro non
     * ha un lettore, ed e' anche l'unica cosa che si puo' fare se la
     * generazione fallisce.
     */
    @Composable
    private fun Qr(chiudi: () -> Unit) {
        val lato = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val bitmap = remember { CipherCore.nativeIdentityCard()?.let { CipherQr.encode(it, lato) } }
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            scrollContent = true,
            title = { Text(stringResource(R.string.cipher_show_qr)) },
            content = {
                Column {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentScale = ContentScale.Fit,
                            // Nessun filtro nello scalare: interpolare i moduli
                            // sfoca i bordi, ed e' proprio quello che fa fallire
                            // la lettura.
                            filterQuality = FilterQuality.None,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Didascalia(stringResource(R.string.cipher_qr_hint))
                }
            },
        )
    }

    /**
     * Chiede la passphrase, poi apre il selettore di file.
     *
     * La passphrase si chiede PRIMA del file per una ragione pratica: se la
     * si chiedesse dopo, l'utente sceglierebbe dove salvare e solo allora
     * scoprirebbe di doversi inventare qualcosa da ricordare — che e' il modo
     * migliore per farsi scegliere una passphrase pessima.
     */
    @Composable
    private fun ChiediPassphrase(esporta: Boolean, chiudi: () -> Unit) {
        var testo by remember { mutableStateOf("") }
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = {
                // Sopravvive fino al ritorno del selettore di file: e' una
                // finestra breve ma reale, ed e' il motivo per cui viene
                // azzerata appena usata. Cio' che si azzera e' questo array; la
                // `String` del campo di testo non e' azzerabile, ed e' un
                // residuo che c'era anche prima.
                passphraseInAttesa = testo.toByteArray()
                apriSelettore(esporta)
            },
            checkOk = { testo.isNotEmpty() },
            title = {
                Text(
                    stringResource(
                        if (esporta) R.string.cipher_backup_export else R.string.cipher_backup_import
                    )
                )
            },
            content = {
                Column {
                    Text(
                        stringResource(
                            if (esporta) R.string.cipher_backup_export_hint
                            else R.string.cipher_backup_import_hint
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testo,
                        onValueChange = { testo = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }

    /** L'ultima conferma prima di sostituire l'identita'. */
    @Composable
    private fun ConfermaImport(richiesta: Dialogo.ConfermaImport, chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_backup_import),
            corpo = stringResource(R.string.cipher_backup_import_conferma),
            azione = stringResource(R.string.cipher_backup_import_procedi),
            chiudi = { chiudi(); azzeraPassphrase() },
        ) { eseguiImport(richiesta.blob, richiesta.pass) }
    }

    @Composable
    private fun ChiediReset(chiudi: () -> Unit) {
        DialogoDistruttivo(
            titolo = stringResource(R.string.cipher_reset_identity),
            // Tre conseguenze, tutte e tre scritte: l'identita' vecchia non
            // torna, i messaggi gia' ricevuti restano illeggibili, e ogni
            // contatto vedra' un cambio di chiave — cioe' lo stesso segnale che
            // il sistema usa per dire "qualcuno si sta spacciando per lui".
            corpo = stringResource(R.string.cipher_reset_identity_body),
            azione = stringResource(R.string.cipher_reset_identity_confirm),
            chiudi = chiudi,
        ) {
            CipherIdentity.resetIdentity(this)
            ricarica()
        }
    }

    /**
     * Un dialogo che distrugge qualcosa.
     *
     * L'azione sta sul pulsante **piu' lontano dal pollice** e quella che non
     * fa niente su quello dove il pollice cade da solo. E' contro la
     * convenzione apposta, ed e' la stessa regola che valeva prima con
     * `setNegativeButton`: qui il posto naturale — in fondo a destra — e' preso
     * dalla rinuncia, e l'azione vive a sinistra, dove si arriva solo mirando.
     */
    @Composable
    private fun DialogoDistruttivo(
        titolo: String,
        corpo: String,
        azione: String,
        chiudi: () -> Unit,
        rinuncia: String = stringResource(android.R.string.cancel),
        esegui: () -> Unit,
    ) {
        ThreeButtonAlertDialog(
            onDismissRequest = chiudi,
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = rinuncia,
            neutralButtonText = azione,
            onNeutral = { chiudi(); esegui() },
            scrollContent = true,
            title = { Text(titolo) },
            content = { Text(corpo) },
        )
    }

    // ========================================================================
    // Azioni sul keyring
    // ========================================================================

    private fun assegnaNome(peer: Peer, nome: String) {
        if (nome.isEmpty()) return
        // Stai per dare a questa chiave un nome che avevi gia' dato a una chiave
        // DIVERSA, poi dimenticata. Non e' una prova di niente — l'app conosce
        // chiavi, non persone — ma e' la contraddizione che il pin avrebbe
        // segnalato con "la chiave e' cambiata", e che dimenticare aveva
        // disarmato. Si mostra prima di scrivere, e si puo' proseguire.
        val conflitto = CipherLapidi.conflitto(this, nome, improntaDi(peer))
        if (conflitto != null && !conflittoAccettato) {
            dialogo = Dialogo.Sostituzione(peer, nome, conflitto.nome)
            return
        }
        conflittoAccettato = false
        val result = CipherCore.IncomingResult()
        if (CipherCore.nativeAssignLabel(peer.key, nome, result) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        when (result.kind) {
            CipherCore.LABEL_ASSIGNED -> {
                if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
                ricarica()
            }
            // Il conflitto NON e' un fallimento: e' uno stato che richiede la
            // UI. Il core non ha modificato niente e non lo fara' finche' non
            // arriva una conferma esplicita.
            CipherCore.LABEL_CONFLICT -> {
                val esistente = result.existingKey
                if (esistente == null) {
                    toast(R.string.cipher_unavailable)
                    return
                }
                dialogo = Dialogo.Conflitto(
                    peer = peer,
                    nome = nome,
                    chiaveEsistente = esistente,
                    improntaEsistente = result.existingFingerprint.orEmpty(),
                    improntaNuova = result.senderFingerprint ?: fingerprintOf(peer),
                )
            }
            else -> toast(R.string.cipher_unavailable)
        }
    }

    private fun confermaCambioChiave(chiaveVecchia: ByteArray, peer: Peer) {
        val code = CipherCore.nativeConfirmKeyChange(
            chiaveVecchia,
            peer.key,
            System.currentTimeMillis() / 1000,
        )
        if (code != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        // replace_pinned azzera `verified`: una chiave nuova non e' stata
        // confrontata fuori banda, per definizione. L'utente dovra' rifarlo.
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        toast(R.string.cipher_key_replaced)
        ricarica()
    }

    private fun segnaVerificato(peer: Peer) {
        if (CipherCore.nativeMarkVerified(peer.key) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        ricarica()
    }

    private fun brucia(peer: Peer) {
        val richiesta = CipherCore.nativeBurnConversation(peer.key, System.currentTimeMillis() / 1000)
        // Su disco SUBITO: da questo lato il rogo e' gia' avvenuto in memoria,
        // e un processo che muore adesso lascerebbe le chiavi al loro posto.
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        // Tre esiti, non due. `null`: il rogo non e' avvenuto. Stringa vuota: e'
        // avvenuto ma la richiesta per l'altra persona non si e' potuta
        // costruire — dirgli "non disponibile" sarebbe falso su un'operazione
        // irreversibile che ha funzionato.
        if (richiesta == null) {
            toast(R.string.cipher_unavailable)
            ricarica()
            return
        }
        if (richiesta.isEmpty()) {
            toast(R.string.cipher_burn_done_no_request)
            ricarica()
            return
        }
        // La richiesta va consegnata a mano, come tutto il resto: qui non c'e'
        // nessun canale verso l'altra persona, e inventarne uno significherebbe
        // dare alla tastiera l'accesso a internet.
        copiaNegliAppunti(richiesta)
        toast(R.string.cipher_burn_done)
        ricarica()
    }

    private fun dimentica(peer: Peer) {
        // La lapide PRIMA: dopo `nativeForgetPeer` il nome non c'e' piu', e la
        // lapide senza nome non servirebbe a niente — e' il nome il ponte che
        // permette di riconoscere una sostituzione. Vedi CipherLapidi.
        CipherLapidi.ricorda(this, improntaDi(peer), peer.label.orEmpty())
        if (CipherCore.nativeForgetPeer(peer.key) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        // Su disco subito: un keyring non persistito farebbe ricomparire il
        // contatto al riavvio, e l'utente crederebbe che il pulsante non
        // funzioni.
        if (!CipherIdentity.persistKeyring(this)) toast(R.string.cipher_keyring_not_saved)
        toast(R.string.cipher_forgotten)
        ricarica()
    }

    /**
     * Il blob di rogo negli appunti, da incollare nella chat.
     *
     * Non e' testo in chiaro — e' un blob cifrato come tutti gli altri — quindi
     * qui non serve il trattamento riservato ai plaintext.
     */
    private fun copiaNegliAppunti(blob: String) {
        val clip = ClipData.newPlainText(null, blob)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    // ========================================================================
    // File
    // ========================================================================

    /**
     * Manda un file cifrato a questo contatto.
     *
     * Il destinatario si sceglie **qui**, esplicitamente (decisione G4): questo
     * percorso parte da una schermata e non dalla tastiera, quindi non esiste
     * l'app di provenienza da cui dedurlo — e un file mandato alla persona
     * sbagliata non si ritira.
     */
    private fun inviaFile(peer: Peer) {
        dialogo = Dialogo.Nessuno
        destinatarioFile = peer.key
        val tipi = if (soloMedia) arrayOf("image/*", "video/*") else arrayOf("*/*")
        if (runCatching { selettoreFile.launch(tipi) }.isFailure) {
            destinatarioFile = null
            toast(R.string.cipher_unavailable)
        }
    }

    private fun cifraEInvia(uri: Uri) {
        val peer = destinatarioFile ?: return
        destinatarioFile = null
        val sorgente = CipherFiles.describe(this, uri)
        val massimo = CipherFiles.maxBytes(this)
        // Il limite si dice PRIMA di cifrare. Scoprirlo dopo significherebbe
        // far aspettare l'utente per poi fallire, e su un telefono con poca
        // memoria fallire uccidendo il processo.
        if (sorgente.size > massimo) {
            toast(getString(R.string.cipher_file_too_big, massimo / (1024 * 1024)))
            return
        }
        val intent = CipherFiles.shareIntent(this, peer, uri, System.currentTimeMillis() / 1000)
        if (intent == null) {
            // NON "troppo grande": la dimensione e' gia' stata controllata qui
            // sopra, e dire la causa sbagliata manda a cercare nel posto
            // sbagliato.
            toast(R.string.cipher_file_not_prepared)
            return
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.cipher_file_send)))
        }.onFailure { toast(R.string.cipher_unavailable) }
    }

    // ========================================================================
    // Backup
    // ========================================================================

    private fun apriSelettore(esporta: Boolean) {
        // Storage Access Framework: l'utente sceglie il file, e l'app non
        // guadagna nessun permesso sullo storage. Un permesso di lettura su
        // tutto il disco per salvare un file sarebbe sproporzionato, e in
        // questo progetto anche contraddittorio.
        val esito = runCatching {
            if (esporta) selettoreEsporta.launch("identita-tastiera.kcb")
            else selettoreImporta.launch(arrayOf("*/*"))
        }
        if (esito.isFailure) {
            azzeraPassphrase()
            toast(R.string.cipher_unavailable)
        }
    }

    private fun esporta(uri: Uri) {
        val pass = passphraseInAttesa
        if (pass == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        try {
            val blob = CipherIdentity.exportBackup(pass)
            if (blob == null) {
                toast(R.string.cipher_unavailable)
                return
            }
            val scritto = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(blob) } != null
            }.getOrDefault(false)
            toast(if (scritto) R.string.cipher_backup_esportato else R.string.cipher_unavailable)
        } finally {
            azzeraPassphrase()
        }
    }

    private fun importa(uri: Uri) {
        val pass = passphraseInAttesa
        if (pass == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        val blob = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (blob == null) {
            azzeraPassphrase()
            toast(R.string.cipher_unavailable)
            return
        }
        // La passphrase NON si azzera qui: serve ancora, dopo la conferma.
        dialogo = Dialogo.ConfermaImport(blob, pass)
    }

    private fun eseguiImport(blob: ByteArray, pass: ByteArray) {
        val esito = CipherIdentity.importBackup(this, blob, pass)
        azzeraPassphrase()
        if (esito == CipherState.Ready) {
            toast(R.string.cipher_backup_importato)
            ricarica()
        } else {
            // Passphrase sbagliata e file manomesso danno lo stesso messaggio:
            // distinguerli direbbe a chi prova le passphrase quando ne ha
            // indovinata una.
            toast(R.string.cipher_backup_non_aperto)
        }
    }

    private fun azzeraPassphrase() {
        passphraseInAttesa?.fill(0)
        passphraseInAttesa = null
    }

    override fun onDestroy() {
        // Se l'utente esce a meta' flusso la passphrase non deve restare in
        // heap ad aspettare la GC.
        azzeraPassphrase()
        super.onDestroy()
    }

    // ========================================================================
    // Identita' non leggibile
    // ========================================================================

    /**
     * L'unica uscita da [CipherState.Unreadable], e sta qui e non dentro
     * `DecryptActivity` apposta: la' comparirebbe davanti a un utente che sta
     * solo cercando di leggere un messaggio, e verrebbe premuta per togliersi
     * di torno l'errore.
     */
    @Composable
    private fun NonLeggibile(part: CipherPart) {
        Titolo(stringResource(R.string.cipher_unreadable_title))
        Riquadro {
            Text(
                text = stringResource(
                    if (part == CipherPart.IDENTITY) R.string.cipher_unreadable_identity
                    else R.string.cipher_unreadable_keyring
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
            Divisore()
            Voce(stringResource(R.string.cipher_reset_identity), distruttiva = true) {
                dialogo = Dialogo.Reset
            }
        }
    }

    // ========================================================================
    // Utilita'
    // ========================================================================

    private fun ricarica() {
        dialogo = Dialogo.Nessuno
        revisione++
    }

    private fun fingerprintOf(peer: Peer): String =
        CipherCore.nativeFingerprintOf(peer.key).orEmpty()

    private fun formatDate(unix: Long): String =
        DateFormat.getDateFormat(this).format(Date(unix * 1000))

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Quale dialogo e' aperto.
     *
     * Uno stato solo invece di una manciata di booleani: due dialoghi aperti
     * insieme non e' uno stato rappresentabile, e quello che si chiude
     * aprendone un altro (la scheda del contatto verso le sue azioni) e' un
     * passaggio esplicito.
     */
    private sealed interface Dialogo {
        data object Nessuno : Dialogo
        data class Scheda(val peer: Peer) : Dialogo
        data class Nome(val peer: Peer) : Dialogo
        data class Verifica(val peer: Peer) : Dialogo
        data class Rogo(val peer: Peer) : Dialogo
        data class Oblio(val peer: Peer) : Dialogo

        /** Stesso nome, chiave diversa da una che avevi dimenticato. */
        data class Sostituzione(
            val peer: Peer,
            val nome: String,
            val nomeVecchio: String,
        ) : Dialogo
        data object Qr : Dialogo
        data class Passphrase(val esporta: Boolean) : Dialogo
        data object Reset : Dialogo

        // Classi normali e non `data`: portano array, e un `data class` con
        // dentro un `ByteArray` genera un `equals` che confronta i riferimenti.
        class Conflitto(
            val peer: Peer,
            val nome: String,
            val chiaveEsistente: ByteArray,
            val improntaEsistente: String,
            val improntaNuova: String,
        ) : Dialogo

        class ConfermaImport(val blob: ByteArray, val pass: ByteArray) : Dialogo
    }

    companion object {
        private const val EXTRA_ALLEGATO = "cipher_allegato"
        private const val EXTRA_SOLO_MEDIA = "cipher_solo_media"
        private const val EXTRA_NOMINA = "cipher_nomina"

        /**
         * La schermata in modalita' "scegli a chi mandare il file".
         *
         * Un extra e non un'Activity nuova: e' lo stesso elenco, con lo stesso
         * `FLAG_SECURE` e lo stesso keyring, e duplicarlo significherebbe due
         * posti da tenere allineati per una differenza che sta in una riga.
         */
        fun intentAllegato(context: android.content.Context, soloMedia: Boolean): Intent =
            Intent(context, ContactsActivity::class.java).apply {
                putExtra(EXTRA_ALLEGATO, true)
                putExtra(EXTRA_SOLO_MEDIA, soloMedia)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun intent(context: android.content.Context): Intent =
            Intent(context, ContactsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** L'elenco con il dialogo "dai un nome" gia' aperto su [peer]. */
        fun intentNomina(context: android.content.Context, peer: ByteArray): Intent =
            intent(context).apply { putExtra(EXTRA_NOMINA, peer) }
    }
}

/**
 * L'anteprima della schermata, con dati finti.
 *
 * Serve a qualcosa di preciso: la schermata vera ha `FLAG_SECURE`, quindi non
 * si puo' fotografare — la cattura viene nera, ed e' il comportamento voluto.
 * Senza questa anteprima l'unico modo di guardare come viene e' guardare il
 * telefono con gli occhi.
 *
 * I fingerprint qui dentro sono inventati e non provengono da nessun keyring.
 *
 * Non e' `private` perche' `PreviewActivity` la cerca per nome via reflection, e
 * la variante `debug` passa da R8: senza un riferimento, sparisce.
 */
@Preview
@Composable
internal fun AnteprimaContatti() {
    // Il tema del dispositivo e non uno fisso: chiaro e scuro vanno guardati
    // tutti e due, ed e' l'unico modo di vedere il secondo senza ricompilare.
    Theme {
        Surface {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Titolo("La tua identita'")
                Riquadro {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Impronta("ybnd rfg8 ejkm cpqx ot1u wisz", selezionabile = true)
                        Didascalia(
                            "Questo codice ti identifica. Leggilo a voce a chi vuoi contattare: " +
                                "se combacia, nessuno si e' interposto.",
                            Modifier.padding(top = 6.dp),
                        )
                    }
                    Divisore()
                    Voce("Mostra il codice QR") { }
                    Divisore()
                    Voce("Salva una copia dell'identita'") { }
                    Divisore()
                    Voce("Ripristina da una copia") { }
                }
                Titolo("Contatti")
                Riquadro {
                    Contatto("Giulia ✓", "8ejk mcpq xot1 uwis zybn drfg", "Visto la prima volta il 3 mar 2026") { }
                    Divisore()
                    Contatto("Senza nome", "qxot 1uwi szyb ndrf g8ej kmcp", "Visto la prima volta il 11 ago 2026") { }
                }
                Titolo("Azioni sul contatto")
                Riquadro {
                    Voce("Dai un nome") { }
                    Divisore()
                    Voce("Brucia la conversazione", distruttiva = true) { }
                    Divisore()
                    Vuoto("Nessun contatto. Ne comparira' uno appena ricevi un messaggio cifrato.")
                }
                Avviso("Cifratura non disponibile")
            }
        }
    }
}
