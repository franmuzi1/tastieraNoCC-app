package helium314.keyboard.cipher

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import android.widget.Toast
import helium314.keyboard.latin.R

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
     * Sostituisce il contenuto del campo con il blob cifrato.
     *
     * Il destinatario NON si indovina: lo decide il core in base all'app
     * (ultimo mittente letto, o memoria per package). Se non c'e', si chiede
     * invece di scegliere — cifrare per la persona sbagliata e' il fallimento
     * peggiore che questo sistema possa produrre.
     */
    fun encrypt(ime: InputMethodService) {
        if (!ready(ime)) return
        val ic = ime.currentInputConnection ?: return
        val text = readField(ime, ic) ?: return
        if (text.isEmpty()) {
            toast(ime, R.string.cipher_nothing_to_encrypt)
            return
        }

        // Da qui in poi il chiaro esiste anche come String, che e' immutabile e
        // non azzerabile: la garanzia del core si ferma al confine con
        // InputConnection, che parla CharSequence. L'array lo azzeriamo
        // comunque — e' la copia che vive piu' a lungo.
        val plaintext = text.toByteArray()
        val blob = try {
            CipherCore.nativeEncryptForApp(
                ime.currentInputEditorInfo?.packageName.orEmpty(),
                plaintext,
                System.currentTimeMillis() / 1000,
            )
        } finally {
            plaintext.fill(0)
        }

        if (blob == null) {
            toast(ime, R.string.cipher_no_recipient)
            return
        }
        replaceField(ic, text.length, blob)
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
    fun decrypt(ime: InputMethodService) {
        if (!ready(ime)) return
        val ic = ime.currentInputConnection ?: return
        val text = readField(ime, ic) ?: return
        if (text.isEmpty()) {
            toast(ime, R.string.cipher_nothing_to_decrypt)
            return
        }

        // ACTION_SEND e non un'azione nostra: DecryptActivity la gestisce gia'
        // per lo share sheet, e avere una via sola dentro l'Activity significa
        // una sola implementazione dei sei esiti.
        val intent = Intent(ime, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
    }

    /**
     * Inizializza il core e traduce un esito diverso da `Ready` in un
     * messaggio. Tre casi, tre messaggi: dire "non disponibile" a chi ha solo
     * il telefono bloccato manderebbe a cercare un guasto che non c'e'.
     */
    private fun ready(ime: InputMethodService): Boolean =
        when (CipherIdentity.ensureReady(ime)) {
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
     * Il campo intero, non solo cio' che sta prima del cursore: si cifra il
     * messaggio, non la parte scritta finora.
     */
    private fun readField(ime: InputMethodService, ic: InputConnection): String? {
        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) ?: ""
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) ?: ""
        if (before.length >= MAX_FIELD_CHARS || after.length >= MAX_FIELD_CHARS) {
            toast(ime, R.string.cipher_text_too_long)
            return null
        }
        return before.toString() + after.toString()
    }

    /**
     * Cancella quello che c'era e mette il blob.
     *
     * In un solo batch: senza, l'app vede il campo passare per lo stato vuoto,
     * e le app che reagiscono a ogni modifica (indicatore "sta scrivendo",
     * bozze salvate) registrerebbero uno stato intermedio che non e' mai
     * esistito per l'utente.
     */
    private fun replaceField(ic: InputConnection, previousLength: Int, blob: String) {
        ic.beginBatchEdit()
        // Il cursore puo' stare ovunque, quindi si cancella da entrambi i lati:
        // `previousLength` copre il caso peggiore per ciascun lato e i
        // caratteri che non ci sono vengono semplicemente ignorati.
        ic.deleteSurroundingText(previousLength, previousLength)
        ic.commitText(blob, 1)
        ic.endBatchEdit()
    }

    private fun toast(ime: InputMethodService, resId: Int) {
        Toast.makeText(ime, resId, Toast.LENGTH_SHORT).show()
    }
}
