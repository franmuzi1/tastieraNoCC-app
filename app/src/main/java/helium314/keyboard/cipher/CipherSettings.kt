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

    /**
     * Dopo aver consegnato il messaggio all'app, chiedile anche di spedirlo.
     *
     * Vale **solo** con la riga di composizione attiva, e non e' un dettaglio:
     * li' i due tasti sono gia' "manda questo messaggio", perche' il testo non
     * e' mai stato nel campo dell'app. Senza la riga il lucchetto sostituisce
     * cio' che stai scrivendo, e spedirlo da solo sarebbe un'altra cosa —
     * irreversibile e non richiesta.
     *
     * Acceso di default in quella modalita': l'alternativa e' premere due volte
     * per fare una cosa sola. Si spegne da Impostazioni → Cifratura.
     */
    const val PREF_AUTO_SEND = "cipher_auto_send"
    const val DEFAULT_AUTO_SEND = true

    fun isAutoSend(prefs: SharedPreferences): Boolean =
        isComposeMode(prefs) && prefs.getBoolean(PREF_AUTO_SEND, DEFAULT_AUTO_SEND)

    fun isAutoSend(context: Context): Boolean = isAutoSend(context.prefs())

    /**
     * Aprire da soli il messaggio appena copiato.
     *
     * Il momento in cui si copia e' l'**unico** aggancio automatico che una
     * tastiera abbia: la chat non la vede, e leggere gli appunti di propria
     * iniziativa a ogni sessione di digitazione la renderebbe un'app che spia
     * gli appunti. Qui invece il testo e' gia' stato letto per la cronologia,
     * quindi il controllo e' gratis.
     *
     * *Conseguenza dichiarata:* **con la cronologia appunti disattivata questo
     * non scatta.** E' il prezzo di non leggere di nascosto.
     */
    const val PREF_AUTO_OPEN = "cipher_auto_open"
    const val DEFAULT_AUTO_OPEN = true

    fun isAutoOpen(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(PREF_AUTO_OPEN, DEFAULT_AUTO_OPEN)

    fun isAutoOpen(context: Context): Boolean = isAutoOpen(context.prefs())

    /**
     * Forward secrecy: la chiave con cui il messaggio e' stato cifrato smette
     * di esistere appena il messaggio parte.
     *
     * In pratica: nell'intestazione viaggia una chiave usa-e-getta al posto
     * della tua, e la chiave del messaggio nasce da due scambi messi insieme —
     * quello usa-e-getta e quello con la tua chiave stabile. Il primo fa si'
     * che **chi domani si impossessasse della tua chiave non riaprirebbe i
     * messaggi che hai gia' mandato**; il secondo dimostra a chi riceve che sei
     * stato tu, senza bisogno di firme.
     *
     * Di conseguenza la tua chiave non compare piu' in chiaro, quindi due
     * messaggi tuoi non si possono nemmeno piu' legare fra loro guardando il
     * traffico.
     *
     * **Cosa non copre:** chi ottiene la chiave del *destinatario* apre tutto
     * lo stesso, perche' entrambi gli scambi passano di li'. Per quello serve
     * una chiave temporanea anche dal lato di chi riceve, che e' un lavoro a
     * parte.
     *
     * **Acceso di default**, con due conseguenze da conoscere: chi riceve deve
     * avere questa versione o piu' recente, e il mittente dev'essere gia' fra i
     * contatti — senza la sua chiave in chiaro, chi riceve lo riconosce
     * provando i propri contatti, e uno sconosciuto non e' fra quelli. Lo
     * scambio delle presentazioni, che era gia' il primo passo, ora e'
     * necessario.
     */
    const val PREF_FORWARD_SECRECY = "cipher_forward_secrecy"
    const val DEFAULT_FORWARD_SECRECY = true

    fun isForwardSecrecy(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(PREF_FORWARD_SECRECY, DEFAULT_FORWARD_SECRECY)

    fun isForwardSecrecy(context: Context): Boolean = isForwardSecrecy(context.prefs())

    /**
     * Copia e taglia dalla riga di composizione.
     *
     * **Bloccati di default.** Negli appunti il chiaro e' leggibile dall'app che
     * ha il fuoco — cioe' proprio l'app di chat da cui la riga di composizione
     * esiste per tenerlo lontano — e finisce anche nella cronologia appunti
     * della tastiera, che sta su disco. Copiarlo disfa in un tocco cio' che la
     * riga fa.
     *
     * Esiste comunque l'interruttore perche' il divieto ha un costo reale:
     * spostare del testo fra due campi, o riprendere una frase scritta a meta',
     * diventa impossibile senza riscriverla. Chi ha quel bisogno e sa cosa
     * comporta puo' riaprirlo.
     *
     * Vale **solo** dentro la riga di composizione: senza, il chiaro sta gia'
     * nel campo dell'app e non c'e' niente da proteggere.
     */
    const val PREF_BLOCK_COPY = "cipher_block_copy"
    const val DEFAULT_BLOCK_COPY = true

    fun isBlockCopy(prefs: SharedPreferences): Boolean =
        isComposeMode(prefs) && prefs.getBoolean(PREF_BLOCK_COPY, DEFAULT_BLOCK_COPY)

    fun isBlockCopy(context: Context): Boolean = isBlockCopy(context.prefs())

    /**
     * Tenere vivo il processo della tastiera con un servizio in primo piano.
     *
     * ## Il problema che risolve, e perche' non ce n'erano altri
     *
     * Il riconoscimento di un blob negli appunti vive in
     * `ClipboardHistoryManager`, cioe' nel processo della tastiera: se quel
     * processo non c'e', copiare non produce niente. E **non c'e' modo di
     * accorgersene**: Android non ha nessun evento per gli appunti — nessun
     * broadcast dichiarabile, nessun observer — quindi non esiste niente che
     * possa risvegliare un'app ferma quando l'utente copia.
     *
     * A fermarla e' tipicamente il gestore batteria del produttore, che fa un
     * force-stop vero: il pacchetto passa a `stopped` e il sistema ripiega
     * perfino su un'altra tastiera. Un servizio in primo piano e' la sola cosa
     * che quei gestori di solito rispettano, perche' ha una notifica visibile.
     *
     * ## Perche' e' SPENTO di default
     *
     * Costa una notifica permanente in barra di stato e il permesso notifiche,
     * che su una tastiera che promette riservatezza non e' gratis. La strada da
     * provare prima e' togliere la restrizione batteria all'app: stessa
     * efficacia, zero ingombro. Vedi la voce che apre quella schermata.
     */
    const val PREF_KEEP_ALIVE = "cipher_keep_alive"
    const val DEFAULT_KEEP_ALIVE = false

    fun isKeepAlive(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(PREF_KEEP_ALIVE, DEFAULT_KEEP_ALIVE)

    fun isKeepAlive(context: Context): Boolean = isKeepAlive(context.prefs())

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
