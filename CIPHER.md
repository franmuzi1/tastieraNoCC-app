# keyboard-cipher — integrazione nel fork

Fork di [HeliBoard](https://github.com/HeliBorg/HeliBoard) che cifra il testo
**dentro la tastiera**, prima che entri nell'app di chat. L'app di chat, il suo
server e qualunque scanning lato piattaforma vedono solo ciphertext.

Il core crypto sta in un repo separato (`keyboard-cipher-core` + il ponte
`keyboard-cipher-jni`) e non è incluso qui: questo repo contiene solo il lato
Android.

## Stato: compila, con la funzionalità ancora scheletro

`./gradlew assembleDebug` passa. Verificato **sull'APK**, non sull'esito di
Gradle (vedi sotto perché la distinzione conta):

| Cosa | Esito |
|---|---|
| `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libkeyboard_cipher_jni.so` | presenti, 339–573 KB |
| `Lhelium314/keyboard/cipher/DecryptActivity;` nel dex | presente |
| `Lhelium314/keyboard/cipher/ContactsActivity;` nel dex | presente |
| `CipherCore` nel dex | **assente, ed è corretto** — vedi sotto |

`CipherCore` non è nel dex perché **nessuno lo chiama ancora**: l'unica
occorrenza del nome in tutto il sorgente è il TODO dentro
`DecryptActivity.onCreate`. R8 rimuove le classi irraggiungibili, e questa lo
è. Comparirà nel dex insieme al primo chiamante vero; `proguard-rules.pro` ha
già `-keepclassmembers class * { native <methods>; }` e `-dontobfuscate`,
quindi i nomi dei metodi nativi sopravvivono alla minificazione — che è
l'unica cosa che conta, dato che JNI risolve **per nome**
(`Java_helium314_keyboard_cipher_CipherCore_native...`).

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
| `app/src/main/java/helium314/keyboard/cipher/` | `CipherCore` (binding JNI), `DecryptActivity`, `ContactsActivity` |
| `app/src/main/AndroidManifest.xml` | le due Activity con i loro attributi di sicurezza, e gli intent filter `PROCESS_TEXT` / `SEND` |
| `app/build.gradle.kts` | task `buildCipherCore` che invoca `cargo-ndk` e deposita i `.so` in `jniLibs`, agganciato a `preBuild` |
| `.gitignore` | `app/src/main/jniLibs/` — sono artefatti, non sorgenti |

Il core Rust è cercato in `../tastieraNoCC` rispetto alla radice del repo.
Diverso? `-PcipherCorePath=...` oppure una riga in `gradle.properties`.

Se il core non è affiancato il task viene saltato e il build **prosegue**: a
fallire sarà il caricamento della libreria, dove il messaggio è comprensibile.
Un build rotto per un percorso sbagliato sarebbe molto più difficile da
diagnosticare.

## Cosa manca (e dove)

Lista ordinata per dipendenza. Chi la usa deve verificare **nel codice** se un
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

*Residuo:* **non c'è lo scanner.** Leggere un QR richiede `CAMERA`, e non averne
è la proprietà principale del fork. Il flusso che funziona senza: l'altra
persona inquadra con un lettore QR qualunque, ottiene il testo `kc/…`, e lo
condivide alla nostra Activity dallo share sheet. Se un giorno si aggiunge lo
scanner, `CAMERA` va chiesto **a runtime**, all'apertura dello scanner, mai
come permesso di installazione.

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

## Cosa NON è ancora stato provato

Tutti i percorsi felici sono coperti. Restano scoperti i **negativi** e i
**margini di versione**, che è dove il comportamento è stato progettato con più
cura e verificato di meno:

- **blob corrotto o troncato** → deve dare *"Impossibile decifrare"*, un solo
  messaggio per qualunque causa. Mai provato su dispositivo;
- **versione futura** → deve dire *"aggiorna l'app"*, non *"non è cifrato"*;
- **tier non supportato** → messaggio dedicato;
- **API 23** (il minimo in cui la cifratura esiste): niente StrongBox, niente
  `setUnlockedDeviceRequired`. È il terzo ramo di `CipherKeystore.generate`,
  osservato solo su API 34 senza blocco schermo;
- **API 21–22**, dove la funzione deve dichiararsi non disponibile invece di
  fallire.

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

- **Nessun permesso nuovo.** HeliBoard non ha `INTERNET` ed è la sua proprietà
  principale. Se servirà `CAMERA` per il QR: a runtime, all'apertura dello
  scanner, mai come permesso di installazione.
- **`ACTION_PROCESS_TEXT` non deve mai restituire il plaintext al chiamante.**
  Il contratto di quell'intent prevede `setResult` con un testo sostitutivo, ed
  è l'implementazione naturale — che qui consegnerebbe il chiaro proprio
  all'app di chat da cui il progetto esiste per tenerlo lontano.
- **`FLAG_SECURE` prima di qualunque `setContentView`**, più `noHistory` ed
  `excludeFromRecents`: senza, il sistema salva su disco uno screenshot del
  testo decifrato per la schermata Recenti.

## Licenza e rapporti con upstream

HeliBoard è GPL-3.0 e questo fork lo resta.

`AI_USAGE.md` di upstream chiede esplicitamente di non usare LLM per i
contributi, e questo codice è scritto con assistenza LLM. La cosa non tocca il
diritto di forkare — la GPL lo garantisce — ma **non mandare niente di tutto
questo upstream** sotto forma di PR o issue: è esattamente ciò che quel
documento chiede di non fare.
