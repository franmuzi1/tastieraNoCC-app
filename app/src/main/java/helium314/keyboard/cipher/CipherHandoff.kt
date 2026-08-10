package helium314.keyboard.cipher

import java.security.SecureRandom

/**
 * Passaggio di consegne fra la tastiera e [DecryptActivity], per dire in quale
 * app stava scrivendo l'utente.
 *
 * ## Perche' non basta un extra dell'intent
 *
 * `DecryptActivity` e' esportata — deve esserlo, per ricevere il testo dalle
 * altre app — quindi qualunque applicazione puo' mandarle un intent con gli
 * extra che preferisce.
 *
 * Il tentativo precedente usava `getReferrer()` come guardia: "onoro l'extra
 * col package dell'editor solo se a chiamarmi e' stata questa stessa app".
 * **Non funzionava.** `Activity.getReferrer()` restituisce, con precedenza su
 * tutto il resto, `Intent.EXTRA_REFERRER` se presente — cioe' un extra scritto
 * dal chiamante. La documentazione Android lo dice esplicitamente: non e' una
 * funzione di sicurezza e le applicazioni possono falsificarlo.
 *
 * Conseguenza concreta di quel buco: un'app senza alcun permesso poteva
 * mandare un intent dichiarandosi `com.whatsapp`, e spostare il destinatario
 * corrente della vera conversazione WhatsApp sulla propria chiave — senza che
 * l'utente aprisse mai WhatsApp.
 *
 * ## Cosa fa invece questo
 *
 * Un valore casuale, generato in memoria dalla tastiera e consumato una volta
 * sola. Tastiera e Activity vivono nello **stesso processo**, quindi il valore
 * non attraversa nessun confine osservabile; un'app esterna puo' scrivere
 * qualunque extra ma non puo' indovinare 128 bit.
 *
 * Se il processo muore fra la consegna e il ritiro, il gettone si perde e
 * l'attribuzione salta: [DecryptActivity] ricade su "nessuna app", che
 * disabilita la scelta implicita del destinatario. E' il verso giusto in cui
 * fallire — meglio chiedere all'utente che attribuire il messaggio all'app
 * sbagliata.
 */
internal object CipherHandoff {

    private const val EXTRA = "helium314.keyboard.cipher.HANDOFF"

    private val random = SecureRandom()

    /**
     * Un gettone alla volta: il flusso e' sempre "premi decifra, si apre la
     * schermata". Tenerne una collezione servirebbe solo a farli sopravvivere
     * piu' del necessario.
     */
    @Volatile
    private var pendente: Pair<String, String>? = null

    /** Chiave dell'extra da mettere nell'intent. */
    fun extraName(): String = EXTRA

    /**
     * Registra il package dell'editor e restituisce il gettone da allegare
     * all'intent.
     */
    fun issue(editorPackage: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        pendente = token to editorPackage
        return token
    }

    /**
     * Ritira il package associato al gettone, se combacia. Consuma comunque il
     * gettone: un valore che resta valido e' un valore che si puo' riprovare.
     *
     * Il confronto e' a tempo costante. Non e' paranoia gratuita: e' un
     * segreto confrontato con un valore fornito dall'esterno, ed e' lo schema
     * in cui un confronto che esce al primo byte diverso perde informazione.
     */
    fun consume(token: String?): String? {
        val atteso = pendente
        pendente = null
        if (token == null || atteso == null) return null
        return if (equalsCostante(token, atteso.first)) atteso.second else null
    }

    private fun equalsCostante(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
