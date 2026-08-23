package helium314.keyboard.cipher

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/**
 * I due file del sistema, gia' cifrati da [CipherKeystore].
 *
 * Stanno sotto `noBackupFilesDir` e non sotto `filesDir`, ed e' la scelta piu'
 * importante di questo file. HeliBoard ha `android:allowBackup="true"` senza
 * regole di esclusione, quindi tutto cio' che finisce in `filesDir` va nel
 * backup cloud. Due conseguenze, entrambe brutte:
 *
 *  - la chiave maestra sta in Keystore e NON e' esportabile, quindi non viene
 *    ripristinata. Un ripristino su un telefono nuovo riporterebbe due file
 *    illeggibili: l'utente si troverebbe l'identita' "corrotta" senza aver
 *    fatto niente di sbagliato;
 *  - l'elenco dei peer e' proprio il metadato che questo progetto esiste per
 *    non regalare. Cifrato o no, non ha motivo di passare da un server terzo.
 *
 * `noBackupFilesDir` da' la garanzia per costruzione, senza toccare
 * `fullBackupContent` / `dataExtractionRules` nel manifest di upstream —
 * che sarebbero due file in piu' da tenere allineati e un merge conflict in
 * attesa.
 *
 * ## Dove finiscono davvero, misurato
 *
 * Il manifest ha `defaultToDeviceProtectedStorage="true"`, da cui sarebbe
 * naturale dedurre che questi file stiano in device protected storage. **Non
 * e' cosi'.** Verificato su emulatore API 34: finiscono in
 * `/data/user/0/<pkg>/no_backup/cipher/`, cioe' in *credential encrypted*
 * storage; la directory device-protected dell'app esiste separatamente e
 * contiene solo cio' che HeliBoard ci mette tramite `DeviceProtectedUtils`.
 *
 * La differenza conta ed e' a nostro favore: CE e' cifrato con una chiave
 * derivata dalla credenziale dell'utente, quindi a riposo e prima del primo
 * sblocco i file non sono nemmeno leggibili. La protezione della chiave
 * maestra (`setUnlockedDeviceRequired`) si somma a questa, non la sostituisce.
 *
 * Conseguenza pratica gia' gestita: prima del primo sblocco questa directory
 * non e' accessibile, ed e' un'altra ragione per cui [CipherIdentity] esce con
 * `Locked` invece di provarci.
 */
internal object CipherStorage {

    private const val DIR = "cipher"

    const val IDENTITY = "identity.bin"
    const val KEYRING = "keyring.bin"
    /** Destinatario corrente per app. Vedi [CipherRecipients]. */
    const val RECIPIENTS = "recipients.bin"
    /** Contatti dimenticati, per riconoscerli se tornano. Vedi [CipherLapidi]. */
    const val TOMBSTONES = "forgotten.bin"
    /** Ultimo uso di ogni contatto, per l'ordine dell'elenco. Vedi [CipherUsage]. */
    const val USAGE = "usage.bin"

    /**
     * Distingue "non c'e' ancora" da "c'e' ma non si legge". Il primo caso e'
     * il primo avvio, il secondo e' un guasto: confonderli significherebbe
     * rigenerare l'identita' al primo errore di lettura.
     */
    fun exists(context: Context, name: String): Boolean = file(context, name).isFile

    fun read(context: Context, name: String): ByteArray? =
        runCatching { file(context, name).readBytes() }.getOrNull()

    /**
     * Scrittura atomica: file temporaneo, `fsync`, rename, `fsync` della
     * directory (vedi [syncDir], che e' il pezzo che mancava). Senza,
     * un'interruzione a meta' — batteria, kill dell'OOM killer — lascerebbe un
     * file troncato, che dopo la cifratura autenticata e' indistinguibile da
     * un file manomesso. L'utente vedrebbe "identita' corrotta" per un calo di
     * batteria.
     */
    fun write(context: Context, name: String, bytes: ByteArray): Boolean = runCatching {
        val target = file(context, name)
        val tmp = File(target.parentFile, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (tmp.renameTo(target)) {
            syncDir(target.parentFile)
            true
        } else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)

    /**
     * `fsync` sulla directory, che e' l'unica cosa che rende durevole il
     * rename.
     *
     * Il `sync()` qui sopra riguarda il CONTENUTO del temporaneo; la voce di
     * directory che il rename sposta e' un'altra cosa, e senza questa chiamata
     * puo' restare solo in cache. Il rename e' atomico — non si vede mai un
     * file mezzo rinominato — ma atomico non vuol dire durevole: dopo un crash
     * del kernel o un calo di corrente subito dopo la scrittura la directory
     * puo' tornare com'era, con il solo `.tmp` e nessun file al suo posto. Su
     * ext4 con le opzioni di default il journal commit arriva comunque entro
     * pochi secondi e in pratica regge, ma qui dentro ci sono identita' e
     * portachiavi, e "in pratica regge" non e' la garanzia che il resto di
     * questa funzione promette.
     *
     * Si passa da `Os.open` e non da `FileInputStream`: su Android aprire una
     * directory con quelle classi fallisce sempre — `IoBridge` rifiuta con
     * `EISDIR` anche in sola lettura — quindi l'unico modo di avere un
     * descrittore di directory e' la open(2) diretta.
     *
     * Un fallimento qui NON fa fallire la scrittura, ed e' il motivo per cui
     * ha il suo `runCatching` invece di appoggiarsi a quello del chiamante:
     * `fsync` su un descrittore di directory non e' garantito su tutti i
     * filesystem che Android monta, e restituire `false` direbbe al chiamante
     * che il file non c'e' — mentre c'e', completo e al suo posto. In
     * [CipherIdentity] quel `false` significa "portachiavi non salvato" e fa
     * comparire un avviso: trasformare un fsync opzionale in quell'avviso
     * sarebbe una bugia.
     */
    private fun syncDir(dir: File?) {
        if (dir == null) return
        runCatching {
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
    }

    fun delete(context: Context, name: String) {
        runCatching { file(context, name).delete() }
    }

    private fun file(context: Context, name: String): File =
        File(context.noBackupFilesDir, DIR).apply { mkdirs() }.resolve(name)
}
