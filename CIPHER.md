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

**4. Clipboard (via 1).** Polling passivo con `getPrimaryClipDescription()` —
che non fa comparire il toast di Android 12 — e lettura del contenuto solo su
gesto esplicito dell'utente. È la via che funziona ovunque; oggi non c'è niente.

**5. Identity card.** `nativeIdentityCard` + `commitText`. Senza, il primo
contatto in una direzione non si chiude. Serve un punto d'ingresso nella UI:
una voce nelle impostazioni, o un long-press sul tasto cifra.

**6. UI di `ContactsActivity`.** Elenco peer, fingerprint, etichette
(`nativeAssignLabel`), verifica fuori banda (`nativeMarkVerified`), QR.
Due schermate non sono cosmetiche:
- **conflitto di etichetta** — è il "safety number changed" di Signal, e senza
  di essa `nativeConfirmKeyChange` è irraggiungibile;
- **`CipherState.Unreadable` → `resetIdentity`** — l'unica uscita da
  un'identità non decifrabile, e va dietro una schermata che dica che si sta
  distruggendo l'identità.

**7. Cronologia clipboard.** Se si abilita la copia del testo decifrato, quel
contenuto va escluso esplicitamente dalla cronologia clipboard della tastiera —
che è la *stessa app*. Vedi il commento su `copyPlaintext` in `DecryptActivity`,
che è dead code apposta finché questo non è risolto.

**8. Prova su dispositivo.** Niente è mai stato eseguito: il `.so` non è mai
stato caricato. Tutto il confine JNI e tutto Keystore sono verificati solo
staticamente.

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
