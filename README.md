# MusyBoard

**Fork di [HeliBoard](https://github.com/HeliBorg/HeliBoard)** che cifra il
testo **dentro la tastiera**, prima che entri nell'app di chat.

Il chiaro non arriva mai all'applicazione: quando premi "cifra", nel campo di
testo compare già il messaggio cifrato. L'app di chat, il suo server, i backup
in cloud e qualunque analisi automatica lato piattaforma vedono soltanto quello.

> **La tastiera è di HeliBoard, non mia.** Layout, correzione, digitazione a
> gesti, temi, dizionari, emoji, appunti: tutto ciò che rende utilizzabile una
> tastiera è lavoro loro, e qui è arrivato invariato. MusyBoard aggiunge un
> solo pezzo — la cifratura — sopra il loro.
>
> È un fork indipendente, **non approvato né sostenuto** da chi sviluppa
> HeliBoard. Non segnalare a loro i problemi di questa versione, e non
> aspettarti che ne sappiano qualcosa: i difetti della parte cifrata sono di
> questo fork.
>
> Il README originale è conservato in
> [`README-HeliBoard.md`](README-HeliBoard.md).

> **Scritto con assistenza di un LLM.** Il progetto originale chiede
> esplicitamente di non usare LLM per i contributi *a loro*: per questo qui non
> arriverà mai una pull request, e l'URL di push verso il loro repository è
> disabilitato di proposito.

---

## Stato: sperimentale. Leggi prima di fidartene

Questo software **non è stato controllato da nessuno** tranne chi lo ha
scritto. Non ha ricevuto revisioni indipendenti né un audit di sicurezza.

Cosa è stato verificato, e come:

| | |
|---|---|
| core crittografico | 71 test, analisi statica severa, ~48 milioni di input di fuzzing |
| ciclo completo | osservato su emulatore Android 6, 12 e 14 |
| percorsi d'errore | blob corrotto, troncato, versione futura, testo non cifrato |
| cambio chiave di un contatto | tutti e tre gli esiti |
| backup e ripristino dell'identità | salvataggio, cancellazione dei dati, ripristino, passphrase sbagliata |

Cosa **non** è stato fatto:

- **mai eseguito su un telefono vero**, solo su emulatore;
- **nessun audit indipendente**;
- **il formato dei messaggi non è congelato**: una versione futura potrebbe non
  leggere i messaggi di oggi;
- **il backup dell'identità c'è, ma proteggerlo tocca a te.** Il file è cifrato
  soltanto con la passphrase che scegli: se è debole, lo è il backup; se la
  dimentichi, il file non si apre più e non esiste modo di recuperarla.

Se stai valutando di usarlo in una situazione dove sbagliare ha conseguenze
serie: **non usarlo.** Usa Signal.

---

## Da cosa protegge, e da cosa no

Il progetto è costruito contro un avversario preciso: **l'analisi automatica e
indiscriminata dei contenuti di tutti gli utenti**, con conservazione in blocco
— il modello di cui si discute sotto il nome di *Chat Control*. Non è pensato
contro qualcuno che prende di mira te in particolare, e questa distinzione
decide gran parte del disegno.

**Protegge da:** chi legge il testo dentro la chat — la piattaforma, il suo
server, i backup automatici, la scansione dei contenuti — e dalla manomissione
del messaggio cifrato, che viene rilevata.

**Non protegge da**, per scelta esplicita e non per dimenticanza:

- **un telefono compromesso.** Keylogger, root, cattura schermo, servizi di
  accessibilità: se l'endpoint è compromesso, il testo si legge prima che venga
  cifrato. Nessun disegno può evitarlo;
- **i metadati sociali.** Chi parla con chi resta visibile alla piattaforma, e
  lo resterebbe con qualunque disegno;
- **il fatto stesso che tu stia cifrando.** I messaggi sono riconoscibili;
  l'aspetto da link serve a non insospettire un occhio umano, non un
  classificatore automatico;
- **la ripubblicazione di un vecchio messaggio.** Un messaggio cifrato resta
  valido per sempre e rispedirlo funziona. È mitigato mostrando la data di
  composizione, che sta dentro il cifrato: non lo impedisce, lo rende visibile;
- **la compromissione futura delle chiavi.** Un archivio conservato oggi più le
  chiavi ottenute domani permette di leggere all'indietro. È il rischio che la
  conservazione in blocco fa maturare col tempo, ed è la ragione per cui nel
  formato è previsto un livello con *forward secrecy* — previsto, non ancora
  implementato.

---

## Come funziona, in breve

Ognuno ha **una** identità, valida per tutti i contatti: non esiste una chiave
per interlocutore, il segreto condiviso si calcola.

Il primo contatto si aggancia scambiandosi una **presentazione**, un messaggio
che la tastiera scrive da sola nel campo. Chi la riceve e la decifra memorizza
la chiave dell'altro. Da quel momento decifrare un messaggio stabilisce da solo
il destinatario per quella app, quindi rispondere cifrato non richiede di
scegliere nulla.

La memorizzazione al primo incontro (*trust on first use*) lascia scoperta una
cosa sola: il primissimo scambio. Per chiuderla c'è il **codice QR** da mostrare
di persona — l'unica verifica che un intermediario non può falsificare.

Se un giorno la chiave di un contatto cambia, il sistema **non decide da solo**:
mostra i due codici affiancati, spiega le due letture possibili — ha
reinstallato l'app, oppure qualcuno si sta interponendo — e non modifica niente
finché non sei tu a confermare.

Dettagli tecnici: [`CIPHER.md`](CIPHER.md). Decisioni di progetto e loro
motivazioni: `CLAUDE.md` nel repository del core.

---

## Permessi

**Nessuno in più rispetto a HeliBoard**, che a sua volta non ha accesso a
internet — ed è la sua proprietà principale.

In particolare **niente `CAMERA`**: il codice QR si mostra ma non si scansiona.
Non serve, perché basta che una delle due persone inquadri, con un lettore QR
qualunque, e passi il testo all'app. È una decisione chiusa, non una funzione
mancante.

---

## Installazione

L'APK è nelle [release](../../releases). Convive con HeliBoard: ha un nome di
pacchetto diverso e non lo sostituisce — nell'elenco delle tastiere di sistema
compaiono affiancati, distinti per nome e icona.

1. installa l'APK;
2. Impostazioni → Sistema → Lingue e immissione → Tastiera su schermo → attiva
   questa tastiera, poi selezionala;
I due lucchetti sono già lì, a destra della striscia dei suggerimenti: non
serve configurare niente.

Tutto il resto sta in **impostazioni della tastiera → Cifratura**: interruttore
generale, modalità di scrittura e contatti.

Se hai già usato questa tastiera prima della versione 0.1.2 i lucchetti non
compaiono — le preferenze salvate non vengono sovrascritte, sono tue. Si
attivano da impostazioni della tastiera → Toolbar, oppure cancellando i dati
dell'app (che però **distrugge l'identità**: fai prima un backup).

### Dove si scrive il messaggio

Due modi, si sceglie in Cifratura → *Scrivi dentro la tastiera*.

- **spento** (predefinito): scrivi nel campo dell'app come con qualunque
  tastiera, e il lucchetto sostituisce quel testo con il messaggio cifrato;
- **acceso**: scrivi in una riga della tastiera e l'app riceve **solo** il
  messaggio cifrato, quando premi il lucchetto. Il campo dell'app resta vuoto
  fino a quel momento, quindi non può salvare bozze del chiaro né annunciare
  che stai scrivendo. In questa modalità compare un terzo tasto,
  l'aeroplanino: consegna il testo **in chiaro**, per quando il destinatario
  non ha questa tastiera.

> **Attenzione al prossimo aggiornamento.** Le build da 0.1.0 a 0.1.3 sono
> firmate con una chiave di debug; dalla prossima si passa a una chiave di
> release, e Android non installa un APK con firma diversa sopra uno esistente.
> Servira' disinstallare, e disinstallare **cancella la tua identita'**. Prima
> di farlo: Contatti → salva il backup, e dopo l'installazione ripristinalo.

Serve **Android 6.0** o superiore per la cifratura. Sotto, la tastiera funziona
normalmente e i tasti dicono che la cifratura non è disponibile.

**Tieni installata una seconda tastiera.** Se questa dà problemi, senza
un'alternativa non puoi più scrivere nemmeno per ripararla.

### Uso

| Gesto | Cosa fa |
|---|---|
| pressione **lunga** sul lucchetto chiuso | inserisce la tua presentazione: il primo passo con un contatto nuovo |
| lucchetto chiuso | cifra il campo per il destinatario corrente |
| lucchetto aperto | decifra il campo, o gli appunti se il campo è vuoto |
| pressione **lunga** sul lucchetto aperto | va dritto agli appunti |
| impostazioni tastiera → Contatti | elenco, codici di verifica, nomi, QR |

Il testo decifrato compare in una finestra della tastiera che blocca screenshot
e anteprime di sistema, e **non torna mai nell'app di chat**.

---

## Com'è fatto

| Dove | Cosa |
|---|---|
| questo repository | il lato Android: interfaccia, storage, Keystore |
| [`tastieraNoCC`](https://github.com/franmuzi1/tastieraNoCC) | il core in Rust: crittografia, formato, portachiavi |

Il core è separato perché non sa niente di Android: si compila e si prova in
mezzo secondo su qualunque macchina, ed è la ragione per cui esiste quella
quantità di test e di fuzzing. Tenerlo qui dentro significherebbe farlo passare
attraverso ogni merge dal progetto originale.

Primitive: X25519, XChaCha20-Poly1305, HKDF-SHA256.

---

## Licenza

GPL-3.0, come HeliBoard — da cui questo fork discende e di cui eredita il
copyright su tutto ciò che non è la parte cifrata. Il README originale del
progetto è conservato in [`README-HeliBoard.md`](README-HeliBoard.md).
