package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Quando ogni contatto e' stato scelto come destinatario l'ultima volta.
 *
 * Serve a una cosa sola: mettere in cima all'elenco "a chi stai scrivendo?" chi
 * hai usato di recente, invece dell'ordine in cui il core restituisce i peer.
 *
 * ## Cosa NON afferma
 *
 * L'ordine non dice "e' lui": dice "questi li hai usati poco fa". La differenza
 * conta, ed e' il motivo per cui qui non c'e' nessuna preselezione. Non esiste
 * un destinatario per app — dentro una stessa chat si scrive a piu' persone — e
 * qualunque interfaccia che lo lasci intendere invita a cifrare per la persona
 * sbagliata, che e' il fallimento peggiore di questo sistema. Ordinare
 * risparmia lo scorrimento, non la scelta.
 *
 * ## Perche' un file suo
 *
 * Non in `SharedPreferences`: sono XML in chiaro dentro `filesDir`, e HeliBoard
 * ha `allowBackup="true"` senza esclusioni. Qui c'e' *con chi parli e quando*,
 * che e' il metadato che questo progetto esiste per non regalare.
 *
 * Ricalca [CipherRecipients]: involucro di [CipherKeystore], **dominio AAD suo**
 * perche' un blob non possa essere accettato al posto di un altro, e
 * `noBackupFilesDir` per costruzione. File illeggibile: mappa vuota, si perde
 * l'ordine e non altro.
 */
internal object CipherUsage {

    private val AAD = "keyboard-cipher/v1/storage/usage".toByteArray()

    private const val VERSION: Byte = 1
    private const val KEY_LEN = 32

    /**
     * Segna che si sta cifrando per questo contatto, adesso.
     *
     * Alla SCELTA e non all'invio: la scelta e' il gesto che dice "e' con questa
     * persona che sto parlando", e il messaggio potrebbe non partire mai.
     */
    fun nota(context: Context, peer: ByteArray) {
        if (peer.size != KEY_LEN) return
        val mappa = carica(context).toMutableMap()
        mappa[peer.toList()] = System.currentTimeMillis() / 1000
        salva(context, mappa)
    }

    /**
     * Prima i piu' recenti, poi chi non e' mai stato usato.
     *
     * Una lettura sola, e la mappa in mano al chiamante: l'ordinamento chiama il
     * comparatore O(n log n) volte, e ogni chiamata sarebbe una decifratura.
     */
    fun ordinati(context: Context, peers: List<Peer>): List<Peer> {
        val quando = carica(context)
        return peers.sortedByDescending { quando[it.key.toList()] ?: 0L }
    }

    private fun carica(context: Context): Map<List<Byte>, Long> {
        val blob = CipherStorage.read(context, CipherStorage.USAGE) ?: return emptyMap()
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return emptyMap()
        return try {
            runCatching { decodifica(plain) }.getOrDefault(emptyMap())
        } finally {
            plain.fill(0)
        }
    }

    private fun salva(context: Context, mappa: Map<List<Byte>, Long>) {
        val plain = codifica(mappa)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return
        } finally {
            plain.fill(0)
        }
        CipherStorage.write(context, CipherStorage.USAGE, blob)
    }

    private fun codifica(mappa: Map<List<Byte>, Long>): ByteArray {
        val buffer = ByteBuffer
            .allocate(1 + 4 + mappa.size * (KEY_LEN + 8))
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION)
        buffer.putInt(mappa.size)
        for ((chiave, quando) in mappa) {
            buffer.put(chiave.toByteArray())
            buffer.putLong(quando)
        }
        return buffer.array()
    }

    private fun decodifica(bytes: ByteArray): Map<List<Byte>, Long> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 5) return emptyMap()
        // Versione diversa: si riparte senza ordine invece di indovinare un
        // formato. Si perde la comodita', non i contatti.
        if (buffer.get() != VERSION) return emptyMap()
        val quanti = buffer.int
        if (quanti < 0) return emptyMap()
        val mappa = HashMap<List<Byte>, Long>(quanti)
        repeat(quanti) {
            if (buffer.remaining() < KEY_LEN + 8) return mappa
            val chiave = ByteArray(KEY_LEN)
            buffer.get(chiave)
            mappa[chiave.toList()] = buffer.long
        }
        return mappa
    }
}
