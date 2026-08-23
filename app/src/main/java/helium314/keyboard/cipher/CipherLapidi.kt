package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Le impronte dei contatti dimenticati, con il nome che avevano.
 *
 * ## Perche' esiste
 *
 * Dimenticare un contatto cancella il pin, e con esso l'unico segnale anti-MITM
 * del sistema: la prossima chiave che arriva viene fissata come nuova, in
 * silenzio, perche' non c'e' piu' niente con cui confrontarla. E' la finestra
 * in cui qualcuno che si spaccia per quella persona passa senza far scattare
 * nulla.
 *
 * Questa lapide riapre quel confronto. Non e' crittografia: e' il ricordo di
 * cosa avevi scritto tu.
 *
 * ## Cosa puo' dire, e cosa no
 *
 * L'app **non conosce persone, conosce chiavi**: un contatto e' una chiave piu'
 * un'etichetta scelta dall'utente. Quindi:
 *
 *  - **stessa impronta** che ritorna: confronto affidabile, e' la chiave con
 *    se' stessa. "E' quella che avevi dimenticato";
 *  - **stesso nome su un'impronta diversa**: il ponte non e' crittografico, e'
 *    il nome che l'utente digita. Non afferma "e' la stessa persona" — mostra
 *    una contraddizione nei suoi appunti: stai per chiamare Marco una chiave
 *    diversa da quella che chiamavi Marco. E' lo stesso segnale che il TOFU
 *    darebbe con "la chiave e' cambiata", e che dimenticare aveva disarmato.
 *
 * Non distingue "ha cambiato telefono" da "qualcuno si spaccia per lui": sono
 * indistinguibili dall'interno. Dice solo di fermarsi e verificare fuori banda.
 *
 * ## Il prezzo, dichiarato
 *
 * **"Dimentica" smette di dimenticare del tutto**: resta una traccia di con chi
 * hai parlato. Sul telefono, cifrata e fuori dal backup — ma resta. E' una
 * scelta presa sapendo che quel metadato la piattaforma di chat ce l'ha
 * comunque, mentre il silenzio davanti a un MITM non lo compensa nessuno.
 * L'avviso di "Dimentica" lo dice.
 *
 * Il **rogo** non scrive qui: quello esiste per non lasciare tracce, e una
 * lapide lo contraddirebbe.
 */
internal object CipherLapidi {

    private val AAD = "keyboard-cipher/v1/storage/tombstones".toByteArray()

    private const val VERSION: Byte = 1

    /** Un contatto dimenticato: impronta, nome che aveva, quando. */
    class Lapide(val impronta: String, val nome: String, val quando: Long)

    fun ricorda(context: Context, impronta: String, nome: String) {
        if (impronta.isBlank()) return
        val tutte = carica(context).filterNot { it.impronta == impronta }
        salva(context, tutte + Lapide(impronta, nome, System.currentTimeMillis() / 1000))
    }

    /** La lapide di questa esatta chiave, se e' gia' stata dimenticata. */
    fun perImpronta(context: Context, impronta: String): Lapide? =
        carica(context).firstOrNull { it.impronta == impronta }

    /**
     * Una lapide con questo nome ma un'impronta **diversa**: la contraddizione
     * che vale la pena mostrare.
     */
    fun conflitto(context: Context, nome: String, impronta: String): Lapide? {
        val cercato = nome.trim()
        if (cercato.isEmpty()) return null
        return carica(context).firstOrNull {
            it.nome.equals(cercato, ignoreCase = true) && it.impronta != impronta
        }
    }

    /** Quando una chiave torna in rubrica, la sua lapide non serve piu'. */
    fun scorda(context: Context, impronta: String) {
        val restanti = carica(context).filterNot { it.impronta == impronta }
        salva(context, restanti)
    }

    private fun carica(context: Context): List<Lapide> {
        val blob = CipherStorage.read(context, CipherStorage.TOMBSTONES) ?: return emptyList()
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return emptyList()
        return try {
            runCatching { decodifica(plain) }.getOrDefault(emptyList())
        } finally {
            plain.fill(0)
        }
    }

    private fun salva(context: Context, lapidi: List<Lapide>) {
        val plain = codifica(lapidi)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return
        } finally {
            plain.fill(0)
        }
        CipherStorage.write(context, CipherStorage.TOMBSTONES, blob)
    }

    private fun codifica(lapidi: List<Lapide>): ByteArray {
        val pezzi = lapidi.map { it.impronta.toByteArray() to it.nome.toByteArray() }
        val size = 1 + 4 + pezzi.sumOf { (i, n) -> 2 + i.size + 2 + n.size + 8 }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION)
        buffer.putInt(pezzi.size)
        for ((indice, pezzo) in pezzi.withIndex()) {
            val (impronta, nome) = pezzo
            buffer.putShort(impronta.size.toShort())
            buffer.put(impronta)
            buffer.putShort(nome.size.toShort())
            buffer.put(nome)
            buffer.putLong(lapidi[indice].quando)
        }
        return buffer.array()
    }

    private fun decodifica(bytes: ByteArray): List<Lapide> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 5) return emptyList()
        // Versione diversa: si riparte senza lapidi invece di indovinare un
        // formato. Si perde il confronto, non i contatti.
        if (buffer.get() != VERSION) return emptyList()
        val quante = buffer.int
        if (quante < 0) return emptyList()
        val fuori = ArrayList<Lapide>(quante)
        repeat(quante) {
            val impronta = leggiStringa(buffer) ?: return fuori
            val nome = leggiStringa(buffer) ?: return fuori
            if (buffer.remaining() < 8) return fuori
            fuori.add(Lapide(impronta, nome, buffer.long))
        }
        return fuori
    }

    private fun leggiStringa(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 2) return null
        val quanti = buffer.short.toInt()
        if (quanti < 0 || buffer.remaining() < quanti) return null
        val bytes = ByteArray(quanti)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
