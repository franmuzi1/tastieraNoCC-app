package helium314.keyboard.cipher

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * File cifrati: foto, note vocali, qualunque cosa (decisione G).
 *
 * ## Perche' non passa dalla tastiera
 *
 * Un IME non puo' allegare un file. Puo' inserire testo e, su Android 7.1+,
 * immagini con `commitContent` — ma l'app ricevente le **ricomprime**, e una
 * ricompressione distrugge il ciphertext. L'audio non si puo' inserire affatto.
 * Quindi si parte da una schermata e si consegna con lo share sheet, che manda
 * il file come *documento*: i documenti non vengono ricompressi.
 *
 * ## Cosa vede la piattaforma
 *
 * Un allegato che non si apre con niente. E' un marcatore molto piu' forte del
 * blob di testo, che si nasconde in mezzo a milioni di messaggi, e la
 * dimensione dice molto piu' della lunghezza di un testo. Residuo accettato con
 * la chiusura di G1: senza questa via le foto si mandano lo stesso, in chiaro,
 * nella stessa conversazione.
 *
 * Il **nome originale** del file non ci finisce dentro: sta nel cifrato. Fuori
 * resta `kc-<casuale>.kc`, altrimenti l'allegato si chiamerebbe
 * `IMG_20260810_compleanno-di-marco.jpg.kc` e racconterebbe da solo quasi tutto.
 */
object CipherFiles {

    /** Estensione dichiarata (decisione G3). */
    private const val EXTENSION = "kc"

    /** Tetto assoluto, scelto con G6. */
    private const val MAX_BYTES = 50L * 1024 * 1024

    /**
     * Il core cifra in una passata sola, in memoria: servono il chiaro, il
     * cifrato, e le copie che JNI fa attraversando il confine. Quattro volte il
     * file e' una stima prudente.
     *
     * Da qui il tetto vero: **il minore fra 50 MB e un quarto dell'heap**. Il
     * limite scelto resta quello, ma su un telefono che non lo regge si dice
     * prima, invece di far aspettare la cifratura e poi morire di
     * OutOfMemory — che e' lo stesso fallimento, molto piu' tardi e senza
     * spiegazione.
     */
    fun maxBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MAX_BYTES
        val heapBytes = manager.memoryClass.toLong() * 1024 * 1024
        return minOf(MAX_BYTES, heapBytes / 4)
    }

    /** Nome e dimensione dichiarati dal fornitore del documento. */
    class Source(val name: String, val mime: String, val size: Long)

    fun describe(context: Context, uri: Uri): Source {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = uri.lastPathSegment.orEmpty()
        var size = -1L
        val cursor: Cursor? = runCatching {
            resolver.query(uri, null, null, null, null)
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return Source(name, mime, size)
    }

    /**
     * Cifra il documento in [uri] per [peer] e ritorna l'intent di condivisione.
     *
     * `null` quando non si e' potuto, e chi chiama distingue il perche' con
     * [maxBytes] e [describe]: qui non si tenta di indovinare un messaggio
     * d'errore che il chiamante puo' formulare meglio.
     */
    fun shareIntent(context: Context, peer: ByteArray, uri: Uri, nowUnix: Long): Intent? {
        val source = describe(context, uri)
        val content = readAll(context.contentResolver, uri, maxBytes(context)) ?: return null
        val blob = try {
            CipherCore.nativeEncryptFile(
                peer, source.name, source.mime, content, nowUnix,
                CipherSettings.isForwardSecrecy(context),
            )
        } finally {
            // Il chiaro non resta in heap piu' del necessario. Non e' una
            // garanzia — la GC puo' averne gia' fatto copie — ma e' la stessa
            // regola che vale per il resto del confine JNI.
            content.fill(0)
        }
        if (blob == null) return null
        // La chiave temporanea appena generata su disco, prima che l'allegato
        // esca: senza, la risposta arriverebbe cifrata verso una chiave che il
        // processo si e' portato nella tomba.
        CipherIdentity.persistKeyring(context)

        val file = writeShared(context, blob) ?: return null
        val shared = FileProvider.getUriForFile(context, authority(context), file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, shared)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Legge un allegato ricevuto e lo consegna al core.
     *
     * Ritorna il codice del core, cosi' chi chiama usa gli stessi messaggi dei
     * messaggi di testo — compreso l'unico errore opaco per qualunque
     * fallimento crypto.
     */
    fun decrypt(
        context: Context,
        uri: Uri,
        nowUnix: Long,
        result: CipherCore.IncomingResult,
    ): Int {
        val blob = readAll(context.contentResolver, uri, maxBytes(context))
            ?: return CipherCore.FORMAT
        return CipherCore.nativeDecryptFile(blob, nowUnix, result)
    }

    /**
     * Legge tutto il flusso, fermandosi **sopra** il limite.
     *
     * Si legge un byte in piu' del consentito apposta: e' l'unico modo di
     * distinguere "grande quanto il limite" da "piu' grande del limite" quando
     * la dimensione dichiarata non c'e' o mente, e i fornitori di documenti
     * possono benissimo non dichiararla.
     */
    private fun readAll(resolver: ContentResolver, uri: Uri, limit: Long): ByteArray? =
        runCatching {
            resolver.openInputStream(uri)?.use { stream: InputStream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > limit) return@use null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrNull()

    /**
     * Scrive il cifrato dove il FileProvider puo' esporlo.
     *
     * E' ciphertext, quindi non e' un segreto — ma resta della roba che si
     * accumula: la cartella viene ripulita a ogni condivisione, tenendo solo
     * l'ultimo file. Tenerne uno solo e' anche l'unico modo di non lasciare in
     * giro l'elenco di quanti file hai mandato e quando.
     */
    private fun writeShared(context: Context, blob: ByteArray): File? = runCatching {
        val dir = File(context.cacheDir, "cipher-share").apply {
            deleteRecursively()
            mkdirs()
        }
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val suffix = bytes.joinToString("") { "%02x".format(it) }
        File(dir, "kc-$suffix.$EXTENSION").apply { writeBytes(blob) }
    }.getOrNull()

    private fun authority(context: Context) = context.packageName + ".cipherfiles"
}
