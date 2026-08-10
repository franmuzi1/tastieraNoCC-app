package helium314.keyboard.cipher

import android.content.Context
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

    /**
     * Distingue "non c'e' ancora" da "c'e' ma non si legge". Il primo caso e'
     * il primo avvio, il secondo e' un guasto: confonderli significherebbe
     * rigenerare l'identita' al primo errore di lettura.
     */
    fun exists(context: Context, name: String): Boolean = file(context, name).isFile

    fun read(context: Context, name: String): ByteArray? =
        runCatching { file(context, name).readBytes() }.getOrNull()

    /**
     * Scrittura atomica: file temporaneo, `fsync`, rename. Senza,
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
            true
        } else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)

    fun delete(context: Context, name: String) {
        runCatching { file(context, name).delete() }
    }

    private fun file(context: Context, name: String): File =
        File(context.noBackupFilesDir, DIR).apply { mkdirs() }.resolve(name)
}
