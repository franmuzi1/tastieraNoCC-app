package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Quando ogni contatto e' stato usato come destinatario l'ultima volta.
 *
 * Serve a una cosa sola: ordinare l'elenco di "a chi stai scrivendo?" per uso
 * recente invece che nell'ordine in cui il core restituisce i peer. Chi si
 * scrive spesso con tre persone non deve cercarle ogni volta in fondo a un
 * elenco che cresce.
 *
 * ## Perche' un file suo
 *
 * Non sta in `SharedPreferences`: quelle sono XML in chiaro dentro `filesDir`, e
 * HeliBoard ha `allowBackup="true"` senza esclusioni, quindi finirebbero nel
 * backup cloud. Qui dentro c'e' *con chi parli e quando*, che e' esattamente il
 * metadato che questo progetto esiste per non regalare — cifrato o no, non ha
 * motivo di passare da un server terzo.
 *
 * Ricalca [CipherRecipients]: stesso involucro di [CipherKeystore], **dominio
 * AAD suo** perche' un blob non possa essere accettato al posto dell'altro, e
 * `noBackupFilesDir` per costruzione. Un file illeggibile diventa una mappa
 * vuota e basta: si perde l'ordine, non l'uso della tastiera.
 */
internal object CipherUsage {

    private val AAD = "keyboard-cipher/v1/storage/usage".toByteArray()

    private const val VERSION: Byte = 1
    private const val KEY_LEN = 32

    /**
     * Segna che si sta cifrando per questo contatto, adesso.
     *
     * Si chiama quando il destinatario viene **scelto**, non quando il messaggio
     * parte: la scelta e' il gesto che dice "e' con questa persona che sto
     * parlando", e il messaggio potrebbe non partire mai.
     */
    fun nota(context: Context, peer: ByteArray) {
        if (peer.size != KEY_LEN) return
        val mappa = carica(context).toMutableMap()
        mappa[peer.toList()] = System.currentTimeMillis() / 1000
        salva(context, mappa)
    }

    /**
     * L'ordine per l'elenco: prima chi e' stato usato piu' di recente, poi chi
     * non lo e' mai stato.
     *
     * Una lettura sola e una mappa in mano al chiamante, invece di una funzione
     * che rilegge il file per ogni confronto: l'ordinamento chiama il
     * comparatore O(n log n) volte, e ogni chiamata sarebbe una decifratura.
     */
    fun ordinati(context: Context, peers: List<Peer>): List<Peer> {
        val quando = carica(context)
        return peers.sortedByDescending { quando[it.key.toList()] ?: 0L }
    }

    fun dimentica(context: Context, peer: ByteArray) {
        val mappa = carica(context).toMutableMap()
        if (mappa.remove(peer.toList()) != null) salva(context, mappa)
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
