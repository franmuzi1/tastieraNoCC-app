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
     * visibile e' la UI contatti, che non c'e' ancora; questo e' il gesto
     * veloce, non l'unico previsto.
     */
    fun insertIdentityCard(ime: InputMethodService) {
        if (!ready(ime)) return
        val ic = ime.currentInputConnection ?: return
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
        toast(ime, R.string.cipher_card_inserted)
    }

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
            val ic = ime.currentInputConnection
            if (ic != null) {
                // Distinzione che conta: `null` qui vuol dire "campo troppo
                // lungo, gia' segnalato", non "campo vuoto". Ricadere sugli
                // appunti in quel caso decifrerebbe una cosa diversa da quella
                // che l'utente stava guardando.
                val field = readField(ime, ic) ?: return null
                if (field.isNotEmpty()) return field
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
