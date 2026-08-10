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

*Residuo:* nessun indizio visivo che negli appunti ci sia qualcosa da
decifrare. Servirebbe rivalutare lo stato del pulsante a ogni cambio di
clipboard, e `setToolbarButtonsActivatedStateOnPrefChange` si attiva sui
preferences, non sulla clipboard. Il gancio esiste — `ClipboardHistoryManager`
ha già un `OnPrimaryClipChangedListener` — ma è codice di HeliBoard, quindi va
pesato contro il costo nei merge.

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

*Residui:*
- **il QR non c'è.** È l'unica cosa che chiude il MITM al primo contatto, che il
  TOFU da solo non chiude. Mostrarlo non costa permessi; scansionarlo richiede
  `CAMERA`, e allora va chiesto a runtime all'apertura dello scanner, mai
  all'installazione;
- **manca la voce nelle impostazioni.** Oggi a `ContactsActivity` si arriva solo
  dal pulsante dentro `DecryptActivity`, cioè solo mentre si guarda un
  messaggio. Serve l'ingresso dalle impostazioni della tastiera, che è quello
  previsto e l'unico che si trova quando non si sta leggendo niente.

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

**Non ancora risolto:** guidare l'IME via adb. Il servizio parte solo quando un
campo di testo prende il fuoco, e le due Activity di impostazioni di HeliBoard
non si avviano (`am start` dice "does not exist" benché le classi siano nel dex
e nella resolver table del sistema). Causa non determinata, ed è codice di
upstream. La via praticabile è un APK usa-e-getta con un solo `EditText`, fuori
dal prodotto, da usare come bersaglio per `uiautomator`.

## Cosa ha insegnato la prima esecuzione

**`setUnlockedDeviceRequired(true)` fallisce alla *generazione* della chiave se
il dispositivo non ha un blocco schermo**, con
`Failed to handle super encryption: User ECDH key missing`. Non all'uso: alla
generazione. Senza un terzo tentativo di fallback la cifratura sarebbe stata
semplicemente non disponibile per chiunque non tenga un PIN sul telefono.

Non si perde nulla di reale nel fallback: quel flag protegge i dati *mentre il
dispositivo è bloccato*, e un dispositivo senza blocco schermo non è mai
bloccato.

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
