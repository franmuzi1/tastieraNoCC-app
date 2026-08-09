# keyboard-cipher — integrazione nel fork

Fork di [HeliBoard](https://github.com/HeliBorg/HeliBoard) che cifra il testo
**dentro la tastiera**, prima che entri nell'app di chat. L'app di chat, il suo
server e qualunque scanning lato piattaforma vedono solo ciphertext.

Il core crypto sta in un repo separato (`keyboard-cipher-core` + il ponte
`keyboard-cipher-jni`) e non è incluso qui: questo repo contiene solo il lato
Android.

## Stato: NON COMPILATO

Niente di quanto segue è mai stato costruito. L'ambiente in cui è stata fatta
l'integrazione non aveva SDK, NDK, Gradle né JDK — solo la JRE. Le parti Rust
sono compilate e testate nel loro repo; **tutto ciò che è Kotlin, Gradle e
manifest qui dentro è da considerarsi non verificato** finché non passa un
`./gradlew assembleDebug`.

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

**1. I tasti in toolbar.** `ToolbarKey` è un enum in
`app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt:121`.
Servono due voci — cifra e decifra — con le rispettive icone e stringhe, e la
gestione nel listener delle azioni. Non le ho aggiunte: senza poter compilare,
una voce di enum priva della mappatura dell'icona rompe il build in un modo che
non avrei potuto verificare, e un TODO preciso vale più di codice
plausibilmente rotto.

**2. Il ciclo di vita.** Nessuno chiama ancora `CipherCore.nativeInit`. Serve:
generare il segreto al primo avvio con `nativeGenerateSecret`, cifrarlo con una
chiave in Android Keystore, persisterlo, e ricaricarlo insieme al keyring
(`nativeExportKeyring` / il blob passato a `nativeInit`).

**3. `onNewIntent` in `DecryptActivity`.** Con `launchMode=singleTask` un
secondo intent verso un'istanza viva non passa da `onCreate`: la seconda
decifratura verrebbe ignorata lasciando a schermo il plaintext precedente.

**4. La cronologia clipboard.** Se si abilita la copia del testo decifrato, quel
contenuto va escluso esplicitamente dalla cronologia clipboard della tastiera —
che è la *stessa app*. Vedi il commento su `copyPlaintext`.

**5. UI di `ContactsActivity`.** Elenco peer, fingerprint, etichette,
schermata di conflitto, QR.

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
