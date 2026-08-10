# Protocollo di prova — PC ↔ telefono via messaggi salvati di Telegram

Prova manuale del giro completo fra le due parti del sistema:

- **PC**: il binario `kc` (`~/.local/bin/kc`), identità già creata, fingerprint
  `ez9p hsgt j1oh xzfy biib he4o`, stato in
  `~/.local/share/keyboard-cipher/state`. È l'"altra parte": una seconda
  identità, indipendente da quella del telefono.
- **Telefono**: la tastiera che cifra, `tastiera-cifrata-0.1.8-dev1`.

L'unico canale fra i due lati sono i **messaggi salvati** di Telegram, sullo
stesso account: si copia un blob da una parte e lo si incolla nell'altra.
Nient'altro li collega — `kc` non sa che il telefono esista, e viceversa.

> **Cosa NON fa parte di questo protocollo.** Telegram Desktop è dell'utente:
> lo apre e lo pilota lui. Qui si dice cosa incollare e cosa deve comparire,
> non si automatizza niente su quella GUI. Nessun passo tocca l'emulatore
> Android.

---

## 0. Convenzioni e preparazione

### 0.1 Stato di partenza atteso

```
$ kc id
fingerprint: ez9p hsgt j1oh xzfy biib he4o
stato:       /home/user/.local/share/keyboard-cipher/state
contatti:    0
```

**Verificato**: questo è esattamente l'output ottenuto. Zero contatti, quindi
si parte davvero da pulito. Se `contatti` è diverso da 0, qualcuno ha già fatto
un giro: il protocollo funziona lo stesso, ma i numeri d'indice di
`kc contacts` non saranno quelli scritti qui.

### 0.2 Come si incolla un blob nel terminale

Il blob è `kc/` seguito da soli caratteri z-base-32 (minuscole e cifre): non
contiene metacaratteri di shell. Le tre forme funzionano tutte
(**verificate**):

```
kc decrypt 'kc/…'          # apici singoli: la forma consigliata
kc decrypt kc/…            # nudo: funziona, gli argomenti vengono uniti con spazi
echo 'kc/…' | kc decrypt   # da stdin, se il blob è molto lungo
```

Due trappole **verificate sul PC**, entrambe con l'errore che producono:

| Errore da evitare | Cosa si legge |
|---|---|
| blob spezzato da un a capo a metà (copia che va a capo) | `kc: formato non valido: body troncato` |
| due blob nello stesso testo | decifra **il primo** e ignora il secondo, senza avvisare |

Un blob con testo intorno e un `\n` in coda invece va bene: **verificato**,
`kc decrypt "guarda: kc/…\n"` decifra normalmente. È voluto — il
riconoscimento tollera il contesto.

### 0.3 Dove stanno le cose sul telefono

- **Toolbar della tastiera**, quattro tasti (nomi interni `ENCRYPT`, `DECRYPT`,
  `SEND_PLAIN`, `COMPOSE`; i primi due fissati di default):
  - **lucchetto chiuso** = *Cifra*;
  - **lucchetto aperto** = *Decifra*;
  - **aeroplanino** = *Consegna in chiaro* — compare **solo** a riga di
    composizione accesa;
  - **quarto tasto** = *Scrivi dentro la tastiera* — interruttore della riga di
    composizione, acceso quando la modalità è attiva.
- **Impostazioni → Cifratura**: *Cifratura attiva*, *Scrivi dentro la
  tastiera*, *Invia subito*, *Contatti*.
- **Gesti lunghi**: pressione lunga su *Cifra* → inserisce la propria
  presentazione nel campo; pressione lunga su *Decifra* → va dritta agli
  appunti saltando il campo.

### 0.4 Fingerprint da tenere davanti

| Parte | Dove si legge | Valore |
|---|---|---|
| PC | `kc id` | `ez9p hsgt j1oh xzfy biib he4o` (**verificato**) |
| Telefono | Impostazioni → Cifratura → Contatti → *La tua identità* | da annotare al primo giro |

Ogni prova che nomina un fingerprint fallisce se il valore mostrato non è
**identico carattere per carattere**: 24 caratteri in 6 gruppi da 4. Se i
gruppi sono un numero diverso, il formato non è quello congelato e c'è un
problema più grave della prova in corso.

---

## 1. Primo contatto: telefono → PC

Si parte da questa direzione perché è quella che non richiede niente di già
fissato da nessuna delle due parti.

**Gesto sul telefono**

1. Aprire Telegram → *Messaggi salvati*, mettere il cursore nel campo di
   scrittura.
2. **Tenere premuto** il lucchetto chiuso.
3. Premere invio per spedire.

**Atteso sul telefono**

- Nel campo compare `kc/` seguito da una lunga sequenza di lettere e cifre
  minuscole.
- Toast: *«La tua presentazione è nel campo: invia per farti riconoscere»*.

**Gesto sul PC**

4. In Telegram Desktop, copiare il messaggio appena arrivato nei messaggi
   salvati.
5. Nel terminale:

```
kc decrypt '<blob incollato>'
```

**Atteso sul PC**

```
nuovo contatto, chiave fissata.
<fingerprint del telefono>

Confronta il codice di persona, poi dagli un nome con `kc name`.
```

6. Confrontare quel fingerprint con quello che il telefono mostra in
   Impostazioni → Cifratura → Contatti → *La tua identità*. **Devono
   coincidere.**
7. Dare un nome al contatto:

```
kc name 0 telefono
kc contacts
```

Atteso: `0. telefono` con sotto il fingerprint.

**Come si riconosce il fallimento**

| Sintomo | Significato |
|---|---|
| `kc: sentinel non riconosciuto: il testo non e' un blob di questo sistema` | non è stato copiato il blob, o la copia ha preso solo testo intorno |
| `kc: formato non valido: checksum della identity card non torna` | la card è arrivata **corrotta**: un carattere è cambiato per strada. È il caso per cui il checksum esiste |
| `kc: decodifica z-base-32 fallita` | copia parziale (la selezione ha tagliato il blob) |
| `kc: formato non valido: body troncato` | il blob si è spezzato su due righe |
| il fingerprint stampato ≠ quello mostrato dal telefono | **fermarsi**: o si è copiato il blob sbagliato, o qualcosa altera il testo in transito |
| il telefono non mette niente nel campo alla pressione lunga | vedere il toast: *«Cifratura non disponibile»* o *«Sblocca il dispositivo per cifrare»* sono stati diversi da un guasto |

Tutti gli errori della tabella sono stati **riprodotti realmente sul PC** (vedi
appendice A).

---

## 2. Primo contatto: PC → telefono

**Gesto sul PC**

```
kc card
```

Produce un blob `kc/…`. Copiarlo per intero e incollarlo in Telegram Desktop
nei messaggi salvati, poi inviare.

> **Nota verificata:** `kc card` è di sola lettura — l'hash del file di stato è
> identico prima e dopo (appendice A). Si può rieseguire quante volte si vuole.
>
> **Ogni card è diversa in lunghezza**: tre esecuzioni consecutive hanno dato
> 207, 151 e 373 caratteri. È il riempimento casuale, ed è voluto: senza,
> tutte le presentazioni avrebbero la stessa lunghezza e una sola regex le
> isolerebbe. Non è un difetto e non va "stabilizzato".

**Gesto sul telefono**

1. Telegram → *Messaggi salvati*: tenere premuto il messaggio appena arrivato →
   **Copia**.
2. Sulla tastiera, **tenere premuto** il lucchetto aperto (*Decifra*): la
   pressione lunga va dritta agli appunti e salta il campo.

   *Variante da provare almeno una volta:* incollare il blob nel campo di
   scrittura di Telegram e premere il lucchetto aperto con una pressione
   **breve**. Il tasto guarda prima il campo e solo dopo gli appunti: deve
   funzionare uguale.

**Atteso sul telefono**

- Si apre una schermata con titolo ***Nuovo contatto***.
- Sotto: *«Chiave memorizzata. Confronta questo codice di persona per essere
  sicuro che sia davvero lui.»*
- Il fingerprint mostrato è `ez9p hsgt j1oh xzfy biib he4o`, cioè quello di
  `kc id`. **Questo è il controllo che conta.**
- C'è un pulsante *Scrivi a questo contatto*. **Non premerlo ora**: serve alla
  prova 3.

**Come si riconosce il fallimento**

| Cosa appare | Significato |
|---|---|
| *«Questo testo non è cifrato»* | non è stato copiato il blob (o gli appunti contengono altro) |
| *«Impossibile decifrare questo messaggio»* | il blob è arrivato alterato o troncato. Un solo messaggio per tutte le cause: è la regola di opacità, non una diagnostica scarsa |
| *«Messaggio creato con una versione più recente. Aggiorna l'app.»* | il PC ha un formato più nuovo del telefono: le due parti non sono allineate |
| fingerprint ≠ `ez9p hsgt …` | fermarsi, come sopra |
| il tasto *Decifra* non fa niente | la schermata è `FLAG_SECURE`: non è invisibile, è che non è partita. Vedere se compare un toast |
| titolo *«Contatto già noto»* invece di *«Nuovo contatto»* | la card era già stata fissata in un giro precedente. Non è un errore |

**Nota sull'indizio del tasto *Decifra***: si accende quando negli appunti c'è
qualcosa che ha la forma di un nostro blob, ma **solo se la cronologia degli
appunti di HeliBoard è attiva** — il riconoscimento si aggancia alla lettura
che quella fa già. Con la cronologia spenta l'indizio resta spento e non è un
guasto. Non usare l'accensione dell'icona come criterio di successo di questa
prova.

---

## 3. Selettore del destinatario

**Va eseguita adesso**, prima di qualunque cifratura dal telefono: è l'unico
momento in cui il telefono ha una chiave fissata ma **nessun destinatario
impostato per Telegram**. Decifrare un *messaggio* imposta il destinatario da
solo, e brucerebbe la prova; decifrare una *card* invece no, per costruzione —
una presentazione non è autenticata e non può decidere per chi si cifra.

**Gesto sul telefono**

1. In Telegram → *Messaggi salvati*, scrivere una parola qualunque nel campo.
2. Premere il lucchetto chiuso (pressione **breve**).

**Atteso**

- Si apre una schermata con titolo **«A chi stai scrivendo?»**.
- Elenca i contatti: un pulsante per ognuno, con **nome sopra e fingerprint
  sotto**. Qui deve esserci `ez9p hsgt j1oh xzfy biib he4o`, con etichetta
  *Senza nome* se non gli è ancora stato dato un nome sul telefono.
- C'è un pulsante *Annulla*.
- **Il testo scritto al punto 1 non è stato toccato**: il controllo del
  destinatario avviene prima di leggere il campo.

3. Toccare il contatto del PC.

**Atteso**

- Toast: *«Ora in questa app cifri per questo contatto»*.
- Si torna a Telegram, con il testo del punto 1 ancora lì.

**Come si riconosce il fallimento**

| Sintomo | Significato |
|---|---|
| toast *«Scegli prima un destinatario per questa app»* e nessuna schermata | il selettore non si è aperto: è il vicolo cieco che la schermata esiste per chiudere. **Guasto** |
| non succede niente e il testo resta com'è | il tasto non ha reagito: **guasto** |
| il testo del campo sparisce o viene sostituito | **guasto**: il destinatario si chiede *prima* di toccare il campo |
| compare *«Nessun contatto ancora. Fatti mandare la presentazione di qualcuno…»* | il keyring è vuoto: la prova 2 non ha fissato niente. Rifare la 2 |
| la schermata si apre ma manca il fingerprint sotto il nome | **guasto**: due contatti senza nome sarebbero indistinguibili |

**Sotto-prova: senza destinatario e senza contatti.** Se si vuole vedere il
ramo "elenco vuoto", va provato su un'app dove non si è mai cifrato **e** con
keyring vuoto — cioè prima della prova 2. Se si è già passati dalla 2 questo
ramo non è più raggiungibile senza cancellare i dati dell'app, e cancellarli
**distrugge l'identità del telefono**: non farlo per una prova cosmetica.

---

## 4. Messaggio dal telefono al PC

**Gesto sul telefono**

1. In Telegram → *Messaggi salvati*, campo di scrittura: scrivere
   `messaggio dal telefono`.
2. Premere il lucchetto chiuso (breve).
3. Inviare.

**Atteso sul telefono**

- Il testo in chiaro **sparisce dal campo**, sostituito da `kc/…`.
- In *Messaggi salvati* compare il blob, non la frase.

**Gesto sul PC**

4. Copiare il blob da Telegram Desktop, poi:

```
kc decrypt '<blob>'
```

**Atteso sul PC** — quattro righe, in quest'ordine:

```
da:       telefono
chiave:   <fingerprint del telefono>
scritto:  <data e ora> UTC (secondo il mittente)

messaggio dal telefono
```

Tre controlli, tutti obbligatori:

- **`chiave:`** deve essere il fingerprint annotato alla prova 1. È la verifica
  del mittente: non è una decorazione, è l'unica cosa che dice chi ha scritto.
- **`da:`** deve essere l'etichetta locale data con `kc name`. Se dice
  `mittente mai visto, ora fissato`, la prova 1 non ha lasciato traccia.
- **`scritto:`** è l'orologio **del telefono**, ed è dichiarato tale
  (*«secondo il mittente»*). Se il telefono ha l'ora sbagliata, qui si legge
  l'ora sbagliata: **non è un guasto e non deve far rifiutare niente**.

**Come si riconosce il fallimento**

| Sintomo | Significato |
|---|---|
| il chiaro resta nel campo accanto al blob | toast *«Questa app non ha lasciato sostituire il testo…»*: **da segnalare**, si rischia di inviare il chiaro |
| toast *«Niente da cifrare»* | il campo era vuoto |
| si apre *«A chi stai scrivendo?»* | il destinatario è andato perso fra una prova e l'altra. Se succede **dopo** la prova 3, è il guasto del destinatario che non sopravvive al riavvio del servizio: annotare quanto tempo è passato e se il telefono si è riavviato |
| `kc: decifratura/autenticazione fallita` | tre cause possibili e indistinguibili per costruzione: blob alterato in transito, blob troncato, oppure **messaggio cifrato per qualcun altro** — cioè destinatario sbagliato sul telefono. Controllare la prova 3 |

---

## 5. Messaggio dal PC al telefono

**Gesto sul PC**

```
kc encrypt --to telefono 'messaggio dal PC'
```

`--to` accetta il nome, l'indice di `kc contacts`, o l'inizio del fingerprint —
**tutte e tre verificate**, anche col fingerprint spaziato fra virgolette
(`--to 'ez9p hsgt'`).

Copiare il blob, incollarlo in Telegram Desktop nei messaggi salvati, inviare.

**Gesto sul telefono**

1. Tenere premuto il messaggio in *Messaggi salvati* → **Copia**.
2. Tenere premuto il lucchetto aperto.

**Atteso sul telefono**

- Fingerprint del mittente: **`ez9p hsgt j1oh xzfy biib he4o`**.
- *«Scritto il … (secondo il mittente)»* con l'ora del PC.
- Il testo: `messaggio dal PC`.
- Se il contatto è stato marcato come confrontato di persona, accanto al nome
  compare `✓`. **Se non è mai stato marcato, il `✓` non deve esserci**: quel
  segno significa "confrontato fuori banda" ed è l'unico segnale anti-MITM del
  sistema.
- C'è un pulsante *Copia*, che avvisa che il testo resta negli appunti di
  sistema.

**Effetto collaterale voluto, da verificare subito dopo:** decifrare un
*messaggio* imposta il destinatario per quell'app. Chiudendo la schermata e
premendo il lucchetto chiuso su del testo nuovo, la cifratura deve partire
**senza** chiedere il destinatario. È "la leva che rende automatico il caso
dominante": se qui si riapre *«A chi stai scrivendo?»*, quella regola non
scatta ed è un guasto.

**Come si riconosce il fallimento**

| Cosa appare | Significato |
|---|---|
| *«Impossibile decifrare questo messaggio»* | blob alterato/troncato, oppure `kc` ha cifrato per la chiave sbagliata (`--to` puntava a un altro contatto) |
| *«Questo testo non è cifrato»* | non è stato copiato il blob |
| *«Mittente sconosciuto»* | il telefono non ha la chiave del PC: la prova 2 non ha fissato niente |
| il `✓` c'è senza averlo mai messo | **guasto grave**: il segno di verifica non deve mai comparire da solo |

---

## 6. Riga di composizione — spenta (comportamento di base)

Stato: Impostazioni → Cifratura → *Scrivi dentro la tastiera* **spento** (è il
default).

**Gesto sul telefono**

1. Telegram → *Messaggi salvati*, scrivere `prova a riga spenta` nel campo.
2. Guardare la tastiera.
3. Premere il lucchetto chiuso.

**Atteso**

- **Nessuna riga in più** sopra i tasti. L'aeroplanino **non c'è** (senza la
  riga non avrebbe niente da consegnare); il quarto tasto (interruttore) **c'è
  lo stesso**, spento — è il modo per riaccendere la modalità.
- Il testo compare nel campo di Telegram mentre si scrive, come con una
  tastiera qualunque.
- Premendo il lucchetto, il chiaro nel campo viene **sostituito** dal blob.

**Osservazione sul PC — è questa che dimostra il punto.** Con la riga spenta il
chiaro è nel campo di Telegram, quindi diventa una **bozza**, e le bozze
Telegram si sincronizzano fra i dispositivi dello stesso account: aprendo
*Messaggi salvati* in Telegram Desktop **prima** di premere il lucchetto, la
frase in chiaro dovrebbe essere lì, non ancora inviata. È esattamente la
finestra che la riga di composizione esiste per chiudere.

> **Non verificato da me** — richiede Telegram Desktop, che è dell'utente. Se
> la bozza non compare, può dipendere dai tempi di sincronizzazione o dalle
> impostazioni dell'account, non necessariamente dalla tastiera. Vale come
> dimostrazione se compare; se non compare, non dimostra il contrario.

**Fallimento**: se a riga spenta compare comunque una riga sopra i tasti, o se
l'altezza della tastiera cambia rispetto a com'era, la modalità non è davvero
spenta.

---

## 7. Riga di composizione — accesa

Accenderla dal **quarto tasto** in toolbar (non dalle impostazioni: è la via
che si userà davvero).

**Gesto sul telefono**

1. Premere il quarto tasto.

**Atteso**: toast *«Ora scrivi dentro la tastiera: l'app vedrà solo il
messaggio cifrato»*, il tasto passa allo stato acceso, compare una riga sopra i
tasti con il suggerimento *«Scrivi qui: l'app non lo vede»*, e **compare
l'aeroplanino**.

2. Scrivere `prova a riga accesa`.

**Atteso**: il testo compare **nella riga della tastiera**; il campo di
Telegram **resta vuoto**. Nella riga c'è un cursore lampeggiante (è disegnato a
mano: la finestra dell'IME non prende il fuoco, quindi non è il caret di
sistema).

3. Premere backspace.

**Atteso**: cancella nella riga.

4. Premere invio.

**Atteso**: **va a capo nella riga, non spedisce niente**, e il campo di
Telegram resta vuoto. È il punto più importante di tutta la modalità:
inoltrare l'invio consegnerebbe all'app il comando di spedire mentre il testo
non è ancora cifrato.

5. Verificare che la riga **non copra** la casella di scrittura di Telegram né
   i pulsanti allegati/microfono. L'altezza della tastiera deve restare la
   stessa a riga vuota, con testo, e dopo tre a capo.

6. Premere il lucchetto chiuso.

**Atteso**: il blob compare nel campo di Telegram, **la riga si svuota**.

7. Inviare, copiare il blob e decifrarlo sul PC con `kc decrypt`: deve tornare
   `prova a riga accesa` integro, a capo compresi.

**Prove aggiuntive della riga, tutte con un fallimento riconoscibile**

| Gesto | Atteso | Fallimento |
|---|---|---|
| scrivere nella riga, andare a decifrare qualcosa e tornare indietro | il testo in composizione **è ancora lì** | se sparisce: il buffer viene azzerato dalle nostre stesse schermate — è il caso più comune (leggere e rispondere) |
| scrivere nella riga in Telegram, poi passare a un'**altra** app | il buffer si azzera | se il testo sopravvive al cambio app, si rischia di cifrarlo per il destinatario della conversazione sbagliata |
| aprire una chat dove Telegram ripristina una bozza, con la riga accesa e vuota | il testo della bozza **si sposta nella riga** e il campo si svuota | se resta in tutti e due i posti: al momento di cifrare finirebbe nel blob **e** in chiaro accanto |
| mettere il fuoco su un campo **password** (una qualunque app) | la riga si sospende | se la riga mostra la password a schermo: **guasto grave** |

---

## 8. Tasto aeroplanino — consegna in chiaro

Richiede la riga di composizione **accesa**.

**Gesto sul telefono**

1. Nella riga scrivere `questo va in chiaro`.
2. Premere l'aeroplanino.

**Atteso**

- Il testo compare **in chiaro** nel campo di Telegram, la riga si svuota.
- Toast *«Messo nel campo in chiaro, non cifrato»* — **ma solo se l'invio
  automatico non è scattato**: se il messaggio è partito da solo, quel toast
  non compare (vedi prova 9).
- Il messaggio **non è cifrato**: in *Messaggi salvati* si legge la frase, non
  un blob.

**Attenzione — comportamento che va verificato e che la documentazione più
vecchia descrive diversamente.** `CIPHER.md` dice «Non spedisce: mette il testo
nel campo». Nel codice attuale l'aeroplanino chiama **la stessa consegna del
lucchetto**, quindi con *Invia subito* acceso **e** un campo che dichiara
un'azione di invio, **spedisce**. Va provato in entrambe le configurazioni
della prova 9 e va annotato quale delle due descrizioni è quella vera sul
telefono.

**Fallimenti**

| Sintomo | Significato |
|---|---|
| l'aeroplanino non c'è | la riga di composizione è spenta: il tasto esiste solo lì |
| toast *«Niente da inviare»* | la riga era vuota |
| il testo compare **cifrato** | **guasto**: questo tasto esiste apposta per consegnare il chiaro |
| il testo resta anche nella riga | doppio posto: da segnalare |

**Prova di ritorno** (regola "il testo sta in un posto solo"): dopo aver
consegnato in chiaro **senza** che parta l'invio, accorgersi di un refuso e
premere un tasto qualunque. Il testo deve **rientrare nella riga**, così si può
correggere. Se resta a schermo e nessun tasto lo tocca, è il guasto che quella
regola chiude.

---

## 9. Invio automatico — i due casi

*Invia subito* (Impostazioni → Cifratura) è acceso di default, ma vale **solo**
a riga di composizione accesa. La leva tecnica è `performEditorAction`, cioè
l'azione che il campo **dichiara di avere**: una tastiera non può premere il
pulsante "invia" dell'app.

### 9.a — Telegram con «Invia con Invio» SPENTO

Telegram → Impostazioni → Chat → *Invia con Invio*: **spento**.

**Gesto**: riga accesa, scrivere `senza invia con invio`, premere il lucchetto
chiuso.

**Atteso**

- Il blob compare nel campo di Telegram.
- **Non parte niente.**
- Toast: *«Messaggio pronto nel campo: questa app non lascia spedire dalla
  tastiera, premi tu invio»*.
- L'utente preme invio (o il pulsante di Telegram) e il messaggio parte.

**Fallimento**: se il messaggio parte da solo, l'app dichiarava un'azione di
invio che non ci si aspettava — non è un danno, ma la descrizione è sbagliata e
va corretta. Se **non** parte e **non** compare nessun toast, il caso "non si
può spedire" resta muto: è proprio ciò che il toast esiste per evitare.

### 9.b — Telegram con «Invia con Invio» ACCESO

Stessa voce, **accesa**.

**Gesto**: riga accesa, scrivere `con invia con invio`, premere il lucchetto
chiuso.

**Atteso**

- Il blob compare nel campo **e il messaggio parte da solo**: un tocco invece
  di due.
- **Nessun** toast *«…premi tu invio»*.
- In *Messaggi salvati* compare il blob, già inviato.

**Fallimento**

| Sintomo | Significato |
|---|---|
| il messaggio non parte e compare il toast *«…premi tu invio»* | l'opzione non ha effetto su `imeOptions` per questo campo: annotare la versione di Telegram |
| nel messaggio inviato c'è un **a capo dentro il blob** | si è ricaduti sul tasto invio simulato: **guasto grave**, il blob è rotto e in silenzio |
| il messaggio parte ma il blob è troncato | consegna e invio in ordine sbagliato |

**Controllo incrociato obbligatorio in entrambi i casi**: copiare il blob
arrivato e decifrarlo con `kc decrypt`. Se dà `formato non valido: body
troncato` o `decifratura/autenticazione fallita`, l'invio automatico ha
danneggiato il messaggio — ed è l'unico modo di accorgersene, perché a schermo
un blob rotto e uno buono sono uguali.

### 9.c — Invio automatico a riga SPENTA

**Gesto**: spegnere la riga di composizione, lasciare *Invia subito* acceso,
scrivere nel campo di Telegram, premere il lucchetto chiuso.

**Atteso**: il blob sostituisce il testo nel campo e **non parte niente**,
qualunque sia l'impostazione di *Invia con Invio*. Senza la riga, il lucchetto
sostituisce ciò che si stava scrivendo, e spedirlo da solo sarebbe
irreversibile e non richiesto.

**Fallimento**: se parte da solo, l'invio automatico non è ristretto alla
modalità composizione. **Da segnalare**: è la differenza fra "un tocco invece
di due" e "un messaggio partito senza che nessuno lo abbia chiesto".

---

## 10. Percorsi negativi (opzionali ma consigliati)

Tutti **verificati sul PC** con `kc`; qui si controlla che il telefono dia
l'esito corrispondente.

| Cosa si incolla nel telefono | Atteso sul telefono | Verificato su `kc` |
|---|---|---|
| una frase qualunque | *«Questo testo non è cifrato»* | `kc: sentinel non riconosciuto…` |
| un blob a cui si è cambiato un carattere in fondo | *«Impossibile decifrare questo messaggio»* | `kc: decifratura/autenticazione fallita` |
| un blob accorciato di una ventina di caratteri | **lo stesso identico messaggio** | `kc: decifratura/autenticazione fallita` |
| il blob che il telefono stesso ha appena prodotto | *«Impossibile decifrare questo messaggio»* | idem: è cifrato per il PC, non per il telefono |

La riga che conta è la terza: corrotto e troncato **devono** dare lo stesso
messaggio. Se il telefono li distinguesse, avrebbe reintrodotto la diagnostica
che il core rifiuta di dare — un canale che aiuta chi attacca.

---

## 11. Conflitto di etichetta (fattibile da soli, con una seconda identità `kc`)

Serve una **seconda** identità sul PC. Non tocca quella vera: si sposta lo
stato con `KC_HOME`.

```
export KC_HOME=/tmp/kc-secondo
mkdir -p $KC_HOME
kc init            # nuova identità, fingerprint diverso
kc card            # la sua presentazione
unset KC_HOME      # tornare all'identità vera
```

**Gesto**

1. Sul telefono, dare il nome `Marco` al contatto del PC (Contatti → il peer →
   *Dai un nome*).
2. Mandare la card della **seconda** identità nei messaggi salvati, decifrarla
   sul telefono: compare *«Nuovo contatto»* con un fingerprint **diverso**.
3. Dare anche a questa il nome `Marco`.

**Atteso**

- Schermata *«Questo nome è già di un'altra chiave»*, con **entrambi** i
  fingerprint affiancati e le due letture possibili (ha cambiato telefono /
  qualcuno si sta interponendo), senza che il sistema ne scelga una.
- Due pulsanti: *Non cambiare nulla* e *È la sua chiave nuova*.
- Scegliendo *Non cambiare nulla*: **niente cambia**, `Marco` resta sulla prima
  chiave.
- Riprovando e scegliendo *È la sua chiave nuova*: il nome si sposta e **il
  segno di verifica `✓` sparisce**.

**Fallimenti**

| Sintomo | Significato |
|---|---|
| il nome viene assegnato senza chiedere niente | **guasto grave**: il conflitto è l'unico momento in cui il sistema può dire "la chiave è cambiata" |
| l'azione distruttiva sta sul pulsante positivo | è messa sul negativo apposta: il posto dove cade il pollice dev'essere quello che non cambia niente |
| dopo *È la sua chiave nuova* il `✓` resta | **guasto**: una chiave nuova non è stata confrontata di persona, per definizione |

**Verificato sul PC** che l'esito e la non-modifica sono corretti lato `kc`
(appendice A). Lato `kc` **manca** la conferma del cambio chiave: il CLI non ha
un comando equivalente a *È la sua chiave nuova* — sul PC il conflitto si può
solo osservare, non risolvere.

---

## 12. Cosa NON si può provare così, e perché

### Limiti del canale "messaggi salvati"

- **Il MITM al primo contatto.** I messaggi salvati sono un canale con sé
  stessi: il fingerprint del PC lo si "verifica" leggendolo da `kc id` sulla
  stessa macchina. Non c'è nessun canale fuori banda indipendente, quindi la
  proprietà che il confronto di persona garantisce **non viene messa alla
  prova**. Serve un'altra persona, in presenza, con il QR o la lettura a voce.
  Il pulsante *Ho confrontato di persona* qui si può premere, ma premerlo non
  dimostra niente.
- **Il cambio chiave reale.** "Marco ha cambiato telefono" si simula con una
  seconda identità `kc` (prova 11), ma il caso vero — la stessa persona con una
  chiave nuova — non è distinguibile da qui, ed è proprio il punto: il sistema
  non può distinguerli, lo può solo l'utente.
- **La correlazione dei metadati.** Che Telegram veda "chi parla con chi" non è
  osservabile dall'interno; con i messaggi salvati non c'è nemmeno un "chi".
- **Che il server veda solo ciphertext.** Si vede il blob in Telegram Desktop,
  che è già qualcosa, ma non dice niente su cosa il server conservi o analizzi.
  Non è verificabile da nessuna delle due parti.
- **La lunghezza del blob rivela la lunghezza del testo.** Osservabile
  (mandando due messaggi molto diversi), ma è un residuo accettato, non un
  guasto da segnalare.

### Limiti del lato PC

- **`kc` non risolve un conflitto di chiave**: manca il comando corrispondente a
  *È la sua chiave nuova* (**verificato**: `kc help` elenca `init`, `id`,
  `card`, `contacts`, `name`, `verify`, `encrypt`, `decrypt`, e nient'altro).
- **La chiave privata di `kc` è in chiaro** in `~/.local/share/keyboard-cipher/state`
  (permessi `600`, **verificato**). Quell'identità non vale quanto quella del
  telefono e non va usata per niente di reale.
- **Nessun accesso al telefono da questa macchina**: tutto ciò che riguarda
  Keystore, backup dell'identità, `pm clear`, schermo bloccato e API vecchie
  resta fuori da questo protocollo.

### Vie d'ingresso non coperte

- **`ACTION_PROCESS_TEXT`** (voce *Decifra* nella barra di selezione del
  testo): Telegram Android usa un menu di selezione proprio in diverse
  versioni, quindi la voce potrebbe non comparire. **Non verificato**: se
  compare, provarla; se non compare, non è un guasto della tastiera.
- **Share sheet (`ACTION_SEND`)**: si può provare condividendo un messaggio dei
  messaggi salvati verso l'app della tastiera. **Non verificato** che Telegram
  esponga la voce.
- **Allegati cifrati (file `.kc`)**: **non sono in questa build**. Le classi
  `CipherFiles` e `CipherFileProvider` **non compaiono** in `classes.dex` di
  `tastiera-cifrata-0.1.8-dev1.apk` (**verificato**), pur essendoci le stringhe
  nelle risorse. Sono lavoro non ancora committato: non provare quel percorso.
- **Decifratura automatica di ciò che scorre nella chat**: non esiste per
  costruzione. La tastiera vede il campo di input, mai la cronologia. Ogni
  messaggio ricevuto richiede un gesto deliberato — non è un limite della prova,
  è il limite del prodotto, e va messo in conto come tale.

---

## Appendice A — output reale dei comandi eseguiti sul PC

Tutto quanto segue è stato **eseguito davvero**, con due identità di prova in
`/tmp` (`KC_HOME`), senza toccare l'identità vera.

### A.1 — Due identità affiancate

```
$ KC_HOME=/tmp/kc-test-alice kc init
identita' creata: 75un x6p1 tyiz m7ao p8qm o57c
stato: /tmp/kc-test-alice/state

$ KC_HOME=/tmp/kc-test-bob kc init
identita' creata: ydex 8xwk c75c ud1m q8rg o6k1
stato: /tmp/kc-test-bob/state

$ kc id                       # l'identità vera, intatta
fingerprint: ez9p hsgt j1oh xzfy biib he4o
stato:       /home/user/.local/share/keyboard-cipher/state
contatti:    0
```

### A.2 — Presentazione reciproca

```
$ KC_HOME=/tmp/kc-test-alice kc card
kc/yryobuhftns89m4is37rkf3d3rad5obxjs5nzy7db3hip3fmuk1ezboc6etr6h73ek6afdhynue5t3becahzutgkowpp58xip1qydy9mwoasxezz8ouey98w9bt77bg7zejck3j6kexfojfoctutrptm4jtfn
                                                                    (160 caratteri)

$ KC_HOME=/tmp/kc-test-bob kc decrypt 'kc/yryobuhf…'
nuovo contatto, chiave fissata.
75un x6p1 tyiz m7ao p8qm o57c

Confronta il codice di persona, poi dagli un nome con `kc name`.

$ KC_HOME=/tmp/kc-test-bob kc contacts
0. (senza nome)
   75un x6p1 tyiz m7ao p8qm o57c
```

Il fingerprint fissato da B è **esattamente** quello che A dichiara con
`kc id`. Nell'altro senso (card di B, 208 caratteri, decifrata da A) l'esito è
identico.

### A.3 — Messaggio nei due sensi

```
$ KC_HOME=/tmp/kc-test-alice kc name 0 bob
ora si chiama bob.
$ KC_HOME=/tmp/kc-test-bob kc name 0 alice
ora si chiama alice.

$ KC_HOME=/tmp/kc-test-alice kc encrypt --to bob 'ciao Bob, primo messaggio dal PC'
kc/yryyyyqxosrka97xks58wtezrxruyxqyf7g5ckhdwc881izriqpkjnhgbt79hz4r3bx6y3itaiqrwbx5fihiq9pu6d8o781faz7ih7wgrggwiuej1moc3oyizwnb3zyx8d3ab5fyqbsc9bedc8dwibexc9dat4td7u16b7tmupszckmae4yw7iqwye

$ KC_HOME=/tmp/kc-test-bob kc decrypt 'kc/yryyyyqx…'
da:       alice
chiave:   75un x6p1 tyiz m7ao p8qm o57c
scritto:  10/08/2026 23:30 UTC (secondo il mittente)

ciao Bob, primo messaggio dal PC

$ KC_HOME=/tmp/kc-test-bob kc encrypt --to alice 'risposta di Bob, secondo senso'
$ KC_HOME=/tmp/kc-test-alice kc decrypt '…'
da:       bob
chiave:   ydex 8xwk c75c ud1m q8rg o6k1
scritto:  10/08/2026 23:30 UTC (secondo il mittente)

risposta di Bob, secondo senso
```

Dopo `kc verify alice`, il contatto compare come `0. alice ✓` e la riga `da:`
diventa `alice ✓`.

### A.4 — Conflitto di etichetta

Terza identità (`/tmp/kc-test-carol`, `ytza air7 agot jq4f 1jfh hg9n`), card
fissata sotto Alice, che ha già un `bob`:

```
$ KC_HOME=/tmp/kc-test-alice kc name 1 bob
«bob» appartiene gia' a un'altra chiave. Non ho cambiato nulla.
  quella che ha il nome: ydex 8xwk c75c ud1m q8rg o6k1
  quella nuova:          ytza air7 agot jq4f 1jfh hg9n

Se bob ha cambiato telefono e' normale. Se non lo sa,
qualcuno si sta interponendo: confrontate il codice di persona.
```

Uscita **0** (è un esito, non un errore) e `kc contacts` immutato dopo il
conflitto.

### A.5 — Percorsi negativi

| Comando | Uscita |
|---|---|
| `kc decrypt 'ciao come stai'` | `kc: sentinel non riconosciuto: il testo non e' un blob di questo sistema` (exit 1) |
| card con un carattere alterato a metà | `kc: formato non valido: checksum della identity card non torna` |
| card troncata dentro la pubkey | `kc: decodifica z-base-32 fallita` |
| messaggio con un carattere del ciphertext alterato | `kc: decifratura/autenticazione fallita` |
| messaggio troncato di 20 caratteri | `kc: decifratura/autenticazione fallita` |
| messaggio di A per Bob dato a Carol | `kc: decifratura/autenticazione fallita` |
| blob spezzato da un a capo a metà | `kc: formato non valido: body troncato` |
| `kc encrypt --to zzz 'x'` | `kc: «zzz» non e' un contatto. Vedi 'kc contacts'.` |
| `kc encrypt 'x'` (senza `--to`) | `kc: manca --to <chi>. Vedi 'kc help'.` |
| `kc init` su stato esistente | `kc: esiste gia' un'identita' in … '--force' la sostituisce, e i messaggi ricevuti finora non saranno piu' decifrabili.` |

Corrotto, troncato e destinatario sbagliato danno **lo stesso identico
errore**: è la regola di opacità, verificata.

### A.6 — Dettagli che servono al protocollo

- **`kc card` non scrive niente**: `sha256` del file di stato identico prima e
  dopo (`6e2524f7…`).
- **La card cambia lunghezza a ogni generazione**: 207, 151, 373 caratteri in
  tre esecuzioni.
- **Troncare la coda di una card può non dare errore**: tagliandola da 160 a
  120 caratteri viene decifrata lo stesso, perché si è tolto solo il
  riempimento casuale. Non è un bug — il checksum copre la parte che conta —
  ma significa che **una card "sopravvissuta" non prova che la copia fosse
  completa**. La prova che conta resta il confronto del fingerprint.
- **Con due blob nello stesso testo `kc` decifra il primo**, in silenzio.
  Incollare un blob alla volta.
- Permessi dei file di stato: `600` in tutti e tre i casi.
