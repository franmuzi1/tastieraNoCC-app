package helium314.keyboard.cipher

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiave maestra in Android Keystore, e cifratura dei blob che finiscono su
 * disco.
 *
 * Il core Rust non fa I/O per costruzione: il segreto di identita' e il
 * keyring escono da li' come byte e qualcuno deve conservarli. Metterli in
 * chiaro nella directory privata dell'app sarebbe inutile — su un dispositivo
 * con root o con un backup estraibile sono leggibili — quindi passano da una
 * chiave AES-256-GCM che vive dentro Keystore e non esce mai in user space.
 * Un attaccante che copia i file si porta via del ciphertext e basta.
 *
 * Perche' API 23 e non 21, che e' il minSdk del fork: prima di Marshmallow
 * AndroidKeyStore sa fare solo RSA, e [KeyGenParameterSpec] non esiste. La
 * variante RSA-wrap sarebbe piu' codice proprio sulle versioni dove le
 * implementazioni erano piu' fragili, per una quota di dispositivi ormai
 * trascurabile. Sotto API 23 la cifratura e' semplicemente non disponibile, e
 * lo si dice: vedi [CipherState.Unavailable].
 *
 * Due proprieta' della chiave, entrambe volute:
 *
 *  - `setUnlockedDeviceRequired(true)` (API 28+): il blob non e' decifrabile
 *    finche' il dispositivo e' bloccato. Serve perche' i file stanno in device
 *    protected storage — l'app e' `directBootAware`, quindi la sua directory
 *    e' leggibile gia' prima dello sblocco. La protezione a riposo la mette
 *    questa riga, non il filesystem;
 *  - NESSUN `setUserAuthenticationRequired`. Chiederebbe biometria o PIN a
 *    ogni operazione: su una tastiera vorrebbe dire un prompt in mezzo alla
 *    digitazione, e il primo effetto sarebbe che l'utente spegne la funzione.
 */
@RequiresApi(Build.VERSION_CODES.M)
internal object CipherKeystore {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "keyboard-cipher/v1/master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM standard. Non e' una scelta: e' l'unica lunghezza che Keystore usa. */
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_LEN = TAG_BITS / 8

    /** Versione del contenitore su disco, non del formato dei messaggi. */
    private const val VERSION: Byte = 1

    /**
     * Cifra [plaintext]. Il risultato e' `versione(1) | iv(12) | ct+tag`.
     *
     * [domain] entra come AAD e distingue i due file: senza, un blob di
     * identita' rinominato in keyring si decifrerebbe senza proteste, e il
     * fallimento apparirebbe molto piu' in la', dove nessuno lo collega alla
     * causa.
     *
     * L'IV lo sceglie Keystore, non noi: `setRandomizedEncryptionRequired`
     * rifiuta un IV fornito dal chiamante, che e' esattamente il modo in cui
     * GCM si rompe.
     */
    fun wrap(domain: ByteArray, plaintext: ByteArray): ByteArray? = runCatching {
        val key = masterKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(domain)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_LEN) return null
        val ct = cipher.doFinal(plaintext)
        ByteArray(1 + IV_LEN + ct.size).also { out ->
            out[0] = VERSION
            iv.copyInto(out, 1)
            ct.copyInto(out, 1 + IV_LEN)
        }
    }.getOrNull()

    /**
     * Inverso di [wrap]. `null` per qualunque motivo: blob corto, versione
     * sconosciuta, tag non valido, chiave maestra sparita, dispositivo
     * bloccato.
     *
     * Il chiamante NON deve reagire rigenerando: un `null` qui puo' voler dire
     * "chiave persa", e sovrascrivere sarebbe distruggere l'identita'
     * dell'utente al primo errore transitorio. Vedi [CipherIdentity].
     */
    fun unwrap(domain: ByteArray, blob: ByteArray): ByteArray? = runCatching {
        if (blob.size < 1 + IV_LEN + TAG_LEN) return null
        if (blob[0] != VERSION) return null
        val key = masterKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
        cipher.updateAAD(domain)
        cipher.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }.getOrNull()

    /**
     * Cancella la chiave maestra. Da qui in poi nessun blob gia' scritto e'
     * piu' decifrabile: e' distruzione di dati, non pulizia. Solo da
     * [CipherIdentity.resetIdentity].
     */
    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun masterKey(): SecretKey? = runCatching {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val entry = keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        entry?.secretKey ?: generate()
    }.getOrNull()

    /**
     * StrongBox (API 28+) mette la chiave in un elemento sicuro separato dal
     * processore applicativo. Non c'e' modo di sapere in anticipo se il
     * dispositivo ce l'ha e se accetta questi parametri, quindi si prova e si
     * ricade sul TEE normale. Il fallback e' silenzioso di proposito: non e'
     * una condizione d'errore, e' la maggioranza dei dispositivi.
     */
    /**
     * Tre tentativi in ordine di robustezza decrescente. Ognuno cade sul
     * successivo, e il motivo di ciascuna caduta e' diverso.
     *
     * 1. **StrongBox + dispositivo sbloccato.** Non c'e' modo di sapere in
     *    anticipo se il dispositivo ha un elemento sicuro separato: si prova.
     *    Fallisce con `HARDWARE_TYPE_UNAVAILABLE` sulla maggioranza dei
     *    dispositivi, emulatori compresi.
     *
     * 2. **TEE + dispositivo sbloccato.** Il caso normale.
     *
     * 3. **TEE e basta.** Necessario, e la ragione va capita prima di
     *    toglierlo: su un dispositivo **senza blocco schermo**
     *    `setUnlockedDeviceRequired(true)` non fallisce all'uso, fallisce alla
     *    GENERAZIONE, con
     *    *"Failed to handle super encryption: User ECDH key missing"*. Quella
     *    chiave per-utente esiste solo se l'utente ha una credenziale. Senza
     *    questo terzo tentativo la cifratura sarebbe semplicemente non
     *    disponibile per chiunque non tenga un PIN sul telefono — che non e'
     *    una minoranza trascurabile.
     *
     *    Non si perde nulla di reale: `setUnlockedDeviceRequired` protegge i
     *    dati *mentre il dispositivo e' bloccato*, e un dispositivo senza
     *    blocco schermo non e' mai bloccato. La protezione sarebbe stata
     *    vacua comunque.
     *
     * Osservato su emulatore API 34 senza blocco schermo: i primi due
     * tentativi falliscono, il terzo riesce. Prima che il codice girasse
     * davvero, questo percorso non esisteva e la funzione era morta su quei
     * dispositivi.
     */
    private fun generate(): SecretKey? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { build(spec(unlockedRequired = true).setIsStrongBoxBacked(true)) }
                .getOrNull()?.let { return it }
        }
        runCatching { build(spec(unlockedRequired = true)) }.getOrNull()?.let { return it }
        return runCatching { build(spec(unlockedRequired = false)) }.getOrNull()
    }

    private fun spec(unlockedRequired: Boolean): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (unlockedRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        return builder
    }

    private fun build(builder: KeyGenParameterSpec.Builder): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }
}
