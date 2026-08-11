package helium314.keyboard.cipher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R
import java.util.Date

/**
 * Gestione contatti: elenco peer, fingerprint, etichette, conflitti di chiave,
 * propria identita'.
 *
 * Non esportata e non nel launcher: un'icona sarebbe un secondo marcatore
 * visibile del sistema, e non farebbe niente che una voce nelle impostazioni
 * non faccia gia'.
 */
class ContactsActivity : Activity() {

    /** Viva solo fra la richiesta della passphrase e il ritorno del selettore. */
    /**
     * A chi va il file scelto nel selettore che sta per aprirsi.
     *
     * Vive fra due Activity, quindi puo' essere azzerato da una ricreazione:
     * in quel caso non si cifra niente e non si indovina nessun destinatario.
     */
    private var destinatarioFile: ByteArray? = null

    private var passphraseInAttesa: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Non mostra plaintext, ma mostra fingerprint: roba che non deve
        // finire negli screenshot automatici dei Recenti.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        when (val state = CipherIdentity.ensureReady(this)) {
            CipherState.Ready -> renderContacts()
            CipherState.Locked -> renderNotice(getString(R.string.cipher_locked))
            is CipherState.Unavailable -> renderNotice(getString(R.string.cipher_unavailable))
            is CipherState.Unreadable -> renderUnreadable(state.part)
        }
    }

    // ========================================================================
    // Elenco
    // ========================================================================

    private fun renderContacts() {
        val root = column()

        root.addView(sectionTitle(getString(R.string.cipher_my_identity)))
        root.addView(caption(getString(R.string.cipher_my_identity_hint)))
        root.addView(fingerprintView(CipherCore.nativeMyFingerprint().orEmpty()))
        root.addView(Button(this).apply {
            setText(R.string.cipher_show_qr)
            setOnClickListener { showQr() }
        })
        root.addView(Button(this).apply {
            setText(R.string.cipher_backup_export)
            setOnClickListener { chiediPassphrase(esporta = true) }
        })
        root.addView(Button(this).apply {
            setText(R.string.cipher_backup_import)
            setOnClickListener { chiediPassphrase(esporta = false) }
        })

        root.addView(sectionTitle(getString(R.string.cipher_contacts)))

        val blob = CipherCore.nativeListPeers()
        val peers = blob?.let { PeerList.parse(it) }
        when {
            peers == null -> root.addView(caption(getString(R.string.cipher_unavailable)))
            peers.isEmpty() -> root.addView(caption(getString(R.string.cipher_no_contacts)))
            else -> peers.forEach { root.addView(peerRow(it)) }
        }

        setContentView(scroll(root))
    }

    private fun peerRow(peer: Peer): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        setOnClickListener { openPeer(peer) }

        val name = peer.label ?: getString(R.string.cipher_unnamed_peer)
        addView(TextView(this@ContactsActivity).apply {
            text = if (peer.verified) getString(R.string.cipher_sender_verified, name) else name
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        addView(fingerprintView(fingerprintOf(peer), selectable = false))
        addView(caption(getString(R.string.cipher_first_seen, formatDate(peer.firstSeenUnix))))
    }

    // ========================================================================
    // Scheda del peer
    // ========================================================================

    private fun openPeer(peer: Peer) {
        val name = peer.label ?: getString(R.string.cipher_unnamed_peer)
        // Vista propria invece di setMessage + setItems: un AlertDialog usa la
        // stessa area per il messaggio e per l'elenco, quindi impostarli
        // entrambi fa sparire le azioni — dialogo con il solo fingerprint e
        // nessun modo di fare niente. Verificato sul dispositivo.
        val contenuto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(caption(getString(R.string.cipher_peer_detail, fingerprintOf(peer))))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setView(contenuto)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun azione(testo: Int, quando: () -> Unit) {
            contenuto.addView(Button(this).apply {
                setText(testo)
                setOnClickListener {
                    dialog.dismiss()
                    quando()
                }
            })
        }
        azione(R.string.cipher_assign_label) { askLabel(peer) }
        azione(R.string.cipher_mark_verified) { markVerified(peer) }
        azione(R.string.cipher_file_send) { inviaFile(peer) }
        azione(R.string.cipher_burn) { chiediDiBruciare(peer) }
        azione(R.string.cipher_forget) { chiediDiDimenticare(peer) }
        dialog.show()
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
    private fun chiediDiBruciare(peer: Peer) {
        val nome = peer.label ?: getString(R.string.cipher_unnamed_peer)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cipher_burn_title, nome))
            .setMessage(R.string.cipher_burn_warning)
            // Come per "dimentica": l'azione distruttiva sul pulsante negativo,
            // perche' dove cade il pollice deve esserci cio' che non fa niente.
            .setNegativeButton(R.string.cipher_burn) { _, _ -> brucia(peer) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun brucia(peer: Peer) {
        val richiesta = CipherCore.nativeBurnConversation(peer.key, System.currentTimeMillis() / 1000)
        // Su disco SUBITO: da questo lato il rogo e' gia' avvenuto in memoria,
        // e un processo che muore adesso lascerebbe le chiavi al loro posto.
        CipherIdentity.persistKeyring(this)
        if (richiesta == null) {
            toast(R.string.cipher_unavailable)
            render()
            return
        }
        // La richiesta va consegnata a mano, come tutto il resto: qui non c'e'
        // nessun canale verso l'altra persona, e inventarne uno significherebbe
        // dare alla tastiera l'accesso a internet.
        copiaNegliAppunti(richiesta)
        toast(R.string.cipher_burn_done)
        render()
    }

    /**
     * Il blob di rogo negli appunti, da incollare nella chat.
     *
     * Non e' testo in chiaro — e' un blob cifrato come tutti gli altri — quindi
     * qui non serve il trattamento riservato ai plaintext.
     */
    private fun copiaNegliAppunti(blob: String) {
        val clip = android.content.ClipData.newPlainText(null, blob)
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(clip)
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
    private fun chiediDiDimenticare(peer: Peer) {
        val nome = peer.label ?: getString(R.string.cipher_unnamed_peer)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cipher_forget_title, nome))
            .setMessage(R.string.cipher_forget_warning)
            // L'azione distruttiva sul pulsante NEGATIVO, come per il conflitto
            // di chiave e per il reset: il posto dove cade il pollice dev'essere
            // quello che non cambia niente.
            .setNegativeButton(R.string.cipher_forget) { _, _ -> dimentica(peer) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun dimentica(peer: Peer) {
        if (CipherCore.nativeForgetPeer(peer.key) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        // Su disco subito: un keyring non persistito farebbe ricomparire il
        // contatto al riavvio, e l'utente crederebbe che il pulsante non
        // funzioni.
        CipherIdentity.persistKeyring(this)
        toast(R.string.cipher_forgotten)
        render()
    }

    /**
     * Manda un file cifrato a questo contatto.
     *
     * Il destinatario si sceglie **qui**, esplicitamente (decisione G4): questo
     * percorso parte da una schermata e non dalla tastiera, quindi non esiste
     * l'app di provenienza da cui dedurlo — e un file mandato alla persona
     * sbagliata non si ritira.
     */
    private fun inviaFile(peer: Peer) {
        destinatarioFile = peer.key
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        if (runCatching { startActivityForResult(intent, RICHIESTA_FILE) }.isFailure) {
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
            toast(getString(R.string.cipher_file_too_big, massimo / (1024 * 1024)))
            return
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.cipher_file_send)))
        }.onFailure { toast(R.string.cipher_unavailable) }
    }

    private fun askLabel(peer: Peer) {
        val input = EditText(this).apply {
            setText(peer.label.orEmpty())
            setSingleLine()
            // Il nome vecchio va SELEZIONATO, non solo mostrato. `setText`
            // lascia il cursore a inizio campo: chi scrive per rinominare si
            // ritrova il nome nuovo incollato PRIMA del vecchio ("GiuliaMarco")
            // e i cancellini non cancellano niente, perche' non c'e' niente a
            // sinistra. Sembra che la rinomina non funzioni, e invece non era
            // mai partita. Selezionando tutto, la prima lettera sostituisce.
            setSelection(0, text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cipher_assign_label)
            .setMessage(R.string.cipher_assign_label_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotEmpty()) assignLabel(peer, label)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun assignLabel(peer: Peer, label: String) {
        val result = CipherCore.IncomingResult()
        if (CipherCore.nativeAssignLabel(peer.key, label, result) != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        when (result.kind) {
            CipherCore.LABEL_ASSIGNED -> {
                CipherIdentity.persistKeyring(this)
                render()
            }
            // Il conflitto NON e' un fallimento: e' uno stato che richiede la
            // UI. Il core non ha modificato niente e non lo fara' finche' non
            // arriva una conferma esplicita.
            CipherCore.LABEL_CONFLICT -> showConflict(peer, label, result)
            else -> toast(R.string.cipher_unavailable)
        }
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
     *  - la conferma sta sul pulsante negativo, non su quello positivo. E'
     *    contro convenzione apposta: il posto dove cade il pollice deve essere
     *    quello che non cambia niente.
     */
    private fun showConflict(incoming: Peer, label: String, result: CipherCore.IncomingResult) {
        val existingKey = result.existingKey
        if (existingKey == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        val message = getString(
            R.string.cipher_conflict_body,
            label,
            result.existingFingerprint.orEmpty(),
            result.senderFingerprint ?: fingerprintOf(incoming),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.cipher_conflict_title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.cipher_conflict_keep, null)
            .setNegativeButton(R.string.cipher_conflict_replace) { _, _ ->
                confirmKeyChange(existingKey, incoming)
            }
            .show()
    }

    private fun confirmKeyChange(oldKey: ByteArray, incoming: Peer) {
        val code = CipherCore.nativeConfirmKeyChange(
            oldKey,
            incoming.key,
            System.currentTimeMillis() / 1000,
        )
        if (code != CipherCore.OK) {
            toast(R.string.cipher_unavailable)
            return
        }
        // replace_pinned azzera `verified`: una chiave nuova non e' stata
        // confrontata fuori banda, per definizione. L'utente dovra' rifarlo.
        CipherIdentity.persistKeyring(this)
        toast(R.string.cipher_key_replaced)
        render()
    }

    private fun markVerified(peer: Peer) {
        AlertDialog.Builder(this)
            .setTitle(R.string.cipher_mark_verified)
            .setMessage(getString(R.string.cipher_mark_verified_body, fingerprintOf(peer)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (CipherCore.nativeMarkVerified(peer.key) == CipherCore.OK) {
                    CipherIdentity.persistKeyring(this)
                    render()
                } else {
                    toast(R.string.cipher_unavailable)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
    private fun showQr() {
        val card = CipherCore.nativeIdentityCard()
        if (card == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        val side = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val bitmap = CipherQr.encode(card, side)

        val content = column().apply {
            if (bitmap != null) {
                addView(ImageView(this@ContactsActivity).apply {
                    setImageBitmap(bitmap)
                    // Nessun filtro nello scalare: interpolare i moduli
                    // sfoca i bordi, ed e' proprio quello che fa fallire la
                    // lettura.
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(side, side)
                })
            }
            addView(caption(getString(R.string.cipher_qr_hint)))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cipher_show_qr)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ========================================================================
    // Backup
    // ========================================================================

    /**
     * Chiede la passphrase, poi apre il selettore di file.
     *
     * La passphrase si chiede PRIMA del file per una ragione pratica: se la
     * si chiedesse dopo, l'utente sceglierebbe dove salvare e solo allora
     * scoprirebbe di doversi inventare qualcosa da ricordare — che e' il modo
     * migliore per farsi scegliere una passphrase pessima.
     */
    private fun chiediPassphrase(esporta: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(if (esporta) R.string.cipher_backup_export else R.string.cipher_backup_import)
            .setMessage(
                if (esporta) R.string.cipher_backup_export_hint
                else R.string.cipher_backup_import_hint
            )
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pass = input.text.toString()
                if (pass.isEmpty()) {
                    toast(R.string.cipher_backup_passphrase_vuota)
                    return@setPositiveButton
                }
                // Sopravvive fino al ritorno del selettore di file: e' una
                // finestra breve ma reale, ed e' il motivo per cui viene
                // azzerata appena usata.
                passphraseInAttesa = pass.toByteArray()
                apriSelettore(esporta)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun apriSelettore(esporta: Boolean) {
        // Storage Access Framework: l'utente sceglie il file, e l'app non
        // guadagna nessun permesso sullo storage. Un permesso di lettura su
        // tutto il disco per salvare un file sarebbe sproporzionato, e in
        // questo progetto anche contraddittorio.
        val intent = if (esporta) {
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "identita-tastiera.kcb")
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*" }
        }.apply { addCategory(Intent.CATEGORY_OPENABLE) }

        val richiesta = if (esporta) RICHIESTA_ESPORTA else RICHIESTA_IMPORTA
        if (runCatching { startActivityForResult(intent, richiesta) }.isFailure) {
            azzeraPassphrase()
            toast(R.string.cipher_unavailable)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            azzeraPassphrase()
            return
        }
        when (requestCode) {
            RICHIESTA_ESPORTA -> esegui(uri, esporta = true)
            RICHIESTA_IMPORTA -> esegui(uri, esporta = false)
            RICHIESTA_FILE -> cifraEInvia(uri)
        }
    }

    private fun esegui(uri: Uri, esporta: Boolean) {
        val pass = passphraseInAttesa
        if (pass == null) {
            toast(R.string.cipher_unavailable)
            return
        }
        try {
            if (esporta) {
                val blob = CipherIdentity.exportBackup(pass)
                if (blob == null) {
                    toast(R.string.cipher_unavailable)
                    return
                }
                val scritto = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(blob) } != null
                }.getOrDefault(false)
                toast(
                    if (scritto) R.string.cipher_backup_esportato
                    else R.string.cipher_unavailable
                )
            } else {
                val blob = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (blob == null) {
                    toast(R.string.cipher_unavailable)
                    return
                }
                confermaImport(blob, pass)
                return
            }
        } finally {
            if (esporta) azzeraPassphrase()
        }
    }

    /**
     * L'ultima conferma prima di sostituire l'identita'.
     *
     * Il pulsante che procede sta sul negativo, come per il conflitto di
     * chiave e per il reset: dove cade il pollice deve esserci cio' che non
     * cambia niente.
     */
    private fun confermaImport(blob: ByteArray, pass: ByteArray) {
        AlertDialog.Builder(this)
            .setTitle(R.string.cipher_backup_import)
            .setMessage(R.string.cipher_backup_import_conferma)
            .setPositiveButton(android.R.string.cancel) { _, _ -> azzeraPassphrase() }
            .setNegativeButton(R.string.cipher_backup_import_procedi) { _, _ ->
                val esito = CipherIdentity.importBackup(this, blob, pass)
                azzeraPassphrase()
                if (esito == CipherState.Ready) {
                    toast(R.string.cipher_backup_importato)
                    render()
                } else {
                    // Passphrase sbagliata e file manomesso danno lo stesso
                    // messaggio: distinguerli direbbe a chi prova le
                    // passphrase quando ne ha indovinata una.
                    toast(R.string.cipher_backup_non_aperto)
                }
            }
            .setOnCancelListener { azzeraPassphrase() }
            .show()
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
    private fun renderUnreadable(part: CipherPart) {
        val root = column()
        root.addView(sectionTitle(getString(R.string.cipher_unreadable_title)))
        root.addView(
            body(
                getString(
                    if (part == CipherPart.IDENTITY) R.string.cipher_unreadable_identity
                    else R.string.cipher_unreadable_keyring
                )
            )
        )
        root.addView(Button(this).apply {
            setText(R.string.cipher_reset_identity)
            setOnClickListener { askReset() }
        })
        setContentView(scroll(root))
    }

    private fun askReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cipher_reset_identity)
            // Tre conseguenze, tutte e tre scritte: l'identita' vecchia non
            // torna, i messaggi gia' ricevuti restano illeggibili, e ogni
            // contatto vedra' un cambio di chiave — cioe' lo stesso segnale che
            // il sistema usa per dire "qualcuno si sta spacciando per lui".
            .setMessage(R.string.cipher_reset_identity_body)
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.cipher_reset_identity_confirm) { _, _ ->
                CipherIdentity.resetIdentity(this)
                render()
            }
            .show()
    }

    // ========================================================================
    // Viste
    // ========================================================================

    private fun renderNotice(text: String) {
        val root = column()
        root.addView(body(text))
        setContentView(scroll(root))
    }

    private fun fingerprintOf(peer: Peer): String =
        CipherCore.nativeFingerprintOf(peer.key).orEmpty()

    private fun formatDate(unix: Long): String =
        DateFormat.getDateFormat(this).format(Date(unix * 1000))

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        setPadding(pad, pad, pad, pad)
    }

    private fun scroll(content: View): View = ScrollView(this).apply { addView(content) }

    private fun sectionTitle(text: String): View = TextView(this).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(16), 0, dp(4))
    }

    /**
     * Monospaziato e selezionabile: sono 24 caratteri che due persone si
     * leggono a voce o confrontano a schermo, e un font proporzionale rende
     * quel confronto piu' difficile di quanto serva.
     */
    /**
     * @param selectable un testo selezionabile **consuma il tocco**, e dentro
     * una riga cliccabile questo significa che il tocco non arriva alla riga.
     * Nell'elenco dei contatti il fingerprint occupa quasi tutta la riga:
     * lasciarlo selezionabile rendeva la riga apribile solo toccando il nome,
     * cioe' una striscia sottile in cima. Verificato sul dispositivo — sembrava
     * che i contatti non si aprissero affatto.
     *
     * Selezionabile resta dove serve davvero: nella scheda del contatto e nella
     * propria identita', dove il codice si copia per confrontarlo.
     */
    private fun fingerprintView(text: String, selectable: Boolean = true): View =
        TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextIsSelectable(selectable)
        }

    private fun body(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun caption(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.7f
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val RICHIESTA_ESPORTA = 1
        const val RICHIESTA_IMPORTA = 2
        const val RICHIESTA_FILE = 3
    }

}
