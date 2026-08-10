package helium314.keyboard.cipher

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.utils.prefs

/**
 * Le due preferenze della cifratura.
 *
 * Stanno qui e non in `Settings`/`Defaults` di HeliBoard perche' ogni riga
 * aggiunta a un file di upstream e' un conflitto in attesa al prossimo merge, e
 * queste due non hanno niente a che vedere con la tastiera: descrivono una
 * funzione che upstream non ha.
 */
object CipherSettings {

    /**
     * Interruttore generale. Spento, il fork si comporta come HeliBoard: niente
     * tasti in toolbar, niente riga di composizione, nessun percorso di
     * cifratura raggiungibile.
     *
     * Acceso di default: e' la ragione per cui questa tastiera esiste, e
     * spedirla con la propria funzione disattivata significherebbe che chi la
     * installa non trova niente.
     */
    const val PREF_ENABLED = "cipher_enabled"
    const val DEFAULT_ENABLED = true

    /**
     * Modalita' con barra testuale autonoma: il chiaro si scrive nella riga
     * della tastiera e l'app riceve solo il blob.
     *
     * Spenta di default, e non per timidezza: cambia il posto in cui si scrive,
     * che e' l'abitudine piu' radicata che una tastiera possa toccare. Chi la
     * vuole la accende sapendo cosa cambia; chi non la accende ha il
     * comportamento di prima, con il chiaro che passa dal campo dell'app.
     */
    const val PREF_COMPOSE_MODE = "cipher_compose_mode"
    const val DEFAULT_COMPOSE_MODE = false

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ENABLED, DEFAULT_ENABLED)

    fun isEnabled(context: Context): Boolean = isEnabled(context.prefs())

    /**
     * La riga di composizione dipende dall'interruttore generale: spento
     * quello, questa non ha significato. Chiederle separatamente sarebbe il
     * modo piu' facile per ritrovarsi con una riga di composizione in una
     * tastiera che non cifra.
     */
    fun isComposeMode(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(PREF_COMPOSE_MODE, DEFAULT_COMPOSE_MODE)

    fun isComposeMode(context: Context): Boolean = isComposeMode(context.prefs())
}
