package helium314.keyboard.cipher

/**
 * Ponte verso il core Rust (crate `keyboard-cipher-jni`).
 *
 * Regole del confine, non negoziabili:
 *
 *  - i segreti attraversano come `ByteArray`, mai come `String`. Una
 *    `java.lang.String` e' immutabile: non e' azzerabile e resta in heap fino
 *    alla GC. Il chiamante azzera l'array appena ha finito;
 *  - la chiave privata attraversa UNA sola volta, alla generazione. Poi resta
 *    in Rust; la JVM manipola solo plaintext in entrata e blob in uscita;
 *  - un fallimento crypto e' un solo codice. Il core non distingue le cause
 *    apposta, e questo strato non deve reintrodurre la distinzione.
 *
 * Le operazioni sono X25519 piu' un AEAD su testi corti: microsecondi. Si
 * possono chiamare dal main thread senza problemi.
 */
object CipherCore {

    /**
     * `false` se il `.so` non si e' caricato. Ogni chiamata a una `native`
     * sotto va preceduta dal controllo — in pratica basta passare da
     * [CipherIdentity.ensureReady], che lo fa.
     *
     * Il caricamento e' avvolto perche' un `UnsatisfiedLinkError` durante
     * l'inizializzazione di questo `object` non resterebbe locale: farebbe
     * fallire l'inizializzazione della classe, e ogni accesso successivo
     * lancerebbe `NoClassDefFoundError`. In un'app qualunque sarebbe un
     * dialogo di crash; in una tastiera e' un dispositivo su cui non si puo'
     * piu' scrivere — comprese le credenziali per ripararlo. Una libreria
     * assente deve degradare a "funzione non disponibile", mai a questo.
     *
     * `Throwable` e non `UnsatisfiedLinkError`: qui l'ampiezza e' il punto.
     */
    @JvmField
    val available: Boolean = try {
        System.loadLibrary("keyboard_cipher_jni")
        true
    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
        false
    }

    // Codici di ritorno. Devono restare allineati a `mod code` in
    // jni/src/lib.rs: sono un contratto ABI, non una convenzione.
    const val OK = 0
    /** Non e' un nostro blob. Esito NORMALE, non un errore. */
    const val NOT_OUR_BLOB = 1
    const val FORMAT = 2
    const val UNSUPPORTED_VERSION = 3
    const val DECODE = 4
    /** Qualunque fallimento crypto. Nessun dettaglio, per costruzione. */
    const val CRYPTO = 5
    const val UNKNOWN_PEER = 6
    const val TIER_UNSUPPORTED = 7
    const val KEYRING = 8
    /** Sessione non inizializzata, errore JNI, o panic intercettato in Rust. */
    const val INTERNAL = 9

    // Esiti di [nativeBlobShape]. Descrivono la BUSTA, non l'esito: dicono cosa
    // il blob dichiara di se' in chiaro, non se si e' aperto.
    const val SHAPE_UNKNOWN = 0
    const val SHAPE_MESSAGE = 1
    /** Ha usato la catena: solo qui «si apre una volta sola» e' vero. */
    const val SHAPE_MESSAGE_FS = 2
    const val SHAPE_GROUP = 3
    const val SHAPE_CARD = 4
    const val SHAPE_BURN = 5
    /**
     * L'abbiamo scritto noi, ma il destinatario non e' piu' fra i contatti.
     * Esito NORMALE: senza forward secrecy un proprio messaggio si riapre, e
     * questo codice arriva solo quando non si trova piu' a chi era diretto.
     *
     * Il core lo restituisce **solo** dove la riapertura passa dalle identita'
     * — schema baseline, bootstrap d'epoca, allegati — cioe' dove fallire per
     * tutti i contatti significa davvero che il destinatario non c'e' piu'.
     * Negli altri casi arriva [OWN_MESSAGE_KEY_GONE].
     */
    const val OWN_MESSAGE = 10

    /**
     * L'abbiamo scritto noi, il destinatario c'e' ancora, ma la chiave effimera
     * verso cui era cifrato non esiste piu'.
     *
     * Esito NORMALE quanto [OWN_MESSAGE], e la differenza sta tutta nell'avviso:
     * qui incolpare la rubrica sarebbe falso. Capita quando il destinatario ha
     * la forward secrecy accesa — il messaggio era sigillato verso la sua
     * prechiave di allora, che e' avanzata — oppure quando la conversazione e'
     * stata bruciata.
     */
    const val OWN_MESSAGE_KEY_GONE = 11

    // Valori del campo `kind` di [IncomingResult] dopo nativeHandleIncomingText.
    const val KIND_MESSAGE = 0
    const val KIND_IDENTITY_CARD = 1
    /** Allegato cifrato: vedi [nativeDecryptFile]. */
    const val KIND_FILE = 2
    /**
     * Un messaggio nostro, riaperto. `senderKey`, `senderLabel` e
     * `senderFingerprint` sono quelli del **destinatario**: qui non c'e' un
     * mittente da mostrare, e la UI deve dire "a" e non "da".
     *
     * Esiste solo senza forward secrecy: con la catena accesa la chiave usata
     * per cifrare non esiste piu' e non c'e' niente da riaprire.
     */
    const val KIND_OWN_MESSAGE = 3
    /** Un allegato nostro, riaperto. Come [KIND_OWN_MESSAGE]: il peer e' il destinatario. */
    const val KIND_OWN_FILE = 4
    /**
     * L'altro ha chiesto di bruciare la conversazione, e l'abbiamo fatto: le
     * chiavi con cui si leggeva non esistono piu' da questo lato. Non c'e'
     * testo da mostrare, e' un gesto.
     */
    const val KIND_BURNED = 5

    /**
     * La propria card, riaperta. **Non e' stato fissato nessun contatto**, e
     * `senderKey` resta nullo apposta: riempirlo con la propria chiave
     * inviterebbe a trattarla come un peer, che e' il difetto da cui nasce
     * questo caso. Copiare la propria chiave e riaprirla creava in rubrica un
     * contatto che e' se stessi, senza che niente lo dicesse.
     */
    const val KIND_OWN_IDENTITY_CARD = 6

    // Valori del campo `kind` dopo nativeAssignLabel.
    const val LABEL_ASSIGNED = 0
    /** L'etichetta appartiene gia' a un'altra chiave: "safety number changed". */
    const val LABEL_CONFLICT = 1

    /**
     * Riempito dal lato Rust invece di essere costruito la': allocare oggetti
     * da JNI richiede risolvere classe e firma del costruttore a runtime, e
     * ogni errore diventa un'eccezione lanciata in mezzo a un'operazione
     * crypto. I campi sono `@JvmField` perche' JNI scrive sui campi, non
     * attraverso i setter.
     *
     * Quali campi siano valorizzati dipende da `kind`; gli altri restano null.
     */
    class IncomingResult {
        @JvmField var kind: Int = -1
        /**
         * 1 se il mittente e' stato verificato fuori banda, cioe' se l'utente
         * ha confrontato il codice di persona. E' l'unico segnale anti-MITM
         * del sistema: non va mai riempito con altro.
         */
        @JvmField var verified: Int = 0
        /** Solo per le presentazioni: 1 se quella chiave era gia' nota. */
        @JvmField var alreadyPinned: Int = 0

        /**
         * Quante persone potevano leggere questo messaggio, mittente compreso.
         * `1` per un messaggio a due.
         *
         * Non e' una curiosita' da mostrare: sopra uno, il messaggio viene da
         * un gruppo, e **un messaggio di gruppo non ha forward secrecy** — la
         * condizione che accompagna quella decisione e' che l'interfaccia lo
         * dica. Senza questo numero non sarebbe esprimibile.
         */
        @JvmField var recipientCount: Int = 0

        /**
         * 1 se il messaggio veniva da un **gruppo**.
         *
         * Un campo suo e non una soglia su [recipientCount], ed e' una
         * correzione: prima il gruppo si deduceva da `recipientCount > 1`, e un
         * blob costruito con un solo slot si presentava come un normale
         * messaggio a due — pur non avendo forward secrecy. Saltava cosi'
         * l'avviso che quella mancanza esiste per dire. Un numero non e' una
         * semantica.
         */
        @JvmField var isGroup: Int = 0
        /** Solo per [KIND_MESSAGE]. Da azzerare dopo l'uso. */
        @JvmField var plaintext: ByteArray? = null
        /**
         * Quando il mittente dichiara di aver composto il messaggio.
         *
         * E' autenticato — sta dentro il cifrato — ma NON verificabile:
         * nessuno puo' dimostrare che il suo orologio fosse giusto. Va
         * mostrato accanto al messaggio, cosi' un blob ripubblicato mesi dopo
         * si nota. Non usarlo per decisioni automatiche.
         */
        @JvmField var sentAtUnix: Long = 0
        @JvmField var senderFingerprint: String? = null
        /** Nome dato dall'utente a questa chiave, null se mai nominata. */
        @JvmField var senderLabel: String? = null
        /** Pubkey del mittente: serve per etichettarlo o selezionarlo. */
        @JvmField var senderKey: ByteArray? = null
        /**
         * Solo per [KIND_FILE]. Nome e tipo arrivano da chi ha mandato il
         * file: sono autenticati — l'AEAD ha detto di si' — ma **non
         * credibili**, perche' il mittente puo' averci scritto qualunque cosa.
         * Chi li usa per salvare deve ripulirli: un nome puo' contenere `../`
         * o un separatore di percorso.
         */
        @JvmField var fileName: String? = null
        @JvmField var fileMime: String? = null
        /** Solo per [KIND_FILE]. Da azzerare dopo l'uso, come il plaintext. */
        @JvmField var fileContent: ByteArray? = null
        /** Solo su [LABEL_CONFLICT]: la chiave che tiene gia' quel nome. */
        @JvmField var existingFingerprint: String? = null
        @JvmField var existingKey: ByteArray? = null
    }

    /**
     * Genera un nuovo segreto di identita'. E' l'unico momento in cui la
     * chiave privata attraversa il confine: va cifrata con Android Keystore,
     * persistita, e l'array azzerato subito dopo.
     */
    external fun nativeGenerateSecret(): ByteArray?

    /**
     * Inizializza la sessione. `keyringBlob` puo' essere vuoto al primo avvio.
     * Ritorna uno dei codici sopra.
     */
    external fun nativeInit(secret: ByteArray, keyringBlob: ByteArray): Int

    /** Blob di presentazione da inserire nel campo con `commitText`. */
    external fun nativeIdentityCard(): String?

    external fun nativeMyFingerprint(): String?

    /**
     * Punto d'ingresso unico per tutte e quattro le vie (clipboard,
     * `ACTION_PROCESS_TEXT`, share sheet, campo di input). Il core non sa da
     * quale arriva, e non deve saperlo.
     *
     * @param appPackage da `EditorInfo.packageName` nell'IME; nell'Activity da
     *   `callingActivity` o `referrer`. Stringa vuota se non determinabile, il
     *   che disabilita la selezione implicita del destinatario — meglio
     *   chiedere che attribuire il messaggio all'app sbagliata.
     * @param result oggetto gia' allocato che il lato Rust riempie.
     */
    external fun nativeHandleIncomingText(
        appPackage: String,
        text: String,
        nowUnix: Long,
        result: IncomingResult,
    ): Int

    /**
     * Esporta identita' e portachiavi cifrati con una passphrase.
     *
     * La passphrase e' un `ByteArray` e non una `String` per la solita
     * ragione: una `String` non e' azzerabile. Azzerala appena chiamata.
     *
     * `null` se qualcosa e' andato storto — un solo esito per tutti i casi,
     * non c'e' niente di utile da distinguere.
     */
    external fun nativeExportBackup(passphrase: ByteArray): ByteArray?

    /**
     * Apre un backup e **sostituisce** identita' e portachiavi correnti.
     *
     * Distruttivo. Va chiamata solo dopo una conferma esplicita dell'utente, e
     * il risultato va persistito SUBITO: se il processo muore prima, su disco
     * resta la vecchia identita' e in memoria c'e' la nuova.
     *
     * @param secretOut array di 32 byte che il chiamante alloca; ci finisce il
     *   segreto da cifrare per lo storage. Azzeralo appena l'hai usato.
     */
    external fun nativeImportBackup(
        blob: ByteArray,
        passphrase: ByteArray,
        secretOut: ByteArray,
    ): Int

    /**
     * Dice se il testo *sembra* contenere un nostro blob. Nessuna
     * decifratura, nessun accesso al keyring, nessun effetto collaterale.
     *
     * NON e' una verifica: `true` non dice che il blob sia integro ne' che sia
     * per noi. Serve solo a decidere se accendere un indizio nella UI.
     *
     * Unica entry che NON richiede [nativeInit]: guarda solo la forma del
     * testo.
     */
    /**
     * Che forma ha il blob, senza decifrarlo e senza toccare il portachiavi.
     *
     * Guarda solo cio' che viaggia in chiaro — versione, tipo, bit di flag —
     * quindi **non e' un oracolo**: non dice se un messaggio si apre, ne'
     * perche' non si e' aperto. Serve dopo un fallimento, per scegliere una
     * frase vera invece di elencare ipotesi. Come [nativeLooksLikeOurBlob], non
     * richiede una sessione inizializzata.
     */
    external fun nativeBlobShape(text: String): Int

    external fun nativeLooksLikeOurBlob(text: String): Boolean

    /**
     * Ritorna il blob cifrato, o null se per quell'app non c'e' un
     * destinatario.
     *
     * **Con `forwardSecrecy` non e' piu' un'operazione di sola lettura:**
     * genera una chiave temporanea nuova e la mette nel keyring. Chi chiama
     * deve persistere subito, altrimenti la risposta dell'altro arrivera'
     * cifrata verso una chiave che il processo si e' portato nella tomba.
     *
     * @param forwardSecrecy con `true` la chiave nell'intestazione e'
     *   usa-e-getta e la tua non viaggia in chiaro, quindi chi domani se ne
     *   impossessasse non riaprirebbe i messaggi gia' mandati — e non li
     *   riapri piu' nemmeno tu. Un messaggio cosi' **non lo apre una versione
     *   precedente**, e chi riceve deve avere il mittente fra i contatti: la
     *   scelta sta nel chiamante perche' il core non sa che versione abbia il
     *   destinatario.
     */
    /**
     * Cifra per piu' destinatari (decisione K).
     *
     * Le chiavi vanno **concatenate** in un solo array, 32 byte ciascuna: un
     * array di array attraverso JNI costa un riferimento locale per riga e un
     * ciclo che li rilascia, cioe' piu' codice `unsafe` per trasportare la
     * stessa cosa.
     *
     * Il mittente si aggiunge da solo dall'altra parte, e non va messo qui:
     * senza uno slot suo non potrebbe rileggere cio' che ha scritto.
     *
     * `null` se qualcosa non va — chiave mai fissata, troppi destinatari,
     * identita' non pronta. **Cifrare non e' di sola lettura**: il portachiavi
     * qui non cambia, ma la regola vale per tutte le altre vie e chi legge
     * questa firma non deve dedurne il contrario.
     */
    /**
     * [errorOut], se non e' `null`, e' un `IntArray(1)` in cui il ponte
     * deposita il motivo quando il ritorno e' `null`: uno dei codici in cima a
     * questo file. Serve perche' `null` da solo mette insieme cause molto
     * diverse, e una — [UNKNOWN_PEER], un membro dimenticato dopo che il
     * gruppo era stato salvato — l'utente puo' risolverla da solo.
     */
    external fun nativeEncryptGroup(
        peers: ByteArray,
        plaintext: ByteArray,
        nowUnix: Long,
        errorOut: IntArray?,
    ): String?

    external fun nativeEncryptForApp(
        appPackage: String,
        plaintext: ByteArray,
        nowUnix: Long,
        forwardSecrecy: Boolean,
    ): String?

    /**
     * Brucia la conversazione con [peer] (decisione J).
     *
     * Ritorna il blob da consegnare all'altra persona, o null se non si e'
     * potuto. **Quando ritorna, da questo lato e' gia' fatto**: chi chiama deve
     * persistere subito, altrimenti un processo che muore lascia le chiavi su
     * disco e il rogo non e' avvenuto.
     *
     * Dall'altro lato e' una **richiesta**, non un comando: la sua app deve
     * onorarla. Non presentarlo all'utente come cancellazione garantita.
     */
    external fun nativeBurnConversation(peer: ByteArray, nowUnix: Long): String?

    external fun nativeSetCurrentPeer(appPackage: String, peer: ByteArray): Int

    /**
     * C'e' gia' un destinatario per questa app?
     *
     * Serve a distinguere "non so a chi cifrare" da "la cifratura e' fallita":
     * due cose che l'utente risolve in modi diversi e che senza questa domanda
     * si vedono uguali, cioe' come un tasto che non fa niente.
     */
    external fun nativeHasCurrentPeer(appPackage: String): Boolean

    /**
     * Nome del destinatario per questa app: l'etichetta, o il fingerprint se
     * non ne ha una. `null` se non c'e' nessun destinatario — informazione, non
     * errore: la tastiera la mostra come tale.
     */
    external fun nativeCurrentPeerName(appPackage: String): String?

    /**
     * Cifra un file per un peer **scelto esplicitamente**: qui non c'e' un'app
     * di provenienza da cui dedurre il destinatario, e indovinarlo sarebbe il
     * modo per mandare una foto alla persona sbagliata.
     *
     * Ritorna i byte dell'allegato, o null se il peer non e' nel keyring o la
     * cifratura fallisce. Il chiamante azzera `content` appena consegnato.
     *
     * Con `forwardSecrecy` **modifica il keyring** come [nativeEncryptForApp]:
     * chi chiama deve persistere subito. Un allegato senza catena e' un buco
     * piu' grosso di un messaggio senza — una foto vale piu' di una riga di
     * testo, e resta sul telefono di chi la riceve.
     */
    external fun nativeEncryptFile(
        peer: ByteArray,
        name: String,
        mime: String,
        content: ByteArray,
        nowUnix: Long,
        forwardSecrecy: Boolean,
        errorOut: IntArray?,
    ): ByteArray?

    /**
     * Apre un allegato ricevuto. Riempie `fileName`, `fileMime`, `fileContent`,
     * piu' i campi sul mittente. Ritorna uno dei codici sopra.
     */
    external fun nativeDecryptFile(blob: ByteArray, nowUnix: Long, result: IncomingResult): Int

    /**
     * Attribuisce un nome a una chiave gia' fissata. E' il punto in cui il
     * TOFU acquista la capacita' di dire "la chiave di Marco e' cambiata":
     * senza un'identita' di contatto indipendente dalla chiave, due chiavi
     * diverse sarebbero solo due peer diversi.
     *
     * Su [LABEL_CONFLICT] non modifica NULLA: riempie `existingFingerprint` e
     * `existingKey`, e sta alla UI mostrare i due fingerprint. Solo se
     * l'utente conferma si chiama [nativeConfirmKeyChange] — mai in automatico.
     */
    external fun nativeAssignLabel(peer: ByteArray, label: String, result: IncomingResult): Int

    /** Sostituisce un pin. SOLO dopo conferma esplicita dell'utente. */
    external fun nativeConfirmKeyChange(oldPeer: ByteArray, newPeer: ByteArray, nowUnix: Long): Int

    external fun nativeMarkVerified(peer: ByteArray): Int

    /**
     * Dimentica un peer, e smette di usarlo come destinatario.
     *
     * Chi chiama deve aver **gia' avvertito l'utente**: si perde il pin, e il
     * prossimo messaggio da quella persona ricompare come mittente mai visto e
     * viene rifissato in silenzio. E' indistinguibile da qualcuno che si
     * spaccia per lei, cioe' si riapre la finestra che il pin serviva a
     * chiudere.
     */
    external fun nativeForgetPeer(peer: ByteArray): Int

    /**
     * Keyring serializzato, da cifrare e persistere. Non contiene segreti —
     * sono tutte chiavi pubbliche — ma va comunque protetto: l'elenco dei peer
     * con cui parli e' esattamente il metadato che il progetto cerca di non
     * regalare.
     */
    external fun nativeExportKeyring(): ByteArray?

    /**
     * Stesso formato di [nativeExportKeyring]. Intestazione di 5 byte
     * (1 versione + 4 di conteggio little-endian), poi un record per peer:
     *
     *     pubkey(32) | firstSeenUnix(8, LE) | verified(1) | labelLen(2, LE) | label(labelLen, UTF-8)
     *
     * I record NON hanno lunghezza fissa: l'etichetta e' variabile. Scorrerli
     * assumendo un passo costante e' sbagliato.
     */
    external fun nativeListPeers(): ByteArray?

    /**
     * Fingerprint di una pubkey qualsiasi, gia' formattato. Non richiede che
     * il peer sia nel keyring: serve anche a mostrare la chiave entrante
     * durante un conflitto, che per definizione non e' fissata.
     */
    external fun nativeFingerprintOf(peer: ByteArray): String?
}
