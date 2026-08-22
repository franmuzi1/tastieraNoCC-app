package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chi e' il destinatario, per ciascuna app — **su disco**.
 *
 * ## Il guasto che questo file ripara
 *
 * Il core tiene la scelta del destinatario in memoria, ed e' giusto cosi': e'
 * un crate senza I/O. Ma la memoria di un IME e' quella di un servizio che
 * Android riavvia quando gli pare — poca RAM, cambio di tastiera, riavvio del
 * telefono — e ogni riavvio azzerava la scelta.
 *
 * L'effetto per chi usa la tastiera era questo: fissi il destinatario, scrivi,
 * cifri, tutto bene; qualche ora dopo premi il lucchetto e non succede piu'
 * niente, con un messaggio che chiede di scegliere un destinatario che tu
 * *avevi gia' scelto*. Sembra una funzione rotta a caso, ed e' il tipo di
 * guasto che fa abbandonare il sistema invece che segnalarlo.
 *
 * ## Perche' cifrato
 *
 * Non contiene segreti — sono chiavi pubbliche e nomi di package — ma e'
 * esattamente il metadato che questo progetto esiste per non regalare: con chi
 * parli, e in quale applicazione. Passa quindi dallo stesso Keystore del
 * keyring, con un dominio suo perche' un blob non possa essere accettato al
 * posto dell'altro.
 *
 * ## Formato
 *
 *     versione(1) | conteggio(4, LE) | record*
 *     record: lunghezza-package(2, LE) | package(UTF-8) | pubkey(32)
 *
 * I record non hanno lunghezza fissa: il nome del package e' variabile.
 * Scorrerli assumendo un passo costante e' sbagliato.
 */
internal object CipherRecipients {

    private const val VERSION = 1
    private const val KEY_LEN = 32
    private val AAD = "keyboard-cipher/v1/storage/recipients".toByteArray()

    /**
     * Ricorda che in [appPackage] si scrive a [peer], e lo scrive su disco.
     *
     * Rilegge il file invece di tenere una copia in memoria: le scritture sono
     * rare — accadono quando l'utente sceglie un destinatario o decifra un
     * messaggio — e una cache qui sarebbe un secondo stato da tenere allineato
     * con quello del core, cioe' un modo in piu' di sbagliare.
     */
    fun remember(context: Context, appPackage: String, peer: ByteArray) {
        if (appPackage.isEmpty() || peer.size != KEY_LEN) return
        val current = load(context).toMutableMap()
        if (current[appPackage]?.contentEquals(peer) == true) return
        current[appPackage] = peer
        save(context, current)
    }

    /**
     * Rimette nel core le scelte salvate. Va chiamata **dopo** `nativeInit`,
     * perche' prima non c'e' nessuna sessione a cui dirle.
     *
     * Un peer che nel frattempo non e' piu' nel keyring viene rifiutato dal
     * core, e va bene: significa che l'utente ha ripulito i contatti, e
     * ripristinare un destinatario che non esiste piu' sarebbe peggio che
     * chiederglielo di nuovo.
     */
    fun restore(context: Context) {
        for ((appPackage, peer) in load(context)) {
            CipherCore.nativeSetCurrentPeer(appPackage, peer)
        }
    }

    fun forget(context: Context) {
        CipherStorage.delete(context, CipherStorage.RECIPIENTS)
    }

    /**
     * Il chiaro non resta in heap oltre il necessario: qui dentro ci sono le
     * chiavi dei peer, cioe' proprio l'elenco di con chi parli che questo
     * progetto esiste per non regalare. Stessa regola del segreto di identita'
     * in [CipherIdentity].
     */
    private fun load(context: Context): Map<String, ByteArray> {
        val blob = CipherStorage.read(context, CipherStorage.RECIPIENTS) ?: return emptyMap()
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return emptyMap()
        return try {
            runCatching { decode(plain) }.getOrDefault(emptyMap())
        } finally {
            plain.fill(0)
        }
    }

    private fun save(context: Context, map: Map<String, ByteArray>) {
        val plain = encode(map)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return
        } finally {
            plain.fill(0)
        }
        CipherStorage.write(context, CipherStorage.RECIPIENTS, blob)
    }

    private fun encode(map: Map<String, ByteArray>): ByteArray {
        var size = 5
        val names = map.keys.associateWith { it.toByteArray(Charsets.UTF_8) }
        for (name in names.values) size += 2 + name.size + KEY_LEN
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION.toByte())
        buffer.putInt(map.size)
        for ((app, peer) in map) {
            val name = names[app] ?: continue
            buffer.putShort(name.size.toShort())
            buffer.put(name)
            buffer.put(peer)
        }
        return buffer.array()
    }

    /**
     * Un file che non si decodifica diventa una mappa vuota, non un'eccezione:
     * il peggio che possa succedere e' che l'utente debba riscegliere il
     * destinatario, che e' molto meglio di una tastiera che non parte.
     */
    private fun decode(bytes: ByteArray): Map<String, ByteArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 5) return emptyMap()
        if (buffer.get().toInt() != VERSION) return emptyMap()
        val count = buffer.int
        if (count < 0) return emptyMap()
        val result = HashMap<String, ByteArray>(count)
        repeat(count) {
            if (buffer.remaining() < 2) return result
            val length = buffer.short.toInt()
            if (length < 0 || buffer.remaining() < length + KEY_LEN) return result
            val name = ByteArray(length)
            buffer.get(name)
            val peer = ByteArray(KEY_LEN)
            buffer.get(peer)
            result[String(name, Charsets.UTF_8)] = peer
        }
        return result
    }
}
