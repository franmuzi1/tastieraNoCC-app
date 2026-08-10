package helium314.keyboard.cipher

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.compat.isUserLocked

/**
 * Esito dell'inizializzazione. Non tutti i casi sono errori — [Locked] e' una
 * condizione normale — e nessuno va tradotto in eccezione: un'eccezione in una
 * tastiera e' un dispositivo su cui non si puo' piu' scrivere.
 */
sealed class CipherState {

    /** Il core e' inizializzato. Da qui in poi le native si possono chiamare. */
    object Ready : CipherState()

    /**
     * Utente non ancora sbloccato dopo il riavvio. La tastiera funziona (serve
     * a digitare il PIN), la cifratura no: la chiave maestra richiede il
     * dispositivo sbloccato. Non e' un guasto e non va cachato — basta
     * richiamare [CipherIdentity.ensureReady] piu' tardi.
     */
    object Locked : CipherState()

    /** La cifratura non e' disponibile su questo dispositivo o in questo build. */
    data class Unavailable(val reason: CipherReason) : CipherState()

    /**
     * I dati ci sono ma non si decifrano. **Stato, non errore**: richiede una
     * decisione dell'utente, esattamente come `LabelOutcome::Conflict` nel
     * core. Vedi [CipherIdentity.resetIdentity] per cosa comporta la risposta.
     */
    data class Unreadable(val part: CipherPart) : CipherState()
}

enum class CipherReason {
    /** Il `.so` non si e' caricato: ABI non prevista, o build senza il core. */
    MISSING_LIBRARY,

    /** Sotto API 23 AndroidKeyStore non sa fare AES. Vedi [CipherKeystore]. */
    API_TOO_OLD,

    /** Keystore ha rifiutato di generare o usare la chiave maestra. */
    KEYSTORE,

    /** Il file non si e' scritto: disco pieno, o storage non disponibile. */
    STORAGE,

    /** Il core ha rifiutato il segreto o il keyring. */
    CORE,
}

enum class CipherPart { IDENTITY, KEYRING }

/**
 * Ciclo di vita della chiave: generazione, persistenza, ricarica.
 *
 * E' il punto che tiene insieme tre pezzi che non si conoscono fra loro — il
 * core Rust, che non fa I/O; Keystore, che protegge; il filesystem, che
 * conserva.
 *
 * ## Pigro, non all'avvio
 *
 * [ensureReady] va chiamata al primo uso vero, non da `Application.onCreate`.
 * Motivi: all'avvio l'utente puo' essere ancora bloccato, e una tastiera che
 * genera chiavi mentre il sistema aspetta la sua finestra e' una tastiera
 * lenta. La prima chiamata puo' costare qualche centinaio di millisecondi
 * (generazione della chiave in Keystore, StrongBox se c'e'); le successive
 * sono la lettura di un flag. **Non chiamarla da un percorso di disegno.**
 *
 * ## Cosa non fa, e apposta
 *
 * Non rigenera mai l'identita' da sola. Se il segreto c'e' ma non si decifra,
 * ritorna [CipherState.Unreadable] e si ferma li'. Rigenerare sarebbe la
 * reazione comoda e la peggiore: l'utente si ritroverebbe una nuova identita'
 * senza saperlo, e tutti i suoi contatti vedrebbero "la chiave e' cambiata" —
 * cioe' esattamente il segnale che il sistema usa per dire "qualcuno si sta
 * spacciando per lui". Un guasto locale diventerebbe indistinguibile da un
 * attacco. La sostituzione esiste, ma la decide l'utente: [resetIdentity].
 */
object CipherIdentity {

    /**
     * Separazione di dominio fra i due file. Impedisce che un blob valido
     * venga accettato al posto dell'altro.
     */
    private val AAD_IDENTITY = "keyboard-cipher/v1/storage/identity".toByteArray()
    private val AAD_KEYRING = "keyboard-cipher/v1/storage/keyring".toByteArray()

    @Volatile
    private var ready = false

    /**
     * Inizializza il core se serve, e dice com'e' andata. Idempotente: dopo il
     * primo successo e' una lettura di campo.
     */
    fun ensureReady(context: Context): CipherState {
        if (ready) return CipherState.Ready
        val app = context.applicationContext
        return synchronized(this) { loadLocked(app) }
    }

    /**
     * Scrive su disco il keyring corrente. Va chiamata dopo OGNI modifica —
     * pin di un nuovo peer, etichetta, conferma di cambio chiave, verifica
     * fuori banda — perche' il core tiene il keyring in memoria e non sa che
     * esiste un disco.
     *
     * Un keyring non persistito significa peer che tornano "nuovi" al riavvio:
     * il TOFU li rifisserebbe in silenzio, e la finestra di MITM che il pin
     * serve a chiudere si riaprirebbe a ogni reboot.
     */
    fun persistKeyring(context: Context): Boolean {
        if (!ready) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val app = context.applicationContext
        return synchronized(this) { persistLocked(app) }
    }

    /**
     * Butta via identita' e keyring e riparte da zero.
     *
     * Distruttivo e irreversibile: la vecchia identita' non e' recuperabile,
     * i messaggi gia' ricevuti non saranno piu' decifrabili, e ogni contatto
     * vedra' un cambio di chiave. Da chiamare SOLO dopo una conferma esplicita
     * dell'utente davanti a una schermata che dica queste tre cose.
     *
     * Esiste per uscire da [CipherState.Unreadable]: senza, l'unica via
     * sarebbe cancellare i dati dell'app.
     */
    fun resetIdentity(context: Context): CipherState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return CipherState.Unavailable(CipherReason.API_TOO_OLD)
        }
        val app = context.applicationContext
        return synchronized(this) {
            ready = false
            CipherStorage.delete(app, CipherStorage.IDENTITY)
            CipherStorage.delete(app, CipherStorage.KEYRING)
            // Dopo i file: cancellare prima la chiave lascerebbe su disco due
            // blob non piu' decifrabili se la cancellazione dei file fallisse.
            CipherKeystore.deleteKey()
            loadLocked(app)
        }
    }

    private fun loadLocked(context: Context): CipherState {
        if (ready) return CipherState.Ready
        // Il .so puo' mancare senza che sia colpa di nessuno: build senza core,
        // o ABI non fra le quattro previste. Non e' un crash, e' una funzione
        // che non c'e'.
        if (!CipherCore.available) return CipherState.Unavailable(CipherReason.MISSING_LIBRARY)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return CipherState.Unavailable(CipherReason.API_TOO_OLD)
        }
        // Prima del primo sblocco la chiave maestra non e' usabile. Uscire qui
        // evita di interpretare un dispositivo bloccato come dati corrotti.
        if (isUserLocked(context)) return CipherState.Locked
        return initLocked(context)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun initLocked(context: Context): CipherState {
        var secret: ByteArray? = null
        try {
            val loaded = if (CipherStorage.exists(context, CipherStorage.IDENTITY)) {
                val blob = CipherStorage.read(context, CipherStorage.IDENTITY)
                    ?: return unreadableOrLocked(context, CipherPart.IDENTITY)
                CipherKeystore.unwrap(AAD_IDENTITY, blob)
                    ?: return unreadableOrLocked(context, CipherPart.IDENTITY)
            } else {
                when (val created = createIdentity(context)) {
                    is Created.Ok -> created.secret
                    is Created.Failed -> return CipherState.Unavailable(created.reason)
                }
            }
            secret = loaded

            val keyring = if (CipherStorage.exists(context, CipherStorage.KEYRING)) {
                val blob = CipherStorage.read(context, CipherStorage.KEYRING)
                    ?: return unreadableOrLocked(context, CipherPart.KEYRING)
                // Un keyring illeggibile NON si sostituisce con uno vuoto:
                // significherebbe perdere in silenzio ogni pin e ogni
                // "verificato di persona", che e' una regressione di sicurezza
                // travestita da ripartenza pulita.
                CipherKeystore.unwrap(AAD_KEYRING, blob)
                    ?: return unreadableOrLocked(context, CipherPart.KEYRING)
            } else {
                ByteArray(0)
            }

            if (CipherCore.nativeInit(secret, keyring) != CipherCore.OK) {
                return CipherState.Unavailable(CipherReason.CORE)
            }
            ready = true
            return CipherState.Ready
        } finally {
            // Il core ha gia' copiato quello che gli serve; qui resta una copia
            // in heap che la GC non azzera.
            secret?.fill(0)
        }
    }

    /**
     * Distingue "dati corrotti" da "dispositivo bloccato", che dall'interno di
     * [CipherKeystore] sono lo stesso `null`.
     *
     * Verificato su emulatore API 34 con PIN impostato: a schermo bloccato
     * Keystore rifiuta la chiave con `Error::Km(DEVICE_LOCKED)`, perche' e'
     * stata generata con `setUnlockedDeviceRequired`. Senza questa distinzione
     * quel rifiuto — transitorio, normale, e che passa da solo allo sblocco
     * successivo — verrebbe presentato come "la tua identita' non e'
     * decifrabile", il cui unico rimedio offerto e' [resetIdentity]: cioe' si
     * inviterebbe l'utente a distruggere irreversibilmente la propria identita'
     * per una condizione che si risolve premendo un tasto.
     *
     * Si classifica DOPO il fallimento e non prima: se la chiave e' stata
     * generata su un dispositivo senza blocco schermo non ha quel vincolo e
     * funziona anche a schermo bloccato. Rifiutare in anticipo bloccherebbe un
     * caso che invece va benissimo.
     */
    private fun unreadableOrLocked(context: Context, part: CipherPart): CipherState =
        if (isDeviceLocked(context)) CipherState.Locked else CipherState.Unreadable(part)

    private sealed class Created {
        class Ok(val secret: ByteArray) : Created()
        class Failed(val reason: CipherReason) : Created()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createIdentity(context: Context): Created {
        val secret = CipherCore.nativeGenerateSecret()
            ?: return Created.Failed(CipherReason.CORE)
        val blob = CipherKeystore.wrap(AAD_IDENTITY, secret)
        if (blob == null) {
            secret.fill(0)
            return Created.Failed(CipherReason.KEYSTORE)
        }
        // Su disco PRIMA di entrare in uso. Un'identita' viva ma non
        // persistita sparirebbe alla morte del processo — dopo che l'utente ha
        // gia' consegnato la propria card a qualcuno, che da quel momento
        // scriverebbe a una chiave che non esiste piu'.
        if (!CipherStorage.write(context, CipherStorage.IDENTITY, blob)) {
            secret.fill(0)
            return Created.Failed(CipherReason.STORAGE)
        }
        return Created.Ok(secret)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun persistLocked(context: Context): Boolean {
        val keyring = CipherCore.nativeExportKeyring() ?: return false
        val blob = CipherKeystore.wrap(AAD_KEYRING, keyring) ?: return false
        return CipherStorage.write(context, CipherStorage.KEYRING, blob)
    }
}
