package helium314.keyboard.cipher

/**
 * Un peer come lo vede la UI.
 *
 * `label` e' `null` finche' l'utente non gli da' un nome: alla prima comparsa
 * si fissa la chiave e basta. L'identita' di contatto e' un'etichetta locale,
 * assegnata dall'utente — senza, due chiavi diverse sarebbero solo due peer
 * diversi e "la chiave di Marco e' cambiata" non sarebbe una frase esprimibile,
 * perche' il keyring e' indicizzato sulla pubkey.
 */
internal data class Peer(
    val key: ByteArray,
    val firstSeenUnix: Long,
    val verified: Boolean,
    val label: String?,
) {
    // data class + ByteArray: equals/hashCode generati confronterebbero il
    // riferimento dell'array, non il contenuto. Qui due Peer con la stessa
    // chiave devono risultare uguali.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Peer && key.contentEquals(other.key))

    override fun hashCode(): Int = key.contentHashCode()
}

/**
 * Decodifica il blob di `nativeListPeers`.
 *
 *     intestazione: versione(1) | conteggio(4, LE)
 *     record:       pubkey(32) | firstSeenUnix(8, LE) | verified(1) |
 *                   labelLen(2, LE) | label(labelLen, UTF-8)
 *
 * I record **non** hanno lunghezza fissa: l'etichetta e' variabile. Scorrerli
 * assumendo un passo costante e' il modo naturale di sbagliare qui, e produce
 * peer inventati invece di un errore.
 *
 * Ogni lettura e' preceduta dal controllo che i byte ci siano: il blob arriva
 * da un file su disco, e un file troncato non deve diventare un
 * `IndexOutOfBounds` dentro la UI.
 */
internal object PeerList {

    private const val VERSION: Byte = 1
    private const val KEY_LEN = 32

    fun parse(blob: ByteArray): List<Peer>? {
        if (blob.size < 5) return null
        if (blob[0] != VERSION) return null

        var offset = 1
        val count = readInt(blob, offset) ?: return null
        offset += 4

        val peers = ArrayList<Peer>(count.coerceAtMost(1024))
        repeat(count) {
            if (offset + KEY_LEN + 8 + 1 + 2 > blob.size) return null
            val key = blob.copyOfRange(offset, offset + KEY_LEN)
            offset += KEY_LEN
            val firstSeen = readLong(blob, offset) ?: return null
            offset += 8
            val verified = blob[offset] != 0.toByte()
            offset += 1
            val labelLen = readShort(blob, offset) ?: return null
            offset += 2
            if (offset + labelLen > blob.size) return null
            val label = if (labelLen == 0) null else String(
                blob, offset, labelLen, Charsets.UTF_8,
            )
            offset += labelLen
            peers.add(Peer(key, firstSeen, verified, label))
        }
        return peers
    }

    private fun readShort(b: ByteArray, at: Int): Int? {
        if (at + 2 > b.size) return null
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt(b: ByteArray, at: Int): Int? {
        if (at + 4 > b.size) return null
        var value = 0
        for (i in 3 downTo 0) {
            value = (value shl 8) or (b[at + i].toInt() and 0xFF)
        }
        // Un conteggio negativo dopo il cast a Int significa un blob assurdo
        // (oltre due miliardi di peer): meglio rifiutare che allocare.
        return if (value < 0) null else value
    }

    private fun readLong(b: ByteArray, at: Int): Long? {
        if (at + 8 > b.size) return null
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (b[at + i].toLong() and 0xFF)
        }
        return value
    }
}
