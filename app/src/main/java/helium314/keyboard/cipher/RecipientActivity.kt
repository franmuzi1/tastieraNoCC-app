package helium314.keyboard.cipher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R

/**
 * "A chi sto scrivendo?" — la scelta esplicita del destinatario.
 *
 * ## Perche' serve
 *
 * Il destinatario si stabilisce in tre modi: decifrando (chi legge e poi
 * risponde ha gia' scelto leggendo), per memoria dell'app, oppure
 * esplicitamente. Il terzo non era mai stato implementato, e questo lasciava
 * un vicolo cieco molto facile da imboccare: fissi la chiave di qualcuno dalla
 * sua presentazione, vai nella chat, premi il lucchetto — e non succede
 * niente, perche' per **quell'app** un destinatario non c'e' mai stato e non
 * c'era modo di sceglierlo.
 *
 * ## Perche' un'Activity e non un menu della tastiera
 *
 * Mostra i fingerprint, che non devono finire nella miniatura dei Recenti:
 * serve `FLAG_SECURE`, e una finestra dell'IME non ce l'ha. In cambio si perde
 * il fuoco sul campo, ed e' il motivo per cui questa schermata **non cifra**:
 * sceglie e basta, poi l'utente torna e preme il lucchetto.
 *
 * ## Da quale app
 *
 * Dal gettone di [CipherHandoff], come per [DecryptActivity]: e' l'unica
 * attribuzione che un'app esterna non puo' falsificare. Senza gettone non si
 * sceglie niente — attribuire la scelta all'app sbagliata significherebbe
 * dirottare per chi cifri, che e' il fallimento peggiore di questo sistema.
 */
internal class RecipientActivity : Activity() {

    private var appDiProvenienza: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)

        appDiProvenienza = CipherHandoff
            .consume(intent.getStringExtra(CipherHandoff.extraName()))
            .orEmpty()

        if (appDiProvenienza.isEmpty()) {
            avviso(getString(R.string.cipher_unavailable))
            return
        }
        when (CipherIdentity.ensureReady(this)) {
            CipherState.Ready -> render()
            CipherState.Locked -> avviso(getString(R.string.cipher_locked))
            else -> avviso(getString(R.string.cipher_unavailable))
        }
    }

    private fun render() {
        val blob = CipherCore.nativeListPeers()
        val peers = if (blob == null) emptyList() else PeerList.parse(blob).orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(titolo(getString(R.string.cipher_pick_recipient)))
        if (peers.isEmpty()) {
            // Non e' un errore: e' il primo avvio. Chi non ha ancora nessun
            // contatto deve sapere cosa fare, non leggere "vuoto".
            root.addView(nota(getString(R.string.cipher_pick_no_contacts)))
        } else {
            peers.forEach { peer: Peer -> root.addView(riga(peer)) }
        }
        root.addView(Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun riga(peer: Peer): View = Button(this).apply {
        val nome = peer.label ?: getString(R.string.cipher_unnamed_peer)
        // Il fingerprint sotto il nome, sempre: due contatti senza nome sono
        // distinguibili solo da quello, ed e' anche cio' che si confronta di
        // persona.
        text = nome + "\n" + CipherCore.nativeFingerprintOf(peer.key).orEmpty()
        setOnClickListener { scegli(peer) }
    }

    private fun scegli(peer: Peer) {
        if (CipherCore.nativeSetCurrentPeer(appDiProvenienza, peer.key) != CipherCore.OK) {
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        // Su disco, altrimenti la scelta muore al primo riavvio del servizio —
        // che e' il guasto che questo progetto ha gia' pagato una volta.
        CipherRecipients.remember(this, appDiProvenienza, peer.key)
        Toast.makeText(this, R.string.cipher_recipient_set, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun avviso(testo: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(nota(testo))
        root.addView(Button(this).apply {
            setText(android.R.string.ok)
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun titolo(testo: String): View = TextView(this).apply {
        text = testo
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, 0, 0, 24)
    }

    private fun nota(testo: String): View = TextView(this).apply {
        text = testo
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, 0, 0, 24)
    }

    companion object {
        /** Intent gia' pronto, con il gettone che dice da quale app si arriva. */
        fun intent(context: android.content.Context, appPackage: String): Intent =
            Intent(context, RecipientActivity::class.java).apply {
                putExtra(CipherHandoff.extraName(), CipherHandoff.issue(appPackage))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
