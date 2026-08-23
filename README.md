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

Questo software **non ha ricevuto un audit di sicurezza indipendente**. È stato
passato al setaccio da una revisione interna a più agenti (23 agosto 2026), che
ha trovato e fatto correggere una decina di difetti reali — fra cui chiavi
private che finivano nell'interfaccia e testo in chiaro che entrava nel
dizionario personale. Ma chi ha condotto la revisione lavorava allo stesso
progetto: non sostituisce occhi esterni.

Cosa è stato verificato, e come:

| | |
|---|---|
| core crittografico | 107 test nel core e 11 nel ponte Android, analisi statica severa, ~187 milioni di input di fuzzing |
| ciclo completo | osservato su emulatore Android 6, 12 e 14 |
| percorsi d'errore | blob corrotto, troncato, versione futura, testo non cifrato |
| cambio chiave di un contatto | tutti e tre gli esiti |
| backup e ripristino dell'identità | salvataggio, cancellazione dei dati, ripristino, passphrase sbagliata |
| allegati, giro completo | file cifrato dalla tastiera e riaperto dall'altra parte, e viceversa: contenuto identico byte per byte, nome originale recuperato da dentro il cifrato |

Il giro completo degli allegati è stato fatto una volta sola, e quella volta ha
trovato un difetto: il salvataggio scriveva un file vuoto. È corretto e
riverificato — ma è il promemoria di quanto vale il codice non ancora eseguito.

Cosa **non** è stato fatto:

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
- **la compromissione futura delle chiavi — non più, con la forward secrecy
  accesa.** Un archivio conservato oggi più le chiavi ottenute domani permette
  di leggere all'indietro: è il rischio che la conservazione in blocco fa
  maturare col tempo. Con la catena attiva — è l'impostazione predefinita — le
  chiavi stabili non bastano più ad aprire nulla dal secondo messaggio in poi,
  allegati compresi. Il prezzo, dichiarato nell'interruttore: **un messaggio si
  apre una volta sola**, e non lo riapri nemmeno tu. Resta scoperto ciò che si
  manda con l'interruttore spento.

---

## Come funziona, in breve

Ognuno ha **una** identità, valida per tutti i contatti: non ci si scambia una
chiave diversa per ogni persona, il segreto condiviso si ricalcola ogni volta
dalle due identità. Sopra a questa base vivono le chiavi temporanee della
forward secrecy e — a interruttore spento — una chiave per conversazione che
rende possibile bruciarla.

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

**Forward secrecy**, accesa di default: ogni messaggio porta una chiave nuova, e
leggere una risposta butta le vecchie. Chi domani prendesse il telefono e la
chiave stabile non riaprirebbe quello che hai già mandato. Il prezzo è dichiarato
e non aggirabile: un messaggio si apre **una volta sola**, nemmeno per te, e non
esiste cronologia. Si spegne per riavere i messaggi rileggibili.

**Bruciare una conversazione**, dalla scheda del contatto. Ha senso soprattutto
a forward secrecy spenta — accesa, i messaggi si aprono una volta sola e non
resta quasi niente da bruciare, e l'app te lo dice prima di procedere. Da questo
telefono le chiavi spariscono ed è definitivo,
all'altra persona viene copiata negli appunti una richiesta da incollarle in
chat. Se la sua app la onora, cancella anche lei — ma **non è imponibile**, e la
piattaforma ha comunque il proprio cifrato.

**Allegati**: si scelgono dalla tastiera con la graffetta, oppure dalla scheda
del contatto. Il file esce cifrato come documento — i
documenti le chat non li ricomprimono — e il nome originale viaggia dentro il
cifrato, non fuori. Chi riceve lo passa all'app e lo vede in una finestra che
blocca gli screenshot; sul telefono finisce solo se lo salva apposta.

Dettagli tecnici: [`CIPHER.md`](CIPHER.md). Decisioni di progetto e loro
motivazioni: `CLAUDE.md` nel repository del core.

---

## Permessi

**Niente accesso a internet**, che è la proprietà principale di HeliBoard e qui
resta intatta: la tastiera non può mandare da nessuna parte quello che scrivi.

Tre permessi in più rispetto a HeliBoard, tutti per il servizio che impedisce al
telefono di chiudere la tastiera mentre la usi: `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE` e le notifiche. Nessuno dei tre permette di far
uscire dati dal telefono, e vanno via insieme a quel servizio se un giorno lo si
toglie.

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
   questa tastiera, poi selezionala.

Accanto ai suggerimenti trovi l'interruttore della **modalità cifrata** e la
lista degli appunti. Accendendo la modalità compaiono gli altri tasti, letti da
destra: consegna in chiaro, cifra, decifra, contatti, immagine, allega.

I tasti della cifratura esistono **solo con la modalità accesa**, ed è
deliberato: senza, "cifra" prenderebbe il testo dal campo dell'app e lo
manderebbe al destinatario ricordato per quell'app, scelto da solo. Con la
modalità accesa il destinatario è scritto accanto a ciò che stai scrivendo.

Tutto il resto sta in **impostazioni della tastiera → Cifratura**: interruttore
generale, modalità di scrittura, blocco della copia del chiaro, apertura
automatica, forward secrecy e contatti.

Se hai già usato questa tastiera prima, i tasti nuovi non compaiono da soli —
le preferenze salvate non vengono sovrascritte, sono tue. Si attivano da
impostazioni della tastiera → Toolbar, oppure reinstallando da zero (che però
**distrugge l'identità**: fai prima un backup).

### Dove si scrive il messaggio

In una **riga della tastiera**, non nel campo dell'app. L'app riceve solo il
messaggio cifrato, quando premi il lucchetto: fino a quel momento il suo campo
resta vuoto, quindi non può salvare bozze del chiaro né annunciare che stai
scrivendo.

Lì vivono i tasti della cifratura, compreso l'aeroplanino che consegna il testo
**in chiaro**, per quando il destinatario non ha questa tastiera.

**La riga compare dove si compongono messaggi.** Su una barra di ricerca, un
campo numerico o un indirizzo non compare da sola: lì il testo appartiene
all'app e cifrarlo non ha senso. Se in un campo del genere la vuoi lo stesso,
premi il tasto della cifratura e compare — e torna automatica appena cambi
campo. L'unica eccezione che non si scavalca sono i **campi password**: la riga
mostrerebbe a schermo quello che il campo nasconde con i pallini.

Nella riga si seleziona come in qualunque campo: tocco per il cursore, pressione
lunga per la parola, trascinamento per un pezzo. **Copia e taglia invece non
funzionano** finché c'è del chiaro: dagli appunti lo leggerebbe l'app di chat che
ha il fuoco, e resterebbe nella cronologia appunti sul telefono. Si può riaprire
da Cifratura, sapendo cosa si apre.

**Quello che scrivi lì non viene imparato.** Il dizionario personale della
tastiera finisce nel backup di Android e riemerge come suggerimento nelle altre
app: le parole della riga cifrata ne restano fuori. Si può riaccendere
l'apprendimento da Cifratura, sapendo il prezzo.

> **Attenzione al passaggio a una build firmata.** Gli APK pubblicati finora
> sono build di **debug**: firmate con la chiave di debug di Android e con il
> flag `debuggable`, quindi chi ha in mano il telefono sbloccato con il debug
> USB attivo può leggerne i dati. Non cambia cosa vede la piattaforma di chat,
> che continua a vedere solo cifrato.
>
> La build di release ha un nome di pacchetto diverso: si installerà **accanto**
> a questa, non al suo posto, e l'identità non si porta dietro da sola. Prima
> di passare: Contatti → salva il backup, e ripristinalo nella nuova.

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
| rubrica | apre i contatti senza passare dalle impostazioni |
| immagine / graffetta | manda un file cifrato: prima scegli la persona, poi il file |
| impostazioni tastiera → Contatti | elenco, codici di verifica, nomi, QR, backup, rogo |

Quando incolli la presentazione di qualcuno mai visto, i contatti si aprono da
soli sul dialogo del nome: una chiave senza nome è un contatto che non si
riconosce.

### Il modo più affidabile di aprire un messaggio ricevuto

**Tieni premuto sul messaggio nella chat e scegli «Decifra».** È un passaggio in
meno della copia, il testo non passa mai dagli appunti, e soprattutto funziona
**anche a tastiera ferma**: quella voce la offre Android, che avvia la schermata
da zero anche se l'app non è in esecuzione. Compare nelle app che usano la barra
di selezione standard; quelle con un menu proprio, come WhatsApp, non la
mostrano.

Copiare funziona ed è comodo — la schermata si apre da sola — ma dipende dal
fatto che la tastiera sia viva, perché è lei a guardare gli appunti. Se il
gestore batteria del telefono la ferma, copiare non produce niente e non c'è modo
per l'app di accorgersene: Android non ha nessun evento di sistema per gli
appunti, quindi non esiste niente che possa risvegliarla. Se ti capita spesso,
metti l'app su «Senza restrizioni» nelle impostazioni della batteria e usa la
selezione per i messaggi che contano.

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
