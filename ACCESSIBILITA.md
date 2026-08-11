# Ramo `accessibilita` — la tastiera che vede la conversazione

Ramo **separato di proposito**, e non ancora implementato: qui c'è solo la
descrizione di cosa comporterebbe. Sta fuori da `cipher` perché non è una
funzione in più, è un altro prodotto — con un altro modello di minaccia e
un'altra promessa all'utente.

## Cosa si vorrebbe

1. La tastiera sa **con chi stai parlando**, quindi sceglie da sola la chiave
   giusta senza che tu debba dirglielo.
2. La tastiera **vede il messaggio in arrivo** e lo decifra da sola, senza che
   tu debba copiarlo.

Sono la stessa richiesta: entrambe presuppongono che la tastiera veda qualcosa
che oggi, per costruzione, non vede.

## Perché oggi non si può

Un `InputMethodService` riceve `EditorInfo`: package dell'app, tipo di campo,
testo di suggerimento, e poco altro. **La conversazione non c'è**, e non esiste
API per chiederla. Non è una lacuna: è il confine che rende una tastiera
installabile senza permessi.

Le due sole strade che darebbero quell'informazione:

### A. `AccessibilityService`

Legge l'albero delle viste di qualunque app in primo piano: il nome del
contatto in cima alla chat, e il testo dei messaggi.

**Funziona davvero**, ed è l'unica strada che soddisfa entrambe le richieste.

Cosa costa:

- è il permesso più invasivo di Android. Chi lo concede dà all'app la lettura
  di **tutto ciò che appare su ogni schermo**: home banking, password digitate
  in altre app, le altre conversazioni. Il sistema lo dice con un avviso
  apposta spaventoso, ed è giusto che lo faccia;
- inverte la premessa del progetto. Oggi la frase è "una tastiera senza
  permessi, che non può spiarti nemmeno volendo". Con questo diventa "una
  tastiera che legge tutti i tuoi schermi, fidati". Chi valuta il progetto per
  quello che promette ha ragione a rifiutarlo;
- Google Play restringe fortemente le app che lo usano fuori dall'accessibilità
  reale, e F-Droid non lo vieta ma lo segnala come anti-feature;
- va scritto per **ogni app di chat**: il nome del contatto sta in una vista
  diversa in Telegram, WhatsApp, Signal, e cambia a ogni loro aggiornamento.
  È manutenzione senza fine, e quando si rompe si rompe **in silenzio** —
  peggio: potrebbe attribuire la conversazione sbagliata, cioè cifrare per la
  persona sbagliata, che è il fallimento peggiore che questo sistema possa
  produrre.

### B. `NotificationListenerService`

Darebbe mittente e testo del messaggio in arrivo, quindi risolverebbe la
seconda richiesta ma non la prima (non dice a chi stai *scrivendo*).

Cosa costa:

- richiede l'accesso a **tutte** le notifiche del dispositivo. Più stretto
  dell'accessibilità, ma sempre molto largo;
- *da verificare, non dato per certo:* se il testo nei dati della notifica sia
  completo o troncato per i messaggi lunghi. I nostri blob stanno fra 150 e 400
  caratteri. La documentazione del progetto dà per scontato il troncamento, ma
  quella affermazione **non è mai stata misurata** — va provata prima di
  fondarci qualcosa.

## Cosa si può fare senza niente di tutto questo

Da fare comunque, indipendentemente da come finisce questa decisione:

- **mostrare nella riga di composizione per chi si sta cifrando** ("→ Marco"),
  con un tocco per cambiarlo. Non risolve la scelta automatica, ma toglie il
  dubbio: oggi l'utente non ha modo di sapere verso chi sta cifrando finché non
  preme;
- l'apertura automatica di ciò che si copia, già fatta nel ramo `cipher`: non
  legge la chat, si aggancia all'unico momento in cui l'utente ha già
  dichiarato di voler leggere quel messaggio;
- il destinatario per app e la scelta implicita dopo aver decifrato, già fatte:
  coprono il caso dominante, cioè leggo e rispondo.

## Se questo ramo va avanti

Va aperta e chiusa una decisione, come per tutte le altre di questo progetto:

- **H1.** Quale delle due strade, o nessuna.
- **H2.** Come si dice all'utente cosa sta concedendo. Un avviso onesto, non
  una schermata che minimizza.
- **H3.** Cosa succede quando il riconoscimento della conversazione fallisce o
  cambia sotto i piedi. La sola risposta accettabile è **non indovinare**: se
  non si è certi, si chiede — cifrare per la persona sbagliata resta il
  fallimento peggiore, e un servizio di accessibilità rende quel fallimento
  più probabile, non meno.
- **H4.** Se il ramo sia distribuibile insieme all'altro o sia un'app diversa,
  con nome diverso, così che nessuno installi per sbaglio quella che legge lo
  schermo credendo di installare quella che non lo fa.

Finché H1 è aperta, qui non si scrive codice.
