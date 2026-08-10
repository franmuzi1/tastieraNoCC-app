package helium314.keyboard.cipher

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R
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
class DecryptActivity : Activity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prima di qualunque cosa che possa finire sullo schermo.
        // Blocca screenshot, registrazione schermo, e la miniatura che il
        // sistema salva per la schermata Recenti.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
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
        appDiProvenienza = resolveCallerPackage()
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
            CipherCore.OK -> Unit
            else -> return showNotice(R.string.cipher_unavailable)
        }

        // Il keyring puo' essere cambiato: un mittente mai visto e' stato
        // appena fissato, e il destinatario corrente per questa app puo'
        // essersi spostato. Senza questa riga il pin vivrebbe solo in memoria
        // e sparirebbe al riavvio, riaprendo a ogni reboot la finestra di MITM
        // che il pin serve a chiudere.
        CipherIdentity.persistKeyring(this)

        when (result.kind) {
            CipherCore.KIND_MESSAGE -> showMessage(result)
            CipherCore.KIND_IDENTITY_CARD -> showIdentityCard(result)
            else -> showNotice(R.string.cipher_unavailable)
        }
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
    // ========================================================================

    private fun showMessage(result: CipherCore.IncomingResult) {
        val bytes = result.plaintext
        if (bytes == null) {
            showNotice(R.string.cipher_cannot_decrypt)
            return
        }
        // Il core consegna ByteArray e non String proprio per poterlo azzerare.
        // Per mostrarlo a schermo serve una CharSequence, quindi una copia non
        // azzerabile esiste comunque nella UI: la garanzia si ferma qui. Si
        // mitiga con FLAG_SECURE, noHistory, e azzerando l'array subito.
        val testo = try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }

        val root = screen()
        root.addView(header(senderLine(result), result.verified == 1))
        root.addView(caption(getString(R.string.cipher_composed_at, formatTimestamp(result.sentAtUnix))))
        root.addView(body(testo))
        root.addView(copyButton(testo))
        root.addView(contactsButton())
        root.addView(closeButton())
        setContentView(root)
    }

    private fun showIdentityCard(result: CipherCore.IncomingResult) {
        val root = screen()
        val gia = result.alreadyPinned == 1
        // Nessun segno di verifica qui: quello significa "confrontato di
        // persona" e una presentazione non lo prova. Titolo diverso se la
        // chiave era gia' nota, perche' "Nuovo contatto" su un contatto vecchio
        // e' semplicemente falso.
        root.addView(
            header(
                getString(
                    if (gia) R.string.cipher_card_known else R.string.cipher_card_title
                ),
                false,
            )
        )
        root.addView(body(result.senderFingerprint.orEmpty()))
        root.addView(
            caption(
                getString(
                    if (gia) R.string.cipher_card_known_hint else R.string.cipher_card_pinned
                )
            )
        )

        // La presentazione NON sceglie il destinatario da sola: non e'
        // autenticata, quindi chiunque potrebbe mandarne una e dirottare per
        // chi cifri. Qui c'e' il gesto esplicito che lo fa, e solo se sappiamo
        // in quale app siamo.
        val peer = result.senderKey
        val app = appDiProvenienza
        if (peer != null && app.isNotEmpty()) {
            root.addView(Button(this).apply {
                setText(R.string.cipher_use_as_recipient)
                setOnClickListener {
                    if (CipherCore.nativeSetCurrentPeer(app, peer) == CipherCore.OK) {
                        Toast.makeText(
                            this@DecryptActivity,
                            R.string.cipher_recipient_set,
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    } else {
                        Toast.makeText(
                            this@DecryptActivity,
                            R.string.cipher_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            })
        }

        root.addView(contactsButton())
        root.addView(closeButton())
        setContentView(scroll(root))
    }

    /**
     * Ingresso alla UI contatti, che e' dove si da' un nome a questa chiave e
     * la si conferma di persona.
     *
     * Sta qui perche' e' il momento in cui serve: l'utente ha appena visto
     * comparire un contatto. `ContactsActivity` non e' nel launcher, quindi
     * senza un aggancio come questo si arriverebbe solo dalle impostazioni.
     */
    private fun contactsButton(): View = Button(this).apply {
        setText(R.string.cipher_contacts)
        setOnClickListener {
            runCatching { startActivity(Intent(this@DecryptActivity, ContactsActivity::class.java)) }
            finish()
        }
    }

    private fun showUnreadable(part: CipherPart) {
        val root = screen()
        root.addView(header(getString(R.string.cipher_unreadable_title), false))
        root.addView(
            body(
                getString(
                    if (part == CipherPart.IDENTITY) R.string.cipher_unreadable_identity
                    else R.string.cipher_unreadable_keyring
                )
            )
        )
        // Nessun pulsante "ripara": l'unica uscita e' resetIdentity, che
        // distrugge l'identita' e fa vedere a ogni contatto un cambio di
        // chiave. Un pulsante qui verrebbe premuto per togliersi il messaggio
        // di torno. Va nella UI contatti, dietro una schermata che spieghi cosa
        // si sta buttando via.
        root.addView(closeButton())
        setContentView(root)
    }

    private fun showNotice(resId: Int) {
        val root = screen()
        root.addView(body(getString(resId)))
        root.addView(closeButton())
        setContentView(root)
    }

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

    private fun scroll(content: View): View = ScrollView(this).apply { addView(content) }

    private fun screen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        setPadding(pad, pad, pad, pad)
    }

    private fun header(text: String, verified: Boolean): View = TextView(this).apply {
        this.text = if (verified) getString(R.string.cipher_sender_verified, text) else text
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun caption(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.7f
        setPadding(0, dp(2), 0, dp(12))
    }

    private fun body(text: String): View = ScrollView(this).apply {
        addView(TextView(this@DecryptActivity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextIsSelectable(true)
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
        ).apply { weight = 1f }
    }

    private fun closeButton(): View = Button(this).apply {
        setText(android.R.string.ok)
        setOnClickListener { finish() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    private fun copyButton(text: CharSequence): View = Button(this).apply {
        setText(R.string.cipher_copy)
        setOnClickListener { copyPlaintext(text) }
    }
}
