# keyboard-cipher — integrazione in MusyBoard

MusyBoard è un fork di [HeliBoard](https://github.com/HeliBorg/HeliBoard) che
cifra il testo **dentro la tastiera**, prima che entri nell'app di chat. L'app
di chat, il suo server e qualunque scanning lato piattaforma vedono solo
ciphertext. La tastiera in sé — layout, correzione, gesti, temi, dizionari — è
di HeliBoard: qui sotto si descrive solo ciò che il fork aggiunge.

Il core crypto sta in un repo separato (`keyboard-cipher-core` + il ponte
`keyboard-cipher-jni`) e non è incluso qui: questo repo contiene solo il lato
Android.

## Stato: funziona, e gira su dispositivo

Il ciclo completo è stato osservato su emulatore: pressione lunga su "cifra" →
la propria presentazione nel campo → "decifra" → contatto fissato con il suo
fingerprint → testo in chiaro + "cifra" → il chiaro sparisce, sostituito dal
blob → "decifra" → mittente, data e testo originale.

| Piano | Copertura |
|---|---|
| core Rust + ponte JNI | 104 + 6 test, clippy pulito, ~187 M input di fuzzing |
| percorsi felici | tutti, sul dispositivo |
| percorsi negativi | blob corrotto, troncato, versione futura, tier ignoto, testo non nostro |
| conflitto di etichetta | tutti e tre gli esiti |
| allegati | giro completo in entrambe le direzioni, contro la CLI del core |
| versioni Android | 22 (non disponibile), 23 (minimo), 34 (con e senza blocco schermo) |

Cosa **non** è stato fatto: un merge da upstream con modifiche vere, e la
riproducibilità dell'APK — che non dipende da noi, vedi in fondo. Lo scanner QR
non è mancante: è escluso, perché richiederebbe `CAMERA`.

`proguard-rules.pro` ha `-keep` su `CipherCore` e `IncomingResult`, e
`-dontobfuscate`: i nomi dei metodi nativi devono sopravvivere alla
minificazione, perché JNI risolve **per nome**
(`Java_helium314_keyboard_cipher_CipherCore_native…`), e R8 rimuoverebbe i
campi di `IncomingResult` non vedendone lettori Kotlin.

### Trappola: un build verde non dimostra che la funzionalità ci sia

Il primo build è passato con exit 0 e un APK da 27 MB **privo delle due
Activity**. Il manifest le dichiarava con nome relativo (`.cipher.DecryptActivity`),
che si risolve contro il namespace del modulo — `helium314.keyboard.latin` —
dando `helium314.keyboard.latin.cipher.DecryptActivity`, che non esiste.
Nessuno se ne lamenta: il manifest accetta il nome, R8 non trova riferimenti
alle classi vere e le toglie dal dex. Da qui due regole:

- **nomi assoluti** nel manifest per tutto ciò che sta fuori da `.latin`
  (HeliBoard fa già così per le proprie Activity, per lo stesso motivo);
- il criterio di verifica è **il contenuto del dex e di `lib/`**, mai
  `BUILD SUCCESSFUL`. Il debug qui ha `isMinifyEnabled = true` (upstream lo
  vuole, per stare sotto i 25 MB di GitHub), quindi R8 gira anche in debug.

## Cosa è già cablato

| Dove | Cosa |
|---|---|
| `app/src/main/java/helium314/keyboard/cipher/` | tutto il codice del fork: `CipherCore` (binding JNI), `CipherIdentity`, `CipherKeystore`, `CipherStorage`, `CipherClipboard`, `CipherActions`, `CipherQr`, `PeerList`, `DecryptActivity`, `ContactsActivity` |
| `app/src/main/AndroidManifest.xml` | le due Activity con i loro attributi di sicurezza, e gli intent filter `PROCESS_TEXT` / `SEND` |
| `app/build.gradle.kts` | task `buildCipherCore` che invoca `cargo-ndk` e deposita i `.so` in `jniLibs`, agganciato a `preBuild` |
| `.gitignore` | `app/src/main/jniLibs/` — sono artefatti, non sorgenti |

Il core Rust è cercato in `../tastieraNoCC` rispetto alla radice del repo.
Diverso? `-PcipherCorePath=...` oppure una riga in `gradle.properties`.

Se il core non è affiancato il task viene saltato e il build **prosegue**: a
fallire sarà il caricamento della libreria, dove il messaggio è comprensibile.
Un build rotto per un percorso sbagliato sarebbe molto più difficile da
diagnosticare.

## La lista di lavoro (esaurita)

Lista ordinata per dipendenza, tenuta come storia delle decisioni. Chi la usa deve verificare **nel codice** se un
punto è già fatto: questo documento può essere indietro rispetto ai commit.

**~~1. Ciclo di vita della chiave.~~ FATTO.** `CipherKeystore` (chiave maestra
AES-256-GCM in Android Keystore), `CipherStorage` (i due blob in
`noBackupFilesDir`, scrittura atomica), `CipherIdentity`
(`ensureReady`/`persistKeyring`/`resetIdentity`).

**~~2. Tasti in toolbar.~~ FATTO.** `ToolbarKey.ENCRYPT`/`DECRYPT`,
`KeyCode.CIPHER_ENCRYPT`/`CIPHER_DECRYPT` (-30000/-30001), le tre mappe di
`KeyboardIconsSet`, `CipherActions`, due righe in `KeyboardActionListenerImpl`.

**~~3. `DecryptActivity`.~~ FATTO.** Decifra davvero, gestisce i sei esiti,
`onNewIntent`, persiste il keyring dopo il pin.

**~~4. Clipboard (via 1).~~ FATTO.** `CipherClipboard` separa la descrizione
(`getPrimaryClipDescription`, non fa comparire il toast di Android 12) dalla
lettura del contenuto, che avviene solo su gesto esplicito. Il tasto "decifra"
prova prima il campo e poi gli appunti; la pressione lunga va dritta agli
appunti.

**Indizio sul tasto "decifra": FATTO.** Il tasto si accende quando negli
appunti c'è qualcosa che ha la forma di un nostro blob.

Il vincolo che ne ha deciso la forma: per saperlo bisogna *leggere* gli
appunti, e su Android 12+ ogni lettura di un contenuto messo da un'altra app fa
comparire il toast di sistema. Farlo per conto nostro a ogni sessione di
digitazione avrebbe fatto sembrare la tastiera un'app che spia la clipboard.
Quindi ci si aggancia alla lettura che `ClipboardHistoryManager` fa **già** per
la cronologia: il controllo costa zero.

*Conseguenza dichiarata:* **con la cronologia clipboard disattivata l'indizio
non si accende.** È il prezzo di non leggere di nascosto, e in questo progetto
è il verso giusto.

Il riconoscimento passa da `nativeLooksLikeOurBlob`, che guarda solo sentinel e
lunghezza minima: nessuna decifratura, nessun accesso al keyring, nessun
effetto collaterale. Il sentinel resta in un posto solo — scriverlo a mano in
Kotlin sarebbe stata una seconda fonte di verità.

Servito anche un `refreshToolbarButtonStates`: i tasti si costruiscono una
volta sola, e senza un ricalcolo l'indizio resterebbe congelato a com'era
quando la striscia è nata.

*Verifica numerica* (l'occhio non basta per un'icona più o meno chiara):
misurata la luminosità media dell'icona rispetto a quella di "cifra" come
riferimento. Con testo qualunque negli appunti **+14,2** (spenta); con un
nostro blob **+1,1** (accesa).

**Apertura automatica anche a tastiera chiusa: FATTO.** `CipherActions.autoDecrypt`
apriva `DecryptActivity` solo con la tastiera a schermo, e il caso più comune —
copiare un blob da una chat con la tastiera chiusa — era proprio quello che non
funzionava.

*Il ragionamento che sembrava ovvio ed era sbagliato.* Da Android 10 un'app
senza finestre visibili non può far partire un'Activity, quindi a tastiera
chiusa la chiamata sarebbe stata ignorata in silenzio e serviva per forza una
notifica da toccare. **Misurato, non è così.** Su Android 14 (emulatore AOSP,
`default_background_activity_starts_enabled=false`, tastiera nascosta —
`mInputShown=false` — e fuoco su un'altra app) `startActivity` dall'IME **apre
la schermata**, senza blocchi in logcat e senza nessun permesso in più. Il
sistema tratta l'IME predefinito come un caso a parte.

Quindi `autoDecrypt` non guarda più `isInputViewShown`: costruisce l'intent e
apre, punto. È più corto di prima.

*Il ripiego che c'è stato e non c'è più.* Per un giro c'è stata una notifica da
toccare, come rete di sicurezza per il caso in cui il sistema rifiutasse
l'avvio: la tastiera provava ad aprire, guardava se qualcuno si era presentato
(`startActivity` non segnala il rifiuto — torna come se fosse andato bene) e
solo in caso contrario postava l'avviso. **Tolto di proposito.** Costava
`POST_NOTIFICATIONS`, e un permesso notifiche su una tastiera che promette
riservatezza è un cattivo segnale a fronte di un ramo che, dove è stato
misurato, non scatta mai. Se un giorno si trovasse una ROM che rifiuta l'avvio,
`git log` di questo file ha l'implementazione già scritta.

*Conseguenza dichiarata:* dove il sistema dovesse rifiutare, **copiare non
produce niente e non lo dice.** Non c'è modo di accorgersene dal codice, ed è il
prezzo accettato. Restano però le altre tre vie, che non dipendono dall'avvio in
background: barra di selezione (`ACTION_PROCESS_TEXT`), share sheet, e il tasto
"decifra" della toolbar.

*Perché non "Mostra sopra le altre app".* Era l'altra strada per l'avvio in
background, ed è il permesso dietro il tapjacking — disegnare sopra un'altra
app, per esempio un finto campo password sopra quello della banca. Si concede da
Impostazioni → Accesso speciale, è permanente e senza ambito. Prenderlo per un
effetto collaterale, e non per la funzione per cui esiste, sarebbe il contrario
di come è costruito il resto: questa app è già l'IME predefinito e tiene in
memoria i messaggi decifrati. Su Android 14+ quell'esenzione è per giunta
ristretta ai casi con una finestra overlay effettivamente visibile — quindi
probabilmente non funzionerebbe nemmeno.

*Il limite che resta*, e non si aggira: tutto questo parte da
`ClipboardHistoryManager`, che vive nel processo della tastiera. **Se quel
processo non è in piedi non esiste nessun ascoltatore**, e copiare non produce
niente. Verificato per caso durante le prove: dopo un `am force-stop` il giro
non scatta più finché la tastiera non torna a servire un campo. In uso normale
il sistema tiene vivo l'IME predefinito, ma non è una garanzia — e vale la pena
sapere che **`am force-stop` sul pacchetto fa anche perdere la selezione come
tastiera predefinita** (il pacchetto passa a `stopped=true` e il sistema ripiega
su un'altra IME abilitata). La sola morte del processo, `kill -9`, non lo fa.


**~~5. Identity card.~~ FATTO.** `CipherActions.insertIdentityCard`, agganciata
alla pressione lunga su "cifra". Passa dal campo e non dagli appunti per
l'asimmetria che governa il progetto: inserire nel campo è nativo per un IME,
leggere no.

*Residuo:* un tocco lungo è poco scopribile. Il punto d'ingresso visibile è la
UI contatti (punto 6); questo è il gesto veloce, non l'unico previsto.

**~~6. UI di `ContactsActivity`.~~ FATTA, tranne il QR.** Elenco peer con
fingerprint monospaziato, etichette (`nativeAssignLabel`), verifica fuori banda
(`nativeMarkVerified`), schermata di conflitto (`nativeConfirmKeyChange`) e
reset identità. `PeerList` decodifica il blob di `nativeListPeers` — record a
lunghezza **variabile**, l'etichetta lo è.

Nelle due schermate delicate l'azione distruttiva sta sul pulsante **negativo**,
contro convenzione e apposta: il posto dove cade il pollice dev'essere quello
che non cambia niente.

**~~QR.~~ FATTO, in mostra.** `CipherQr` più il pulsante *Mostra il codice QR*
nella sezione della propria identità. Nessun permesso nuovo: la generazione non
ne richiede.

**Solo generazione, e ci resta — decisione chiusa.** Leggere un QR richiede
`CAMERA`, e il fork non prende permessi: non averne è la sua proprietà
principale. Non è una funzione mancante in attesa di qualcuno che la completi.

Non serve nemmeno, perché **basta che uno dei due scansioni**: l'altra persona
inquadra il codice con un lettore QR qualunque, ottiene la stringa `kc/…` e la
consegna alla nostra Activity dallo share sheet. Il MITM al primo contatto si
chiude comunque, e la tastiera resta senza permessi.

**~~Voce nelle impostazioni.~~ FATTA e verificata:** Impostazioni → *Contatti*.

**~~7. Cronologia clipboard.~~ FATTO.** `CipherClipboard.markSensitive` /
`isSensitive`, più una riga di guardia in `ClipboardHistoryManager`. Tiene un
**digest SHA-256**, non il testo: un plaintext in un campo statico dell'IME
sarebbe stato esattamente la fuga che il meccanismo esiste per evitare.
`copyPlaintext` non è più dead code e ha il suo pulsante.

*Residuo, per costruzione:* il chiaro resta negli appunti di sistema e
qualunque app col fuoco può leggerlo. `EXTRA_IS_SENSITIVE` (Android 13+)
nasconde solo l'anteprima. Da lì in poi il testo è fuori dal perimetro
dell'app, e il messaggio di conferma lo dice invece di far finta di no.

**8. Prova su dispositivo — PRIMO GIRO FATTO.** Emulatore x86_64, API 34,
senza blocco schermo. Verificato: il `.so` si carica, l'identità viene generata
e scritta (`identity.bin`, 61 byte = 1 versione + 12 IV + 32 segreto + 16 tag),
e dopo un `force-stop` viene **ricaricata e non rigenerata** (stesso hash).

Ha trovato subito un bug che nessuna analisi statica avrebbe preso: vedi sotto.

Secondo giro, **con PIN impostato**: la chiave si crea con
`setUnlockedDeviceRequired` attivo (nessun errore ECDH), e a schermo sbloccato
viene ricaricata intatta. A schermo bloccato Keystore la rifiuta con
`Error::Km(DEVICE_LOCKED)` — vedi sotto, ha trovato il secondo bug.

I tre stati Keystore ora coperti e osservati:

| Dispositivo | Chiave generata | Uso |
|---|---|---|
| senza blocco schermo | terzo tentativo, senza il flag | funziona sempre |
| con blocco schermo, sbloccato | secondo tentativo, col flag | funziona |
| con blocco schermo, bloccato | — | Keystore rifiuta, → `Locked` |

*Ancora da provare su dispositivo:* il ciclo cifra/decifra completo con due
identità, i tasti in toolbar dentro l'IME vero, e il conflitto di etichetta.

## Regressione completa (ultimo giro)

| Cosa | Esito |
|---|---|
| core Rust | 62 test, clippy pulito |
| ponte JNI | 6 test, clippy pulito |
| fuzzing (`decode`, `parse`, `roundtrip`) | ~48 M input in 3 minuti, nessun crash |
| ciclo completo sul dispositivo da stato pulito | card → pin → cifra → decifra, nessun crash |
| dialogo QR nel build minificato | si apre, `ImageView` presente, nessun problema con R8 |

## Backup dell'identità — PROVATO sul dispositivo

Il buco più grave era che perdere i dati dell'app significava perdere la chiave,
in silenzio e per sempre. Ora c'è un file cifrato con una passphrase.

Ciclo completo osservato su emulatore:

| Passo | Esito |
|---|---|
| salvataggio | file di 189 byte scelto dall'utente via Storage Access Framework |
| `pm clear` | identità diversa: `k1zp bokq …` invece di `mssw sqex …` |
| ripristino con passphrase giusta | identità **identica** a prima, contatti tornati |
| `force-stop` e riapertura | ancora quella: scritta su disco, non solo in memoria |
| ripristino con passphrase **sbagliata** | non cambia niente, identità intatta |

Scelte che vale la pena conoscere:

- **Argon2id, non HKDF.** HKDF è veloce apposta ed è giusto per i messaggi; il
  backup invece esce dal dispositivo, quindi chi lo ottiene può provare
  passphrase offline e in parallelo. Qui ogni tentativo costa 64 MiB e tre
  passate — il costo di memoria è il freno vero, perché su GPU è la memoria il
  collo di bottiglia;
- **i parametri stanno nel file e sono autenticati.** Servono per rifare la
  derivazione, ma se fossero modificabili si potrebbero riscrivere a `m=8, t=1`:
  il file resterebbe apribile con la stessa passphrase e provarle tutte
  costerebbe migliaia di volte meno, senza che la vittima se ne accorga;
- **Storage Access Framework**, quindi nessun permesso sullo storage. Chiedere
  accesso a tutto il disco per salvare un file sarebbe sproporzionato, e in
  questo progetto contraddittorio;
- **la passphrase si chiede prima del file.** Chiederla dopo significherebbe
  farla inventare a chi ha già scelto dove salvare e vuole solo finire — cioè
  il modo migliore per ottenere una passphrase pessima;
- **il pulsante che sostituisce l'identità sta sul negativo**, come per il
  conflitto di chiave e per il reset.

*Residuo dichiarato:* la sicurezza del file dipende **solo** dalla passphrase.
Non è un dettaglio da nascondere e infatti è scritto nella schermata.

## Percorsi negativi — PROVATI

Fabbricati alterando i byte del corpo di un messaggio valido (decodifica
z-base-32 → mutazione → ricodifica), poi dati in pasto al tasto "decifra":

| Variante | Cosa appare |
|---|---|
| `body[0] = 2` (versione futura) | *"Messaggio creato con una versione più recente. Aggiorna l'app."* |
| `body[2] = 1` (tier forward-secrecy) | *"Questo messaggio usa una modalità non ancora supportata"* |
| ultimo byte del tag alterato | *"Impossibile decifrare questo messaggio"* |
| ciphertext troncato di 20 byte | *"Impossibile decifrare questo messaggio"* |
| testo qualunque | *"Questo testo non è cifrato"* |

Le due righe che contano sono le ultime tre:

- **corrotto e troncato danno il messaggio identico.** È la regola di opacità:
  il core non distingue "tag non valido" da "ciphertext accorciato", e la UI non
  reintroduce la distinzione travestita da messaggi diversi;
- **la versione futura non dice "non è cifrato".** È la conferma pratica della
  scelta di tenere la versione nel primo byte del corpo e non nel sentinel: col
  sentinel versionato, un blob della v2 non sarebbe stato nemmeno riconosciuto
  come nostro;
- **il testo qualunque non è un errore**, ed è l'esito più comune.

## API 23 — PROVATO

Emulatore x86_64 API 23, cioè il minimo in cui la cifratura esiste.

| Cosa | Esito |
|---|---|
| installazione e avvio | nessun crash |
| `identity.bin` | creato, 61 byte |
| card riconosciuta | *"Nuovo contatto"* + fingerprint |
| `keyring.bin` | scritto, 77 byte |
| errori Keystore o `NoSuchMethodError` | nessuno |

Cosa dimostra davvero: che le chiamate riservate ad API 28+
(`setIsStrongBoxBacked`, `setUnlockedDeviceRequired`) sono **correttamente
protette dai controlli di versione**. Se una guardia fosse sbagliata, qui si
sarebbe visto un `NoSuchMethodError` in faccia, non un degrado silenzioso.

**Ciclo completo dalla tastiera, non solo dalla Activity:** card inserita con la
pressione lunga → *"Nuovo contatto"* + fingerprint → testo cifrato → decifrato
con timestamp e chiaro. Su uno schermo 320×640 mdpi, cioè la geometria più
stretta che il fork supporti: la toolbar ci sta e i due tasti sono distinguibili.

*Nota d'ambiente:* su API 23 non esiste lo storage device-protected, quindi i
preferences stanno in `/data/data/<pkg>/shared_prefs/`. E
`run-as … sh -c 'cat > file'` con stdin da `adb` **si pianta**: passare invece
per `adb push` in `/data/local/tmp` + `run-as … cp`.

**Controllo incrociato che vale più di quanto sembri:** la stessa card ha dato
lo stesso fingerprint `bhai 4o4s ys8g ouie 6u4u x8j5` su API 34 e su API 23,
con un `.so` costruito per un'altra piattaforma. È la proprietà su cui poggia
l'intera verifica di persona: se il fingerprint dipendesse dal dispositivo, due
persone che confrontano il codice vedrebbero valori diversi e concluderebbero
che qualcuno si è interposto.

## API 22 — PROVATO

Sotto il minimo, la cifratura deve **dichiararsi non disponibile**, non fallire.

| Cosa | Esito |
|---|---|
| schermata | *"Cifratura non disponibile"* |
| crash | nessuno |
| `no_backup/cipher/` | **non creata** |

L'ultima riga è quella che conta: il controllo di versione avviene **prima** di
toccare lo storage e Keystore, quindi su un dispositivo dove la funzione non
può esistere non si lascia dietro niente.

*Nota d'ambiente:* su API 22 `uiautomator dump /sdcard/…` dice di aver scritto
il file ma non lo si trova. Usare `/data/local/tmp/`.

## Cosa NON è ancora stato provato

- **merge da upstream con modifiche vere.** Oggi non c'è niente di nuovo da
  `upstream/main`, quindi la tenuta non è provabile; misurata l'esposizione,
  vedi sotto.
- **la build di release su dispositivo.** Ora si costruisce (vedi sotto), ma
  esce non firmata: senza chiave non si installa, quindi non è mai stata
  eseguita.

## Build riproducibile — misurata

Due build da pulito della libreria nativa danno un `.so` **byte per byte
identico** (`sha256` uguale). È la condizione necessaria perché qualcun altro
possa ricostruire il binario e confrontarlo con quello distribuito.

Ci si arriva con `--remap-path-prefix`, impostato dal task Gradle. Senza, il
`.so` incide i percorsi assoluti della macchina che l'ha costruito — otto
occorrenze di `/home/<utente>/.cargo/registry` — che sono le stringhe di
posizione dei `panic!` delle dipendenze (`jni`, `curve25519-dalek`,
`rand_core`, `cesu8`, `cipher`).

Due cose da sapere, perché non sono ovvie:

- **lo strip del debuginfo non le toglie.** Stanno in `.rodata`, non nel
  debuginfo. `strip = "debuginfo"` serve ad altro e resta utile, ma non
  risolve questo;
- **`strip = "symbols"` romperebbe tutto**: i simboli dinamici esportati verso
  la JVM devono restare, perché JNI li risolve per nome.

Verificato dopo il rimappaggio: zero percorsi macchina in tutte e quattro le
ABI dell'APK, 28 simboli `Java_…_native…` intatti, app funzionante.

*Cosa questo NON dimostra:* la riproducibilità **fra macchine diverse**, che
richiede in più la stessa versione di Rust e dello stesso NDK. Quelle F-Droid
le fissa; il rimappaggio toglie la parte che dipendeva da *dove* si compila.

### L'APK invece NON è riproducibile, e la colpa è di R8

Due build complete danno APK diversi. Le voci che cambiano sono `classes.dex`
(372 byte di differenza) e i tre file sotto `META-INF/`, che differiscono solo
di conseguenza — la firma copre il dex.

Isolata la causa con un esperimento: la variante **`debugNoMinify`**, cioè la
stessa app senza R8, produce un dex **byte per byte identico** su due build.
Con R8 no.

Quindi:

- **non è colpa di questo fork.** Riguarda l'intera app, e upstream allo stesso
  modo;
- **non è correggibile da qui.** È il comportamento di R8 con questa versione
  di AGP;
- resta un ostacolo reale se un giorno si vuole una build riproducibile delle
  varianti minificate. Le vie sono due, entrambe fuori da questo repo: fissare
  una versione di R8 che sia deterministica, o distribuire una variante non
  minificata.

Il pezzo che dipende da noi — la libreria nativa — è riproducibile.

## Quanto invade il fork nel codice di HeliBoard

Misurato rispetto al punto di divergenza da upstream, escludendo tutto ciò che
è solo nostro (`cipher/`, `CIPHER.md`, risorse dedicate):

| File | righe |
|---|---|
| `app/build.gradle.kts` | +67 |
| `app/src/main/AndroidManifest.xml` | +67 |
| `ToolbarUtils.kt` | +38 −1 |
| `MainSettingsScreen.kt` | +18 |
| `KeyCode.kt` | +15 |
| `ClipboardHistoryManager.kt` | +12 |
| `KeyboardActionListenerImpl.kt` | +8 |
| `proguard-rules.pro` | +7 |
| `KeyboardIconsSet.kt` | +6 |
| `SuggestionStripView.kt` | +6 |
| **totale** | **+244 −1** |

**Una sola riga rimossa** in tutto il codice di HeliBoard, e sono aggiunte per
il resto. È il profilo che serve per un merge indolore: le aggiunte pure
confliggono solo se upstream tocca esattamente le stesse righe.

Nei soli file Kotlin di upstream le aggiunte sono 103, di cui **56 commenti e
41 di codice**. Il rapporto è voluto: quelle 41 righe sono sparse in codice
altrui, e chi le incontrerà fra sei mesi non avrà il contesto per capire perché
ci sono.

## Gli APK di release vanno costruiti da PULITO

Un `assembleDebug` incrementale produce un APK valido ma **gonfio**: il
packager lascia spazio morto dove stavano voci piu' grandi delle build
precedenti. Misurato: 34,0 MB con 5,8 MB di buchi (il maggiore da 2,8 MB),
contro 28,2 MB e zero buchi dopo `clean`. Stesso contenuto — le voci compresse
pesano uguale — sei megabyte di download in piu' per niente.

Non si vede da nessun avviso e l'APK funziona benissimo, quindi va ricordato:

```
./gradlew clean assembleDebug
```

Come accorgersene: sommare le dimensioni compresse delle voci e confrontarle
con la dimensione del file. Se il file e' molto piu' grande, e' spazio morto.

## Riga di composizione — il chiaro non passa piu' dal campo dell'app

Opzionale, spenta di default: Impostazioni → Preferences → Cifratura → *Scrivi
dentro la tastiera*.

**Cosa chiudeva.** Prima il chiaro veniva digitato nel campo dell'app di chat e
ci restava fino alla pressione del lucchetto. In quella finestra l'app lo vede
per intero — e' il suo `EditText`, riceve ogni battuta, puo' salvarlo come bozza
sul proprio server e intanto annuncia "sta scrivendo". Il progetto esiste per
non consegnare il chiaro all'app di chat, e glielo consegnava lo stesso: per
pochi secondi, e con l'utente convinto del contrario.

**Come e' fatta.** Non c'e' un secondo motore di digitazione: si sostituisce la
sola `InputConnection`. HeliBoard scrive dove gli dice `getCurrentInputConnection`,
quindi basta che quella — a modalita' attiva — sia una `BaseInputConnection` su
un buffer nostro perche' correzione, suggerimenti, cancellazione, cursore e
gesti continuino a funzionare senza modifiche. L'innesto in `LatinIME` e' un
override e un accessore: `getAppInputConnection()` restituisce quella vera, e la
usa solo cio' che deve arrivare davvero all'app — il blob, la presentazione, e
la lettura del campo per decifrare.

**Il tasto invio non fa niente.** E' il punto piu' importante della classe:
inoltrarlo consegnerebbe all'app il comando di spedire mentre il chiaro non e'
ancora cifrato. Finche' non si preme il lucchetto non c'e' niente da spedire.

**Il buffer appartiene a un'app.** Il destinatario e' per package: un testo
cominciato in una conversazione non deve poter essere cifrato in un'altra,
sarebbe cifrato per la persona sbagliata. Quindi il buffer ricorda il package
per cui e' nato e si azzera quando il fuoco passa a un'app *diversa*. Due casi
vanno ignorati, ed entrambi sono costati un giro di prove:

- `inputType == TYPE_NULL` — il fuoco su qualcosa che non e' un campo di testo;
- **le nostre schermate**. Andare a decifrare e tornare indietro passa da
  `onStartInput` col nostro package: senza l'eccezione, il messaggio in corso
  spariva proprio nel flusso piu' comune, leggere e rispondere.

Provato sull'emulatore battendo i tasti veri (`adb shell input text` inietta gli
eventi nell'app e **salta la tastiera**: con quello non si prova niente di tutto
cio'):

| Passo | Esito |
|---|---|
| digitazione | testo nella riga, campo dell'app **vuoto** |
| backspace | cancella nel buffer |
| invio | va a capo, non spedisce, campo dell'app ancora vuoto |
| decifra e ritorno | il testo in composizione e' ancora li' |
| lucchetto | blob nel campo dell'app, riga svuotata |
| decifra del blob | *"ciao mondo"*, integro |
| modalita' spenta | riga assente, altezza identica al pixel, digitazione nel campo come prima |

### Il cursore e' disegnato a mano

La finestra di un IME non prende il fuoco — se lo prendesse lo toglierebbe al
campo dell'app, che e' cio' che la tastiera serve a riempire — e un `TextView`
disegna il caret solo quando ce l'ha. Quindi il cursore di questa riga non e'
quello di sistema: e' una barra disegnata in `onDraw` alla posizione che il
buffer dichiara, con lampeggio a mezzo secondo, fermato quando la riga non e'
visibile o non e' attaccata.

Non e' estetica: in un campo il cui testo non compare da nessun'altra parte,
senza cursore non c'e' modo di sapere dove finira' il prossimo carattere dopo
aver cancellato o spostato.

La posizione viene da `Selection` sul buffer, cioe' dalla stessa fonte che
HeliBoard aggiorna scrivendo: verificato che lo spostamento con lo **swipe sulla
barra spazio** muove il caret e che il carattere successivo entra li' e non in
fondo. Disegnata anche la selezione, riga per riga — su piu' righe non e' un
rettangolo.

**Lo scorrimento non si sottrae.** `onDraw` disegnava a
`totalPaddingTop - scrollY`, che sembra ovvio in una riga che scorre ed e'
sbagliato: la tela arriva a `onDraw` **gia' traslata** di `-scrollX,-scrollY`,
quindi li' si e' gia' in coordinate del contenuto — le stesse di
`Layout.getLineTop`. Sottrarli contava lo scorrimento due volte.

Invisibile per due righe, perche' finche' il testo ci sta `scrollY` vale zero.
La riga e' alta 56dp con 8dp di padding e il testo 16sp: due righe entrano, la
terza no. **Da li' in poi** il cursore si staccava dal testo e tremolava a ogni
tasto, e toccando per spostarlo finiva altrove rispetto a dove compariva —
l'offset del tocco lo calcola `getOffsetForPosition`, che lo scorrimento lo
conta giusto. Stessa correzione per il rettangolo di selezione. Verificato con
un confronto A/B: ricostruita la versione bacata, il cursore lampeggia staccato
in cima alla riga; con la correzione sta subito dopo l'ultimo carattere.

`totalPaddingTop` va bene anche a testo corto: comprende gia' lo scostamento del
`gravity="center_vertical"`, cioe' di quanto `TextView` sposta in basso il testo
quando non riempie la riga.

## Tre guasti trovati usando la tastiera per davvero

Nessuno dei tre si vedeva rileggendo il codice, e il primo rendeva il sistema
inservibile dopo qualche ora d'uso.

### Il destinatario spariva da solo

Il core tiene la scelta del destinatario in memoria, ed e' giusto: e' un crate
senza I/O. Ma la memoria di un IME e' quella di un servizio che Android riavvia
quando gli pare — poca RAM, cambio di tastiera, riavvio del telefono.

Effetto per chi la usa: fissi il destinatario, scrivi, cifri, tutto bene; ore
dopo premi il lucchetto e non succede piu' niente, con un messaggio che ti
chiede di scegliere un destinatario che *avevi gia' scelto*. Sembra una
funzione rotta a caso, ed e' il tipo di guasto che fa abbandonare il sistema
invece che segnalarlo.

`CipherRecipients` scrive la mappa package → chiave in un terzo file, cifrato
dallo stesso Keystore del keyring e con un dominio proprio. Si ripristina dopo
`nativeInit`, mai prima. Non contiene segreti — chiavi pubbliche e nomi di
package — ma e' esattamente il metadato che il progetto esiste per non
regalare: con chi parli, e in quale applicazione.

*Trappola nel provarlo:* `am force-stop` sull'IME attivo fa passare il sistema
alla tastiera AOSP. Il primo test e' stato fatto scrivendo su un'altra
tastiera, e sembrava confermare una diagnosi diversa. Dopo il force-stop va
rimesso `ime set`, e va **verificato** con
`settings get secure default_input_method`.

### La riga copriva la casella dell'app

`wrap_content` piu' `maxLines=3`: a ogni a capo la riga cresceva, la tastiera
con lei, e l'app di chat non sempre rifa' il proprio layout in tempo — la riga
finiva sopra la casella di testo dell'app.

Ora l'altezza e' **fissa** (`cipher_compose_row_height`, 56dp; 40dp in
orizzontale) e il testo scorre, con lo scorrimento che insegue il cursore.
Misurato: il bordo della tastiera resta allo stesso pixel a riga vuota, con
testo, dopo tre a capo e dopo altro testo ancora.

### Cambiare modalita' costava un viaggio nelle impostazioni

Dove si scrive non e' una configurazione che si fa una volta: e' una scelta che
cambia da conversazione a conversazione — con chi ha questa tastiera si scrive
dentro, con tutti gli altri no. Se per cambiarla bisogna uscire dalla chat e
attraversare le impostazioni, non la cambia nessuno.

Quarto tasto, `COMPOSE`, con lo stato *acceso* che riflette la modalita': e' un
interruttore, deve dire in che stato si trova senza doverlo premere. Resta
visibile anche a modalita' spenta, perche' e' il modo per riaccenderla —
`SEND_PLAIN` invece sparisce, che senza la riga non avrebbe niente da
consegnare.

## Due guasti della riga di composizione, e come si vedevano

### La riga copriva la casella dell'app — davvero, e la prima correzione non bastava

L'altezza fissa aveva tolto il problema di quando *cresceva*, ma la riga
restava sopra la casella dell'app **sempre**. Causa in `onComputeInsets`:

    visibleTopY = inputHeight - visibleKeyboardView.getHeight() - stripHeight

Da li' il sistema decide dove finisce l'app e comincia la tastiera. La riga fa
parte della vista dell'IME — quindi entra in `inputHeight` — ma non veniva
sottratta: il sistema calcolava un confine 56dp piu' in basso di quello vero e
disegnava l'app sotto la riga. Coperta la casella, coperti con lei il pulsante
degli allegati e quello del microfono, cioe' meta' di cio' che serve in una
chat.

Ora si sottrae anche `CipherCompose.rowHeight()`.

### Il testo sta in un posto solo

Regola che governa tutto il comportamento della riga: **se la riga e' vuota e
nel campo dell'app c'e' del testo, il primo tasto premuto lo riporta nella
riga.**

Copre tre casi che sembravano diversi e sono lo stesso:

- la bozza che l'app ripristina aprendo la chat;
- il testo scritto prima di accendere la modalita';
- il messaggio consegnato in chiaro con l'aeroplanino e **non ancora inviato**,
  in cui ci si accorge di un refuso. Senza questa regola quel testo restava a
  schermo e nessun tasto lo toccava, perche' cancellazione e cursore lavorano
  sul buffer.

Costa una lettura del campo, e solo finche' il buffer e' vuoto: dal primo
carattere in poi la condizione e' falsa e non si legge piu'. Non e' un controllo
a ogni battuta.

*Caso che resta fuori, per costruzione:* se l'invio automatico e' scattato
davvero, il messaggio e' partito e la casella e' vuota — non c'e' piu' niente da
riprendere, e si corregge con "modifica messaggio" dell'app.

### Il testo gia' nel campo non si poteva cancellare

Apri una chat, l'app ripristina la bozza; oppure avevi scritto prima di
accendere la modalita'. Quel testo resta a schermo e la tastiera non lo puo'
toccare, perche' cancellazione e cursore lavorano sul buffer. Due caselle
visibili e una sola che risponde ai tasti sono peggio che non avere la riga.

`CipherActions.adoptFieldText`, chiamata da `onStartInputViewInternal` — dove
la connessione e' viva, in `onStartInput` no — sposta quel testo nella riga.

**Si adotta solo se il campo si e' davvero svuotato.** Se la cancellazione non
riesce il testo resterebbe in due posti, e al momento di cifrare finirebbe nel
blob *e* accanto ad esso in chiaro.

### Nel frattempo: niente riga sui campi password

Trovato guardando il resto: su un campo password la riga avrebbe mostrato a
schermo cio' che il campo nasconde con i pallini, e l'avrebbe tenuto in un
buffer nostro. Ora su quei campi la modalita' si sospende — e non e' una
perdita, una password non ha nessun bisogno di essere cifrata.

## Forward secrecy

Impostazioni → Cifratura → *Forward secrecy*, **accesa di default**.

La chiave con cui il messaggio viene cifrato smette di esistere appena il
messaggio parte: nell'intestazione viaggia una chiave usa-e-getta al posto
della tua, e la chiave del messaggio nasce da due scambi messi insieme — quello
usa-e-getta e quello con la tua chiave stabile. Il primo fa si' che chi domani
si impossessasse della tua chiave **non riaprirebbe i messaggi gia' mandati**;
il secondo dimostra a chi riceve che sei stato tu, senza firme.

Di conseguenza la tua chiave non compare piu' in chiaro: due tuoi messaggi non
si possono nemmeno piu' legare fra loro guardando il traffico. Dentro la chat
non cambia niente — la piattaforma sa gia' chi scrive dal tuo account — ma un
blob che gira inoltrato, citato o archiviato non porta piu' la tua firma.

### La catena, cioe' la meta' che mancava

Quanto sopra da' meta' della proprieta': chi ottiene la chiave del
*destinatario* riaprirebbe tutto lo stesso, perche' entrambi gli scambi passano
di li'. Serve una chiave temporanea **anche dal lato di chi riceve**, ed e' cio'
che fa la catena.

Ogni messaggio ne porta una nuova, **dentro** il cifrato — non
nell'intestazione: in chiaro sarebbe un identificatore che cambia ogni volta ma
lega fra loro i due capi di una conversazione, cioe' proprio la correlazione che
la chiave usa-e-getta toglie. Chi risponde usa quella, e dal secondo messaggio
in poi nessuna delle due chiavi stabili basta piu' ad aprire niente.

Il primo messaggio non puo' essere cosi' — una chiave dell'altro non ce l'hai
ancora — quindi ripiega sullo schema di sopra, **portando comunque la propria**.
E' cio' che fa partire la catena: senza, non partirebbe mai.

**Il gesto che produce la forward secrecy e' buttare, non cifrare.** Leggendo
una risposta si buttano le chiavi temporanee piu' vecchie di quella usata.
Finche' esistono, i messaggi che le usavano si riaprono; da quando non
esistono, no. Se ne tengono tre per contatto, non una: mandi due messaggi di
fila, l'altro apre il secondo, e il primo deve restare apribile.

**Vale anche per gli allegati.** Un file senza catena sarebbe un buco piu'
grosso di un messaggio senza: una foto vale piu' di una riga di testo, e resta
sul telefono di chi la riceve. Usa lo stesso stato dei messaggi — e' la stessa
conversazione con la stessa persona.

**Tre conseguenze da conoscere,** ed e' il motivo per cui l'interruttore esiste:

1. un messaggio cosi' **non lo apre una versione precedente**;
2. chi riceve deve avere il mittente **gia' fra i contatti**. Senza la chiave in
   chiaro lo riconosce provando i propri contatti uno per uno, e uno
   sconosciuto non e' fra quelli. Lo scambio delle presentazioni era gia' il
   primo passo consigliato; ora e' necessario;
3. **i messaggi si aprono una volta sola.** Niente cronologia: riaprire una
   conversazione vecchia non e' possibile, nemmeno esportando la chat dall'app
   di messaggistica. E' il prezzo della proprieta', non un difetto — e chi lo
   trova troppo caro spegne l'interruttore.

Provato sul dispositivo: primo messaggio con la sola chiave usa-e-getta,
decifrato — mittente riconosciuto per tentativi, con il fingerprint giusto,
benche' la sua chiave non fosse nell'intestazione — e secondo messaggio che usa
gia' la catena. Da riga di comando, con due identita' separate su disco: un
messaggio gia' letto non si riapre dopo che la catena e' avanzata.

## Invio automatico

Con la riga di composizione attiva, dopo aver consegnato il messaggio all'app le
si chiede anche di spedirlo: un tocco invece di due. Impostazioni → Cifratura →
*Invia subito*, acceso di default **in quella modalita' soltanto**.

Vale solo li', e non e' un dettaglio: con la riga attiva i due tasti sono gia'
"manda questo messaggio", perche' il testo non e' mai stato nel campo dell'app.
Senza la riga il lucchetto sostituisce cio' che stai scrivendo, e spedirlo da
solo sarebbe un'altra cosa — irreversibile e non richiesta.

**Perche' non funziona sempre.** "Invia" e' un pulsante dell'app, e una tastiera
non puo' premerlo. L'unica leva e' `performEditorAction`, cioe' l'azione che il
campo dichiara di avere. Nelle chat quel campo e' spesso multiriga e non
dichiara niente, perche' l'invio sta accanto: in Telegram si accende da
Impostazioni → Chat → *Invia con Invio*. Quando non si puo' l'utente lo legge,
invece di restare a chiedersi perche' non e' partito niente.

Si riusa `InputTypeUtils.getImeOptionsActionIdFromEditorInfo`, la stessa
funzione che decide cosa fa il tasto invio — comprese le eccezioni per app note.
Due letture diverse dello stesso campo sarebbero due comportamenti diversi per
lo stesso gesto.

**Non si ricade sul tasto invio simulato.** In un campo multiriga inserirebbe un
a capo dentro il messaggio appena consegnato invece di spedirlo: romperebbe il
blob, e in silenzio.

Provato dove l'azione esiste davvero — il campo di ricerca delle impostazioni di
sistema, che dichiara `IME_ACTION_SEARCH`: testo scritto nella riga, un tocco
sull'aeroplanino, e il testo e' comparso nel campo **e** la ricerca e' partita.

## Firma delle release

Gli APK pubblicati finora sono firmati con la **chiave di debug** della macchina
che li ha costruiti. Va bene per provare e non va bene per distribuire: quella
chiave non e' gestita come un segreto — password predefinita nota, file in
`~/.android` — quindi chiunque ne entri in possesso puo' firmare aggiornamenti
che i telefoni accettano come tuoi. Per una tastiera che cifra e' il tipo di
dettaglio che decide se il sistema vale qualcosa.

`app/build.gradle.kts` ha ora un `signingConfig` per `release` e `nouserlib`.
Valori da `keystore.properties` nella radice del progetto, oppure dalle
variabili d'ambiente `KC_KEYSTORE`, `KC_KEYSTORE_PASSWORD`, `KC_KEY_ALIAS`,
`KC_KEY_PASSWORD`. Il file e i keystore sono in `.gitignore`.

**Senza chiave il build non fallisce**: produce un APK non firmato. Chi clona il
repo deve poterlo costruire, e rompergli il build per una chiave che non e' sua
sarebbe un ostacolo senza scopo. Un APK non firmato non si installa comunque,
quindi l'errore arriva — ma arriva dove si capisce cos'e'.

Il blocco sta **fuori** da `android { }`: li' dentro `java` e' l'estensione
Gradle e non il package, quindi `java.util.Properties` non si risolve.

Creare la chiave (interattivo, cosi' la password non finisce nella cronologia
della shell):

```
keytool -genkeypair -v -keystore ~/keyboard-cipher-release.jks \
    -alias tastiera -keyalg RSA -keysize 4096 -validity 10000
```

Verificato con un keystore usa-e-getta: `assembleRelease` produce un APK di
23,4 MB firmato con quella chiave, con le quattro ABI del `.so` e le classi
`cipher` nel dex.

### La conseguenza che va detta agli utenti PRIMA

Android rifiuta di installare un APK con una firma diversa sopra uno gia'
installato. Il passaggio dalla chiave di debug a quella di release **rompe
l'aggiornamento**: chi ha una versione da 0.1.0 a 0.1.3 dovra' disinstallare.

E disinstallare **distrugge l'identita'**: chiave privata e keyring stanno in
`noBackupFilesDir` e in Keystore, e spariscono entrambi. Da quel momento i
contatti vedrebbero un cambio di chiave, cioe' esattamente il segnale che il
sistema usa per dire "qualcuno si sta spacciando per lui".

Ordine obbligatorio, da scrivere nelle note della prima release firmata:

1. Contatti → **salva il backup dell'identita'** (file cifrato con passphrase);
2. disinstalla la versione vecchia;
3. installa quella nuova;
4. Contatti → **ripristina** dal backup.

Da li' in poi gli aggiornamenti tornano normali, per sempre — a patto di non
perdere la chiave di firma. Persa quella, non esiste modo di aggiornare l'app
di nessuno: si ricomincia da un pacchetto diverso.

## Impostazioni: una categoria sola, e un interruttore generale

`Impostazioni → Cifratura` (`CipherScreen`) raccoglie interruttore generale,
modalita' di scrittura e contatti. Prima le preferenze stavano fra quelle
generali e i contatti erano una voce a se': la funzione che distingue questa
tastiera sembrava un dettaglio di configurazione.

Le due preferenze stanno in `CipherSettings`, non in `Settings`/`Defaults` di
HeliBoard: sono di una funzione che upstream non ha, e ogni riga aggiunta a un
file di upstream e' un conflitto in attesa al prossimo merge.

`cipher_enabled` spento fa comportare il fork come HeliBoard: i tasti spariscono
dalla toolbar, la riga non c'e', e `CipherActions` rifiuta comunque ogni codice
— un tasto puo' arrivare da una scorciatoia rimasta in un profilo salvato.
Il filtro **non** tocca le preferenze della toolbar: quelle sono scelte
dell'utente, e chi riaccende la cifratura deve ritrovare la barra com'era.

### Il tasto "consegna in chiaro"

Aeroplanino, terzo tasto, visibile **solo** in modalita' composizione: senza
quella riga il testo e' gia' nel campo dell'app, quindi non avrebbe niente da
consegnare.

Serve al caso banale e frequente che altrimenti costringerebbe a spegnere la
modalita': scrivere a chi non ha questa tastiera. Una funzione che si aggira
dalle impostazioni e' una funzione che viene spenta e mai piu' riaccesa.

Consegna il testo al campo e poi, **se l'invio automatico e' acceso**, chiede
all'app di spedirlo, come il lucchetto. Chi vuole rileggere prima di mandare
spegne *Invia subito*.

L'icona non e' un lucchetto aperto: accanto a uno chiuso si distingue male a
colpo d'occhio, e questo e' il tasto che consegna il chiaro.

*Aggiunto anche a chi aggiorna.* Le preferenze esistenti non vengono
sovrascritte dai default, quindi il tasto viene inserito quando la preferenza
salvata non lo nomina affatto. Un tasto messo a `false` dall'utente resta a
`false`: quella e' una scelta, e il nome nella preferenza la registra.

### Trappola: `reloadKeyboard` non ricostruisce la striscia

I tasti in toolbar si costruiscono **una volta**, quando nasce
`SuggestionStripView`. `KeyboardSwitcher.reloadKeyboard()` rifa' la tastiera e
lascia la striscia com'era: il tasto nuovo compariva solo dopo aver cambiato
tastiera e essere tornati indietro, cioe' l'interruttore sembrava non
funzionare. Serve `setThemeNeedsReload()`, che nasconde e rimostra la finestra.

Provato da installazione pulita (`pm clear`), che e' l'unico stato in cui i
default valgono davvero:

| Stato | Striscia | Riga |
|---|---|---|
| default (cifratura on, composizione off) | due lucchetti | assente |
| composizione on | due lucchetti + aeroplanino | presente |
| cifratura off | nessun tasto della cifratura | assente, altezza di prima |

E il giro dell'aeroplanino: testo nella riga → tocco → il testo compare nel
campo dell'app in chiaro, la riga si svuota.

## L'altezza della tastiera non e' cambiata — misurata

Sospetto ragionevole, perche' i due lucchetti fissati stanno nella striscia dei
suggerimenti e sembra che debbano occupare spazio. Non lo occupano: stanno in
`pinned_keys`, che e' alto `match_parent` dentro una striscia la cui altezza la
decide il layout della tastiera.

Misurato sullo stesso build, cambiando solo `pinned_toolbar_keys`, leggendo il
bordo dallo screenshot invece che a occhio:

| Configurazione | Bordo striscia | Bordo tasti | Altezza totale |
|---|---|---|---|
| due lucchetti fissati (default nostro) | y=1431 | y=1541 | 909 px |
| nessun tasto fissato | y=1431 | y=1541 | 909 px |

Identiche al pixel. Il fork non tocca `dimens.xml`, i layout, ne' il codice che
calcola l'altezza — il diff contro upstream lo conferma. Chi vede la tastiera
piu' alta dell'originale sta confrontando **due installazioni diverse**: il
fork ha `applicationIdSuffix .debug`, quindi convive con HeliBoard invece di
sostituirlo, e parte con le impostazioni di fabbrica mentre l'altra ha quelle
gia' regolate dall'utente. La leva e' Impostazioni → Aspetto → altezza della
tastiera.

Cio' che cambia davvero e' la **larghezza** disponibile ai suggerimenti: due
posti in meno, dichiarati e voluti.

## Come far girare l'emulatore qui

Roba scoperta a fatica, per non ripeterla:

```
sdkmanager "emulator" "system-images;android-34;default;x86_64"
avdmanager create avd -n cipher34 -k "system-images;android-34;default;x86_64" -d pixel_5
# lanciarlo STACCATO, altrimenti viene ucciso come figlio della sessione
cd /tmp && setsid nohup $ANDROID_HOME/emulator/emulator -avd cipher34 \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -no-snapshot -memory 2048 > /tmp/emu.log 2>&1 < /dev/null & disown
```

- **L'ambiente si svuota fra una sessione e l'altra.** Trovato due volte:
  `javac` assente (c'e' solo un JRE in `/usr/lib/jvm`, e Gradle fallisce con
  *"does not provide the required capabilities: [JAVA_COMPILER]"*) e
  `cargo-ndk` sparito da `~/.cargo/bin`. Rimedi, senza root: un JDK da
  Adoptium scompattato in `~/jdks` e `JAVA_HOME` puntato li'; e se i `.so` in
  `jniLibs` sono gia' buoni, `-x buildCipherCore` salta cargo del tutto.
  Prima di concludere che qualcosa e' rotto nel progetto, verificare che gli
  attrezzi ci siano ancora.
- **Va lanciato staccato** (`setsid` + `disown`). Lanciato come processo figlio
  della sessione viene terminato, e il crash **azzera i dati dell'AVD**: APK,
  PIN e identità vanno rifatti.
- La macchina ha ~7 GB: fermare il daemon Gradle (`./gradlew --stop`) prima di
  avviare l'emulatore, altrimenti la memoria non basta.
- `locksettings set-pin 1234` per il caso col blocco schermo.

### La trappola che costa più tempo: utente bloccato

**Dopo ogni boot il PIN va inserito**, altrimenti quasi tutto fallisce in modi
che sembrano bug dell'app:

```
adb shell input keyevent 224
adb shell input swipe 540 1800 540 600
adb shell input text 1234 && adb shell input keyevent 66
```

Come riconoscere lo stato, prima di perderci un'ora:

| Sintomo | Cosa sembra | Cos'è davvero |
|---|---|---|
| `am start` → *"Activity class does not exist"* | classe rimossa da R8 | i componenti non direct-boot-aware sono filtrati |
| `run-as ... ls` → *"No such file or directory"* | dati dell'AVD persi | storage credential-encrypted non montato |
| `resolve-activity` → *"No activity found"* | manifest sbagliato | idem |

La conferma è `dumpsys window | grep isKeyguardShowing` e la home risolta: se è
`com.android.settings/.FallbackHome`, l'utente **non** è sbloccato. Con l'utente
sbloccato la home diventa il launcher vero e i file ricompaiono da soli — non
erano mai spariti.

### Bersaglio per far partire l'IME

Il servizio parte solo quando un campo di testo prende il fuoco, e l'immagine
AOSP non offre un campo raggiungibile via `am start`. Serve un APK
usa-e-getta con un solo `EditText`, **fuori dal prodotto**. Si costruisce senza
Gradle, in cinque passaggi, con quello che c'è già nell'SDK:

```
javac --release 8 -cp $SDK/platforms/android-36/android.jar -d classes Main.java
d8 --lib $SDK/platforms/android-36/android.jar --output . classes/.../Main.class
aapt2 link -I .../android.jar --manifest AndroidManifest.xml -o base.apk
zip -qj base.apk classes.dex
zipalign -f 4 base.apk aligned.apk && apksigner sign --ks ks.jks ... aligned.apk
```

Verificato: con quel bersaglio in primo piano il processo
`helium314.keyboard.debug` parte e serve il campo, senza crash.

### Per vedere i tasti in toolbar

Sono disattivati per default. Vanno scritte due preferenze in
`/data/user_de/0/<pkg>/shared_prefs/<pkg>_preferences.xml` (separatori: `|` fra
voci, `:` fra chiave e valore):

```xml
<string name="toolbar_keys">ENCRYPT:true|DECRYPT:true|SETTINGS:true|...</string>
<string name="toolbar_mode">TOOLBAR_KEYS</string>
```

Due inciampi:
- **l'IME attivo non si lascia terminare** con `am force-stop`: il sistema lo
  riavvia subito e i preferences restano quelli vecchi. Per farglieli rileggere
  si cambia tastiera e si torna indietro (`ime set` sull'IME AOSP e poi sul
  nostro);
- all'inizio la striscia è occupata dall'avviso sui contatti, che copre la
  toolbar. Va chiuso rispondendo al dialogo.

### `uiautomator` non basta, lo screenshot sì

`uiautomator dump` **non vede la finestra dell'IME** e **non espone il testo
dell'`EditText`** del bersaglio: restituisce `text=""` anche quando il campo è
pieno. Chi si fida di quel dump conclude che non è successo niente — è successo
due volte qui. La verifica affidabile è `screencap` più una lettura
dell'immagine.

### Ciclo completo osservato

Pressione lunga su "cifra" → nel campo dell'app compare `kc/` seguito da un
blob z-base-32. È l'intera catena che gira per davvero:

    toolbar → KeyCode.CIPHER_IDENTITY_CARD → KeyboardActionListenerImpl
    → CipherActions.insertIdentityCard → CipherIdentity.ensureReady
    (Keystore + storage + nativeInit) → CipherCore.nativeIdentityCard
    → commitText

Pressione breve su "cifra" senza destinatario fissato emette un toast e **non**
tocca il campo, che è il comportamento voluto: il destinatario non si indovina.

**Ciclo completo osservato sul dispositivo:**

1. pressione lunga su "cifra" → `kc/` + blob nel campo (identity card);
2. "decifra" → `DecryptActivity` mostra *"Nuovo contatto"* e il fingerprint
   `bhai 4o4s ys8g ouie 6u4u x8j5` — 24 caratteri in 6 gruppi da 4, il formato
   congelato dalla decisione D. `keyring.bin` compare su disco (77 byte);
3. testo in chiaro + "cifra" → il chiaro sparisce, sostituito da `kc/` + blob;
4. "decifra" → fingerprint del mittente, *"Scritto il … (secondo il mittente)"*
   e il testo originale.

`screencap` **fallisce** sulle schermate di `DecryptActivity`: è `FLAG_SECURE`
che fa il suo lavoro. Il testo resta leggibile via accessibilità, che
`FLAG_SECURE` non copre — ed è il motivo per cui un accessibility service è
escluso dal progetto.

### UI contatti, osservata

Impostazioni → **Contatti** (la voce compare dopo "Toolbar") apre
`ContactsActivity`, che mostra la propria identità, l'elenco dei peer con
fingerprint e data di primo avvistamento. Toccando un peer: *Dai un nome* e
*Ho confrontato di persona*.

Assegnata l'etichetta "Marco", la lista si aggiorna e `keyring.bin` passa da 77
a **82 byte** — esattamente i 5 caratteri del nome. La persistenza si misura,
non si presume.

### QR — verificato fuori dal dispositivo

`FLAG_SECURE` impedisce di catturare il QR a schermo, quindi la verifica è
stata fatta sulla JVM con **gli stessi parametri del codice** (livello M,
margine 2, ISO-8859-1): codifica e ridecodifica, confrontando col testo di
partenza.

| Caso | Esito |
|---|---|
| card vera, 195 caratteri | riletta identica |
| card **massima**, 445 caratteri (`CARD_MAX_BODY` = 276 byte) | riletta identica |

Il secondo caso non è teoria: il riempimento della card è **casuale**, quindi
la lunghezza cambia a ogni generazione. Se il caso lungo non entrasse nel QR,
la funzione fallirebbe a caso su alcune card e non su altre — il tipo di guasto
che si scopre dall'altra parte del tavolo, mentre due persone stanno cercando
di verificarsi a vicenda.

### Conflitto di etichetta — ESEGUITO

Il percorso di sicurezza più importante, provato sul dispositivo con tre
identità fabbricate in sequenza (A e B come peer, C come osservatore):

| Passo | Esito |
|---|---|
| card A sotto C → *Nuovo contatto*, fingerprint `bhai 4o4s …` | peer fissato |
| etichetta "Marco" su A | assegnata |
| card B sotto C → fingerprint `85nt 51zz …` | secondo peer fissato |
| etichetta "Marco" su B | **conflitto**: *"Questo nome è già di un'altra chiave"* |
| *Non cambiare nulla* | hash di `keyring.bin` **identico**, "Marco" resta su A |
| ritento e scelgo *È la sua chiave nuova* | l'etichetta si sposta su B, e il segno di verifica sparisce |

Le due cose che dovevano succedere sono successe: il default non modifica
niente, e la sostituzione azzera `verified` — una chiave nuova non è stata
confrontata fuori banda, per definizione.

Le due card avevano lunghezze diverse (195 e 171 caratteri): è il riempimento
casuale che impedisce di isolare le presentazioni con una regex sulla lunghezza.

*Limite della verifica:* il **corpo** del dialogo — i due fingerprint affiancati
e le due letture possibili — non è esposto dall'accessibilità, e `screencap` è
bloccato da `FLAG_SECURE`. Titolo e pulsanti sono osservati; il testo in mezzo
no.

### Ricetta (usata sopra)

Serve **un'identità con due peer distinti**, e il solo modo
di fabbricare identità nuove senza toccare il prodotto è `pm clear` (i file
sotto `no_backup` spariscono, la chiave in Keystore no, quindi al riavvio ne
nasce una nuova).

1. lunga pressione su "cifra" → cattura la card dal log (vedi sotto) = **A**;
2. `pm clear` → nuova identità; cattura la sua card = **B**;
3. `pm clear` → identità **C**, quella che farà da osservatore;
4. sotto C: incolla la card A nel campo, "decifra" → peer A fissato; nominalo
   *Marco*;
5. sotto C: stessa cosa con la card B, e nominalo *Marco* → **conflitto**.

Attenzione: `pm clear` cancella anche i preferences, quindi i tasti in toolbar
vanno riabilitati ogni volta.

**Catturare un blob:** `uiautomator` non espone il testo dell'`EditText`, quindi
il bersaglio ha un `TextWatcher` che lo scrive nel log. Si legge con
`adb logcat -d -s IMETARGET`. È l'unico modo di rigiocare un blob prodotto
prima.

## Cosa ha insegnato la prima esecuzione

**`setUnlockedDeviceRequired(true)` fallisce alla *generazione* della chiave se
il dispositivo non ha un blocco schermo**, con
`Failed to handle super encryption: User ECDH key missing`. Non all'uso: alla
generazione. Senza un terzo tentativo di fallback la cifratura sarebbe stata
semplicemente non disponibile per chiunque non tenga un PIN sul telefono.

Non si perde nulla di reale nel fallback: quel flag protegge i dati *mentre il
dispositivo è bloccato*, e un dispositivo senza blocco schermo non è mai
bloccato.

**Decifrare dalla toolbar attribuiva il destinatario all'app sbagliata.**
`DecryptActivity` deduceva il package chiamante da `referrer`; per la via
toolbar il chiamante è **la tastiera stessa**, quindi il destinatario finiva
registrato sotto `helium314.keyboard` invece che sotto l'app di chat. Effetto
osservato: dopo aver decifrato, cifrare nella stessa conversazione rispondeva
*"scegli prima un destinatario"* e lasciava il testo in chiaro. Cioè la regola
che CLAUDE.md chiama *"la leva che rende automatico il caso dominante"* non
scattava mai per la via principale.

Ora l'IME passa il package dell'editor in un extra. L'extra è onorato **solo se
a chiamare è questa stessa app**: l'Activity è esportata, quindi senza quel
controllo un'app qualsiasi potrebbe spostare il destinatario corrente di
un'altra conversazione, e la cifratura successiva andrebbe alla persona
sbagliata senza che nulla lo segnali.

**Uno schermo bloccato non è un'identità corrotta.** Con una chiave generata
con `setUnlockedDeviceRequired`, a schermo bloccato Keystore rifiuta con
`Error::Km(DEVICE_LOCKED)`. Dall'interno di `CipherKeystore` quel rifiuto è lo
stesso `null` di un blob manomesso, e veniva presentato come "la tua identità
non è decifrabile" — il cui unico rimedio offerto è `resetIdentity`. Si sarebbe
invitato l'utente a **distruggere irreversibilmente la propria identità per una
condizione che passa premendo un tasto.**

`unreadableOrLocked` classifica il fallimento consultando `isDeviceLocked`, e lo
fa **dopo** il tentativo, non prima: una chiave generata su un dispositivo senza
blocco schermo non ha quel vincolo e funziona anche a schermo bloccato, quindi
rifiutare in anticipo bloccherebbe un caso legittimo.

*Limite della verifica:* il rifiuto `DEVICE_LOCKED` è confermato nel logcat, ma
il messaggio che l'utente vede in quello stato non è osservabile via adb —
l'Activity resta dietro il keyguard. Quella parte è verificata per ispezione,
non per esecuzione.

**I file non stanno in device-protected storage**, malgrado
`defaultToDeviceProtectedStorage="true"` nel manifest. Stanno in
`/data/user/0/<pkg>/no_backup/cipher/`, cioè credential-encrypted — il che è
meglio, ma il commento nel codice diceva il contrario ed è stato corretto.

## Procedura per una sessione automatica

Se stai riprendendo questo lavoro senza contesto:

1. leggi `tastieraNoCC/CLAUDE.md` — è il documento normativo, e le sue
   decisioni non si rimettono in discussione;
2. prendi il **primo** punto non fatto della lista qui sopra, verificandolo nel
   codice e non nel documento. Uno solo, per intero;
3. compila con `./gradlew assembleDebug`;
4. **verifica sul dex, non sull'esito di Gradle** (vedi la trappola più su):
   che le classi ci siano e che chiamino davvero il core;
5. commit e push su `cipher`, messaggio in italiano. Mai `master`, mai
   `upstream`, mai force-push;
6. aggiorna questo file e `tastieraNoCC/HANDOFF.md`.

Se l'ambiente non ha SDK/NDK e non riesci a compilare: implementa comunque, ma
dichiaralo nel commit e qui. Codice non verificato dichiarato tale vale; codice
non verificato spacciato per verificato no.

## Vincoli da non violare

- **Zero permessi, `CAMERA` compresa.** HeliBoard non ha `INTERNET` ed è la sua
  proprietà principale. Il QR si mostra e non si scansiona: decisione chiusa,
  non un residuo. Se una sessione futura trova comodo aggiungere lo scanner,
  non è una svista da correggere.
- **`ACTION_PROCESS_TEXT` non deve mai restituire il plaintext al chiamante.**
  Il contratto di quell'intent prevede `setResult` con un testo sostitutivo, ed
  è l'implementazione naturale — che qui consegnerebbe il chiaro proprio
  all'app di chat da cui il progetto esiste per tenerlo lontano.
- **`FLAG_SECURE` prima di qualunque contenuto**, più `excludeFromRecents`:
  senza, il sistema salva su disco uno screenshot del testo decifrato per la
  schermata Recenti.
- **`noHistory` NON c'è più su `DecryptActivity`, e non va rimesso.** La
  proprietà — uscendo, il chiaro non si ritrova tornando indietro — la fa
  `onStop`, che chiude a mano. Il flag chiudeva la finestra anche mentre il
  selettore "dove salvo" era davanti, e al ritorno non c'era più nessuno a
  scrivere: l'allegato veniva salvato di **zero byte** con scritto "salvato".
  Vedi la sezione sugli allegati.

## Licenza e rapporti con upstream

HeliBoard è GPL-3.0 e questo fork lo resta.

`AI_USAGE.md` di upstream chiede esplicitamente di non usare LLM per i
contributi, e questo codice è scritto con assistenza LLM. La cosa non tocca il
diritto di forkare — la GPL lo garantisce — ma **non mandare niente di tutto
questo upstream** sotto forma di PR o issue: è esattamente ciò che quel
documento chiede di non fare.

## Allegati — il giro completo, PROVATO

Il percorso era scritto e mai eseguito da capo a fondo. Fatto con `kc` (la CLI
del core) come seconda persona, così il giro attraversa Android, JNI e core
invece di rileggere sé stesso.

**Telefono → CLI.** Graffetta → elenco contatti in modalità scelta → contatto →
selettore documenti → in cache compare `kc-<8 esadecimali>.kc`, 151 byte per 35
di testo. `kc openfile` lo apre: mittente fissato per TOFU, nome
`prova-allegato.txt`, tipo, data. Contenuto **identico** all'originale.

**CLI → telefono.** `kc sealfile` → l'app decifra, e il selettore "dove salvo"
propone già il nome originale, che viaggia dentro il cifrato. Il file salvato
coincide byte per byte.

### Il difetto che ha trovato: salvataggio di zero byte

Alla prima esecuzione il file salvato era **vuoto**, con scritto "salvato".
Non era la crittografia: `noHistory` sull'Activity chiude la finestra appena
esce di scena, e il selettore "dove salvo" la fa uscire di scena. Al ritorno
non c'era più nessuno a scrivere: il documento veniva creato dal sistema e
restava vuoto.

Corretto togliendo `noHistory` e chiudendo in `onStop`, con l'eccezione per la
finestra in cui il selettore è davanti (`inAttesaDelSelettore`). Verificate
tutte e due le metà: il file salvato è integro, e uscendo dalla schermata la
finestra col chiaro sparisce comunque — zero riferimenti dopo Home.

**Regola che ne esce:** un'Activity che mostra segreti e apre un selettore non
può usare `noHistory`. La proprietà si ottiene a mano; il flag no.

### Un termometro rotto

La prima verifica sembrava dire che il copia non funzionasse: copiavo e
incollavo, e non compariva niente. Falso — **incolla della toolbar è un finto
CTRL+V**, e la riga di composizione non gestiva quell'evento. Misurando invece
sulla cronologia appunti, il testo copiato c'era eccome. Il tasto incolla è poi
stato fatto funzionare davvero (`CipherCompose.incolla`).

## La striscia: cosa compare, e quando

I tasti della cifratura esistono **solo con la riga di composizione accesa**.
Senza quella riga la tastiera è HeliBoard più il solo interruttore per
riaccenderla.

Non è estetica: senza la riga, "cifra" prende ciò che c'è nel campo dell'app e
lo manda al destinatario **ricordato per quell'app**, scelto da solo. Cifrare
per una persona che non hai indicato in quel momento è il fallimento peggiore
che questo sistema possa produrre, e stava dietro un tocco solo. Con la riga
accesa il destinatario è scritto lì accanto mentre scrivi.

*Conseguenza dichiarata:* sparisce anche "decifra". Con la riga spenta un
messaggio in arrivo si apre dall'apertura automatica alla copia, dal menu di
selezione del testo o dallo share sheet.

L'ordine dei tasti è letto **da destra**, da dove sta il pollice: consegna in
chiaro, cifra, decifra, contatti, immagine, allega, appunti. La striscia si
disegna da sinistra, quindi la lista in `defaultPinnedToolbarPref` è
all'incontrario.

## Selezione, copia e incolla nella riga di composizione

Nella riga valgono i tre gesti di qualunque campo: tocco per il cursore,
pressione lunga per la parola, trascinamento per un pezzo. Vanno scritti a mano
(`CipherComposeView.onTouchEvent`) perché un `TextView` senza fuoco non li fa, e
la finestra di un IME il fuoco non lo prende.

**Il pezzo che fa funzionare il cancella non è la selezione, è dirla a
HeliBoard.** Per un campo vero è il sistema a chiamare `onUpdateSelection`; qui
il buffer è nostro e nessuno la chiamerebbe. Senza quella chiamata il tasto
cancella continuava a togliere un carattere per volta con mezza frase
evidenziata a schermo. La chiamata è **in differita** (`row.post`): "seleziona
tutto" arriva da dentro HeliBoard, e richiamarlo subito lo farebbe rientrare in
sé stesso mentre sta leggendo il testo.

**Copia e taglia sono bloccati** finché nella riga c'è del chiaro
(`CipherCompose.copiaDelChiaroVietata`). Negli appunti il testo lo legge l'app
che ha il fuoco — cioè proprio l'app di chat — e resta nella cronologia appunti
su disco: sarebbe lo stesso buco che la riga esiste per chiudere. C'è
l'interruttore in Impostazioni → Cifratura, perché il divieto ha un costo vero.

**L'invio non adotta.** La riga si riprende il testo rimasto nel campo dell'app
al primo tasto premuto, e l'invio era uno di quelli: si premeva la freccia e il
messaggio tornava dentro la tastiera invece di partire — cioè sul gesto
immediatamente successivo a "consegna in chiaro", ogni volta. Ora invio,
shift+invio e le azioni avanti/indietro sono escluse; cancellazione e lettere
no, perché correggere un refuso è la ragione per cui l'adozione esiste.

## Le schermate: un solo aspetto, e perché non si fotografano

Contatti, scelta del destinatario e testo decifrato usano gli stessi pezzi
(`CipherUi.kt`), lo stesso tema e gli stessi dialoghi delle impostazioni. Prima
erano tre approssimazioni diverse dello stesso elenco, costruite con viste a
mano.

Tutte e tre hanno `FLAG_SECURE`, quindi **la cattura schermo dà un'immagine
vuota**: per guardarle esistono anteprime Compose con dati finti
(`AnteprimaContatti`, `AnteprimaDestinatario`, `AnteprimaDecifrato`), che non
toccano il keyring. Vanno aperte con la variante `debugNoMinify`, perché R8
toglie ciò che nessuno chiama per nome:

```
adb shell am start -n helium314.keyboard.debug/androidx.compose.ui.tooling.PreviewActivity \
  -e composable helium314.keyboard.cipher.CipherUiKt.AnteprimaDecifrato
```

**Una chiave mai vista porta dritti al nome.** Incollando una presentazione
nuova si aprono i contatti con il dialogo "dai un nome" già pronto su quella
chiave: una chiave senza nome è un contatto che non si riconosce, e "la chiave
di Marco è cambiata" è una frase che esiste solo se Marco ha un nome.

## La build di release non esisteva

Mai costruita fino ad allora, e non si costruiva: `viewpager2` si tira dietro
`fragment:1.1.0`, e con quella sul classpath `lintVitalRelease` blocca la
variante di release — 264 errori, tutti su `registerForActivityResult`, che
vuole almeno la 1.3.0. A runtime non sarebbe successo niente: la regola guarda
cosa c'è compilato, non chi lo usa.

Risolto alzando la dipendenza invece di zittire il controllo. Ora la release si
costruisce: 24 MB contro i 33 della debug, senza il flag `debuggable`. Resta
**non firmata** finché non esiste un keystore.

**Da sapere prima di passarci:** la release ha `applicationId`
`helium314.keyboard` (senza `.debug`), quindi si installa **accanto** alla
build attuale e non al suo posto. Identità e contatti non si portano dietro da
soli: servono esportazione e importazione del backup.
