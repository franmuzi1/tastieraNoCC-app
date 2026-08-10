package helium314.keyboard.cipher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
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
        addView(fingerprintView(fingerprintOf(peer)))
        addView(caption(getString(R.string.cipher_first_seen, formatDate(peer.firstSeenUnix))))
    }

    // ========================================================================
    // Scheda del peer
    // ========================================================================

    private fun openPeer(peer: Peer) {
        val name = peer.label ?: getString(R.string.cipher_unnamed_peer)
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(getString(R.string.cipher_peer_detail, fingerprintOf(peer)))
            .setPositiveButton(R.string.cipher_assign_label) { _, _ -> askLabel(peer) }
            .setNeutralButton(R.string.cipher_mark_verified) { _, _ -> markVerified(peer) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askLabel(peer: Peer) {
        val input = EditText(this).apply {
            setText(peer.label.orEmpty())
            setSingleLine()
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
    private fun fingerprintView(text: String): View = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextIsSelectable(true)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // TODO: il QR. Di persona e' l'unica cosa che chiude il MITM al primo
    //   contatto, che il TOFU da solo non chiude, quindi va reso facile da
    //   raggiungere e non sepolto. Mostrarlo non costa permessi; SCANSIONARLO
    //   richiede CAMERA, e allora: permesso a runtime, chiesto solo
    //   all'apertura dello scanner, mai all'installazione. Il fork non deve
    //   guadagnare permessi passivi — non averne e' la sua proprieta'
    //   principale.
}
