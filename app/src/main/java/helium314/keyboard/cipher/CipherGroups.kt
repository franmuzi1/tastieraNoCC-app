package helium314.keyboard.cipher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Un gruppo salvato: un nome scelto dall'utente e le chiavi di chi ne fa parte.
 */
internal class CipherGroup(val nome: String, val membri: List<ByteArray>)

/**
 * I gruppi salvati (decisione K5).
 *
 * ## Cos'e' un gruppo, qui dentro
 *
 * **Un'etichetta locale sopra un insieme di chiavi**, esattamente come il nome
 * di un contatto e' un'etichetta locale sopra una chiave. Non viaggia, non entra
 * nel cifrato, non lo conosce nessun altro — nemmeno i membri, che non sanno di
 * essere in un gruppo che si chiama «Famiglia».
 *
 * Da qui una conseguenza che vale la pena avere in mente: due persone possono
 * mandare messaggi allo stesso insieme di gente chiamandolo in due modi diversi,
 * e non c'e' niente da riconciliare perche' non c'e' niente di condiviso.
 *
 * ## Perche' e' cifrato a riposo come il resto
 *
 * Contiene chiavi di contatti raggruppate per affinita': non e' solo "con chi
 * parli", e' "chi sta insieme a chi". E' un metadato piu' ricco dell'elenco dei
 * contatti, non piu' povero, e sta sotto la stessa protezione con un dominio
 * AAD suo — cosi' il file dei gruppi non si puo' spacciare per un altro file.
 *
 * ## Cosa NON fa
 *
 * Non tocca il keyring. Un membro che nel frattempo e' stato dimenticato resta
 * scritto qui: se ne accorge chi va a cifrare, perche' il core rifiuta le chiavi
 * non fissate. Ripulire i gruppi a ogni cambio dei contatti significherebbe
 * cancellare in silenzio una scelta dell'utente per un'operazione che magari sta
 * annullando.
 */
internal object CipherGroups {

    private const val VERSION: Byte = 1
    private const val KEY_LEN = 32
    private const val MAX_MEMBRI = 8
    private val AAD = "keyboard-cipher/v1/storage/groups".toByteArray()

    fun tutti(context: Context): List<CipherGroup> = load(context).gruppi

    /**
     * Il gruppo scelto come destinatario per quell'app, se ce n'e' uno.
     *
     * La scelta sta qui e non nel core perche' il core non ha un "gruppo
     * corrente": fra due persone il destinatario si stabilisce leggendo, per un
     * gruppo no — vedi il commento in `api.rs`. Ed e' cifrata come il resto,
     * perche' "in quale chat scrivo a quale gruppo" e' un metadato piu' ricco
     * dell'elenco dei contatti, non piu' povero.
     */
    fun corrente(context: Context, appPackage: String): CipherGroup? {
        val stato = load(context)
        val nome = stato.scelte[appPackage] ?: return null
        // `ignoreCase` come in [salva] e [dimentica]: quelli trattano
        // «Famiglia» e «famiglia» come lo stesso gruppo, e un confronto esatto
        // qui farebbe sparire la scelta appena fatta se il nome fosse stato
        // salvato con una maiuscola diversa. Tre confronti dello stesso nome
        // devono seguire la stessa regola, o e' questione di tempo.
        return stato.gruppi.firstOrNull { it.nome.equals(nome, ignoreCase = true) }
    }

    /** Sceglie un gruppo per quell'app. `null` torna al destinatario singolo. */
    fun scegli(context: Context, appPackage: String, nome: String?): Boolean {
        if (appPackage.isEmpty()) return false
        val stato = load(context)
        val scelte = stato.scelte.toMutableMap()
        if (nome == null) scelte.remove(appPackage) else scelte[appPackage] = nome
        return save(context, Stato(stato.gruppi, scelte))
    }

    /**
     * Salva un gruppo con quel nome, sostituendo quello che c'era.
     *
     * Il nome fa da chiave: due gruppi con lo stesso nome sarebbero
     * indistinguibili nell'elenco, e l'utente non avrebbe modo di dire quale
     * intende.
     */
    fun salva(context: Context, nome: String, membri: List<ByteArray>): Boolean {
        val pulito = nome.trim()
        if (pulito.isEmpty() || membri.isEmpty() || membri.size > MAX_MEMBRI) return false
        if (membri.any { it.size != KEY_LEN }) return false
        val stato = load(context)
        val restanti = stato.gruppi.filterNot { it.nome.equals(pulito, ignoreCase = true) }
        return save(context, Stato(restanti + CipherGroup(pulito, membri), stato.scelte))
    }

    /**
     * Dimentica un gruppo, e con lui ogni scelta che lo nominava: una scelta
     * che punta a un gruppo inesistente farebbe comparire "scrivi a Famiglia"
     * in una chat dove Famiglia non c'e' piu'.
     */
    fun dimentica(context: Context, nome: String): Boolean {
        val stato = load(context)
        return save(
            context,
            Stato(
                stato.gruppi.filterNot { it.nome.equals(nome, ignoreCase = true) },
                stato.scelte.filterNot { it.value.equals(nome, ignoreCase = true) },
            ),
        )
    }

    fun cancellaTutto(context: Context) {
        CipherStorage.delete(context, CipherStorage.GROUPS)
    }

    private class Stato(val gruppi: List<CipherGroup>, val scelte: Map<String, String>)

    private fun load(context: Context): Stato {
        val vuoto = Stato(emptyList(), emptyMap())
        val blob = CipherStorage.read(context, CipherStorage.GROUPS) ?: return vuoto
        val plain = CipherKeystore.unwrap(AAD, blob) ?: return vuoto
        return try {
            runCatching { decode(plain) }.getOrDefault(vuoto)
        } finally {
            plain.fill(0)
        }
    }

    private fun save(context: Context, stato: Stato): Boolean {
        if (stato.gruppi.isEmpty()) {
            cancellaTutto(context)
            return true
        }
        val plain = encode(stato)
        val blob = try {
            CipherKeystore.wrap(AAD, plain) ?: return false
        } finally {
            plain.fill(0)
        }
        return CipherStorage.write(context, CipherStorage.GROUPS, blob)
    }

    /**
     *     versione(1) | conteggio(4) |
     *     per gruppo:  nomeLen(2) | nome | membri(1) | chiave(32) * membri
     */
    private fun encode(stato: Stato): ByteArray {
        val nomi = stato.gruppi.associateWith { it.nome.toByteArray(Charsets.UTF_8) }
        val scelte = stato.scelte.entries.associateWith {
            it.key.toByteArray(Charsets.UTF_8) to it.value.toByteArray(Charsets.UTF_8)
        }
        var size = 5
        for (g in stato.gruppi) size += 2 + (nomi[g]?.size ?: 0) + 1 + g.membri.size * KEY_LEN
        size += 4
        for ((app, nome) in scelte.values) size += 2 + app.size + 2 + nome.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION)
        buffer.putInt(stato.gruppi.size)
        for (g in stato.gruppi) {
            val nome = nomi[g] ?: ByteArray(0)
            buffer.putShort(nome.size.toShort())
            buffer.put(nome)
            buffer.put(g.membri.size.toByte())
            for (m in g.membri) buffer.put(m)
        }
        buffer.putInt(scelte.size)
        for ((app, nome) in scelte.values) {
            buffer.putShort(app.size.toShort())
            buffer.put(app)
            buffer.putShort(nome.size.toShort())
            buffer.put(nome)
        }
        return buffer.array()
    }

    /**
     * Ogni lettura e' preceduta dal controllo che i byte ci siano: il blob
     * arriva da un file, e un file troncato non deve diventare un
     * `IndexOutOfBounds` dentro l'interfaccia.
     */
    private fun decode(blob: ByteArray): Stato {
        val vuoto = Stato(emptyList(), emptyMap())
        if (blob.size < 5 || blob[0] != VERSION) return vuoto
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(1)
        val quanti = buffer.int
        if (quanti < 0 || quanti > 1024) return vuoto
        val gruppi = ArrayList<CipherGroup>(quanti)
        repeat(quanti) {
            if (buffer.remaining() < 3) return Stato(gruppi, emptyMap())
            val nomeLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < nomeLen + 1) return Stato(gruppi, emptyMap())
            val nome = ByteArray(nomeLen).also { buffer.get(it) }
            val quantiMembri = buffer.get().toInt() and 0xFF
            if (quantiMembri == 0 || quantiMembri > MAX_MEMBRI) return Stato(gruppi, emptyMap())
            if (buffer.remaining() < quantiMembri * KEY_LEN) return Stato(gruppi, emptyMap())
            val membri = ArrayList<ByteArray>(quantiMembri)
            repeat(quantiMembri) {
                membri.add(ByteArray(KEY_LEN).also { buffer.get(it) })
            }
            gruppi.add(CipherGroup(String(nome, Charsets.UTF_8), membri))
        }

        // Le scelte sono in coda: un file scritto prima che esistessero si
        // rilegge lo stesso, con nessuna scelta invece che con un errore.
        if (buffer.remaining() < 4) return Stato(gruppi, emptyMap())
        val quanteScelte = buffer.int
        if (quanteScelte < 0 || quanteScelte > 1024) return Stato(gruppi, emptyMap())
        val scelte = HashMap<String, String>(quanteScelte)
        repeat(quanteScelte) {
            if (buffer.remaining() < 2) return Stato(gruppi, scelte)
            val appLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < appLen + 2) return Stato(gruppi, scelte)
            val app = ByteArray(appLen).also { buffer.get(it) }
            val nomeLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < nomeLen) return Stato(gruppi, scelte)
            val nome = ByteArray(nomeLen).also { buffer.get(it) }
            scelte[String(app, Charsets.UTF_8)] = String(nome, Charsets.UTF_8)
        }
        return Stato(gruppi, scelte)
    }
}
