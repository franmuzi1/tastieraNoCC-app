// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.view.inputmethod.EditorInfo

/**
 * Un messaggio troppo lungo per una chat, spezzato in piu' messaggi cifrati
 * separatamente.
 *
 * ## Il difetto da cui nasce
 *
 * Il tetto esiste per una ragione seria: Telegram si ferma a 4096 caratteri, e
 * un blob che l'app spezza per conto suo **non si decifra piu'** — la meta' che
 * arriva non e' un messaggio piu' corto, e' spazzatura. Quindi sopra la soglia
 * si rifiutava di cifrare, dicendo quanti caratteri togliere.
 *
 * Per chi scrive quel rifiuto non somigliava a un rifiuto. Con la riga di
 * composizione accesa il chiaro non e' mai stato nel campo dell'app: sta nel
 * buffer della tastiera, dietro a un avviso che passa in due secondi, e il
 * gesto successivo — cambiare chat, chiudere la tastiera — se lo porta via. Il
 * messaggio non veniva perso da un difetto: veniva perso perche' l'unica via
 * offerta era riscriverlo piu' corto.
 *
 * ## Cosa si fa invece
 *
 * Lo si taglia in parti che stanno nel tetto e si cifra **ogni parte per
 * conto suo**. Ne escono N messaggi normali: nessun formato nuovo, niente da
 * cambiare nel core, e chi riceve li apre uno per uno con la versione che ha
 * gia'.
 *
 * ## Perche' il contrassegno «(1/3)» sta nel chiaro
 *
 * Perche' e' l'unico posto dove chi legge lo vede. Metterlo nell'involucro
 * vorrebbe dire cambiare il formato — e allora servirebbe che anche l'altro
 * aggiorni — e comunque la ricomposizione automatica non e' possibile: le parti
 * arrivano come messaggi distinti, si aprono una alla volta, e ognuna finisce
 * in una schermata sua. Senza contrassegno, tre messaggi che continuano l'uno
 * nell'altro sono indistinguibili da tre messaggi diversi arrivati vicini, e
 * chi legge non ha modo di accorgersi che ne manca uno.
 *
 * Il prezzo e' che il contrassegno **si vede**, e che occupa spazio dentro il
 * tetto: e' contato nel taglio, non dopo.
 *
 * ## Perche' il taglio non si calcola con una formula
 *
 * La lunghezza del blob dipende dallo schema di cifratura, dal numero di slot
 * di un gruppo e dalla codifica z-base-32. Quella stima esiste gia' in
 * [CipherActions] ed e' l'unica che decide se un messaggio parte: rifarla qui
 * significherebbe due fonti di verita' che un giorno divergono, e la direzione
 * in cui divergerebbero e' quella cattiva — un blob che passa il controllo di
 * qui e viene poi spezzato dalla chat. Percio' [dividi] non calcola niente:
 * riceve la stessa domanda che si fa il chiamante — «questo ci sta?» — e la usa
 * per cercare il taglio piu' lungo che ancora risponde di si'.
 */
internal object CipherParti {

    /**
     * Oltre questo non si spezza piu': si rifiuta come prima.
     *
     * Non e' un limite tecnico, e' il punto in cui spezzare smette di essere un
     * favore. Dodici messaggi di fila in una chat sono un muro per chi li
     * riceve, e chi ha incollato un documento intero dentro il campo di una
     * chat probabilmente voleva mandare un file — che questa tastiera sa
     * cifrare per conto suo, e in un pezzo solo.
     */
    const val MAX_PARTI = 12

    /**
     * Taglia [testo] in parti contrassegnate che stanno tutte nel tetto.
     *
     * @param ciSta la stessa domanda che si fa il chiamante prima di cifrare,
     *   applicata al testo gia' contrassegnato. Deve essere monotona: se un
     *   testo ci sta, ogni suo prefisso ci sta.
     * @return le parti pronte da cifrare, contrassegno compreso, oppure `null`
     *   se nemmeno [MAX_PARTI] bastano.
     */
    fun dividi(testo: String, ciSta: (String) -> Boolean): List<String>? {
        if (testo.isEmpty()) return null
        var quante = 2
        while (quante <= MAX_PARTI) {
            // Si taglia riservando lo spazio del contrassegno PIU' LARGO che
            // questo numero di parti possa produrre — "(12/12) " e non
            // "(1/12) ". I contrassegni veri sono tutti piu' corti o uguali,
            // quindi cio' che entra nel taglio entra anche dopo l'etichetta.
            val pezzi = taglia(testo, marcatore(quante, quante), ciSta) ?: return null
            if (pezzi.size <= quante) {
                // Un pezzo solo non e' spezzato: niente contrassegno, che
                // direbbe «(1/1)» e non informerebbe nessuno. Non capita da
                // [CipherActions] — che chiama solo quando il testo NON ci sta,
                // e il contrassegno puo' solo allungarlo — ma una funzione che
                // risponde male a una domanda legittima e' un difetto che
                // aspetta il primo chiamante nuovo.
                if (pezzi.size == 1) return listOf(testo)
                return pezzi.mapIndexed { i, pezzo -> marcatore(i + 1, pezzi.size) + pezzo }
            }
            // Il taglio ha prodotto piu' pezzi di quanti se ne erano previsti:
            // il contrassegno diventa piu' largo e il conto va rifatto. Sale
            // sempre, quindi il giro finisce.
            quante = pezzi.size
        }
        return null
    }

    private fun marcatore(quale: Int, quante: Int) = "($quale/$quante) "

    /**
     * Taglio avido: ogni pezzo e' il piu' lungo che ci sta, e si arretra fino
     * all'ultimo stacco di parola per non spezzare a meta' una parola.
     */
    private fun taglia(
        testo: String,
        etichetta: String,
        ciSta: (String) -> Boolean,
    ): List<String>? {
        val pezzi = mutableListOf<String>()
        var resto = testo
        while (resto.isNotEmpty()) {
            // Piu' di MAX_PARTI pezzi non servono a nessuno: chi chiama
            // riprova con un numero piu' alto e si ferma da solo al tetto.
            if (pezzi.size > MAX_PARTI) return pezzi
            val quanto = quantoCiSta(resto, etichetta, ciSta)
            // Nemmeno un carattere: il tetto e' talmente stretto che il solo
            // contrassegno lo riempie. Non e' un caso reale con i valori di
            // oggi, ma restituire una lista vuota lo sarebbe.
            if (quanto <= 0) return null
            pezzi.add(resto.substring(0, quanto))
            resto = resto.substring(quanto)
        }
        return pezzi
    }

    /**
     * Quanti caratteri di [resto] stanno in un pezzo, contrassegno compreso.
     *
     * Ricerca binaria perche' [ciSta] passa dalla stima del blob, e provarci un
     * carattere alla volta su un messaggio da ottomila sarebbe ottomila
     * chiamate. La monotonia richiesta a [ciSta] e' cio' che la rende lecita.
     */
    private fun quantoCiSta(
        resto: String,
        etichetta: String,
        ciSta: (String) -> Boolean,
    ): Int {
        if (ciSta(etichetta + resto)) return resto.length
        var basso = 0
        var alto = resto.length
        while (basso < alto) {
            val mezzo = (basso + alto + 1) / 2
            if (ciSta(etichetta + resto.substring(0, mezzo))) basso = mezzo else alto = mezzo - 1
        }
        // Mai in mezzo a una coppia surrogata: tagliare li' produce due meta'
        // di carattere, e la seconda meta' apre il pezzo successivo con un
        // simbolo che non esiste. Le emoji sono tutte cosi'.
        if (basso in 1 until resto.length && resto[basso - 1].isHighSurrogate()) basso--
        if (basso <= 0) return basso
        // Arretra fino all'ultimo stacco, ma solo se e' vicino: tagliare a
        // meta' di una parola si legge male, e tagliare all'inizio del pezzo
        // per rispettare uno stacco lontano produrrebbe piu' parti del
        // necessario. Un quarto del pezzo e' il compromesso.
        val minimo = basso - basso / 4
        val stacco = resto.lastIndexOf('\n', basso - 1).coerceAtLeast(
            resto.lastIndexOf(' ', basso - 1),
        )
        return if (stacco >= minimo && stacco > 0) stacco + 1 else basso
    }

    // --- La coda delle parti gia' cifrate --------------------------------

    /**
     * Su quale campo si sta consegnando: pacchetto, id della vista e tipo.
     *
     * Non basta il pacchetto, ed e' una lezione che questo progetto ha gia'
     * pagato altrove: dentro WhatsApp ci sono tutte le conversazioni, e una
     * parte consegnata dopo aver cambiato chat andrebbe a un'altra persona. Non
     * la leggerebbe — e' cifrata per il destinatario di prima — ma le
     * arriverebbe, e chi scrive perderebbe il pezzo senza accorgersene.
     *
     * E' la stessa terna che [CipherCompose] usa per la riga forzata, con lo
     * stesso limite dichiarato: due campi davvero identici in due schermate
     * diverse restano indistinguibili. Qui il costo di sbagliare e' piu' basso
     * del costo di non provarci.
     */
    private var campoDellaCoda: Triple<String, Int, Int>? = null

    /** I blob ancora da consegnare, gia' cifrati, in ordine. */
    private val coda = ArrayDeque<String>()

    /** Quante parti erano in tutto, per poterlo dire mentre si consegna. */
    private var totale = 0

    /**
     * Se la prima parte e' uscita dalla riga di composizione.
     *
     * Serve a non cambiare comportamento a meta' messaggio. In modalita' campo
     * il fork non chiede mai all'app di spedire — l'invio automatico vale solo
     * per la riga — e senza questa memoria le parti dalla seconda in poi
     * sarebbero partite da sole mentre la prima aspettava un tocco: lo stesso
     * messaggio, spedito in due modi diversi, e' il genere di sorpresa che fa
     * credere che la tastiera abbia mandato qualcosa di sua iniziativa.
     */
    private var dallaRiga = false

    private fun identita(campo: EditorInfo?): Triple<String, Int, Int>? {
        val app = campo?.packageName.orEmpty()
        if (campo == null || app.isEmpty()) return null
        return Triple(app, campo.fieldId, campo.inputType)
    }

    /**
     * Su questo campo una coda si puo' tenere.
     *
     * Si chiede **prima** di spezzare, non dopo aver consegnato la prima
     * parte. Un campo senza identita' utilizzabile — nessun `EditorInfo`,
     * pacchetto vuoto — non puo' ospitare una coda, e accorgersene dopo
     * significherebbe aver gia' detto all'utente «parte 1 di 3» per poi non
     * consegnargli mai le altre due. Meglio non spezzare affatto e rifiutare
     * come si faceva prima: il testo resta dov'e'.
     */
    fun campoUtilizzabile(campo: EditorInfo?): Boolean = identita(campo) != null

    /**
     * Prende in carico le parti successive alla prima.
     *
     * Arrivano **gia' cifrate**: e' ciphertext, non chiaro, e tenerlo in
     * memoria non aggiunge niente a cio' che e' gia' uscito nel campo.
     * Cifrarle a mano a mano avrebbe l'effetto opposto — il chiaro dovrebbe
     * restare qui dentro per tutto il tempo, e un fallimento a meta'
     * lascerebbe il messaggio consegnato per meta'.
     */
    fun accoda(campo: EditorInfo?, blob: List<String>, quanteInTutto: Int, dallaRiga: Boolean) {
        scarta()
        val identita = identita(campo) ?: return
        if (blob.isEmpty()) return
        campoDellaCoda = identita
        totale = quanteInTutto
        this.dallaRiga = dallaRiga
        coda.addAll(blob)
    }

    /** C'e' una parte pronta per questo campo? */
    fun inAttesaSu(campo: EditorInfo?): Boolean {
        val identita = identita(campo) ?: return false
        return coda.isNotEmpty() && identita == campoDellaCoda
    }

    /** La prima parte e' uscita dalla riga, quindi l'invio automatico vale. */
    fun daRiga(): Boolean = dallaRiga

    /**
     * Quanto era lungo il blob consegnato per ultimo, in caratteri.
     *
     * Serve a distinguere **un campo che si e' svuotato perche' il messaggio e'
     * partito** da uno svuotato in qualunque altro modo. Sono due cose diverse
     * e vogliono due reazioni opposte: nel primo caso tocca alla parte
     * successiva, nel secondo l'utente ha appena cancellato quel pezzo e
     * rimetterglielo davanti significherebbe non lasciarglielo buttare.
     *
     * Il segno si legge nelle posizioni che `onUpdateSelection` porta con se':
     * dopo un invio il cursore stava in fondo al blob — [ultimaLunghezza] —
     * e il campo passa a vuoto in un colpo; cancellando a mano ci si arriva
     * da una posizione qualunque, e selezionando tutto da zero.
     */
    fun ultimaLunghezza(): Int = ultimaLunghezza

    private var ultimaLunghezza = 0

    /** Registra la parte appena finita nel campo. */
    fun consegnata(blob: String) {
        ultimaLunghezza = blob.length
    }

    /** Il numero della prossima parte e il totale, per l'avviso. */
    fun prossimaEtichetta(): Pair<Int, Int> = Pair(totale - coda.size + 1, totale)

    /**
     * Guarda la prossima parte **senza toglierla**.
     *
     * Guardare e togliere sono due gesti separati, ed e' una correzione: prima
     * si toglieva e poi si provava a consegnare, cosi' che un `commitText`
     * fallito — connessione morta, campo che tronca — lasciasse la parte fuori
     * dalla coda e senza essere arrivata da nessuna parte. Quel blob non era
     * uscito: buttarlo, e con lui il resto del messaggio, era la reazione
     * sbagliata al fallimento piu' recuperabile che ci sia.
     *
     * Adesso si toglie solo dopo che il campo l'ha presa, con [consuma], e un
     * fallimento lascia la coda intatta: si riprova col lucchetto.
     */
    fun prossima(campo: EditorInfo?): String? =
        if (inAttesaSu(campo)) coda.first() else null

    /** Toglie la parte appena consegnata. Da chiamare **dopo** il successo. */
    fun consuma() {
        if (coda.isNotEmpty()) coda.removeFirst()
        if (coda.isEmpty()) scarta()
    }

    /** Butta la coda. Chiamata quando si ricomincia da un messaggio nuovo. */
    fun scarta() {
        coda.clear()
        campoDellaCoda = null
        totale = 0
        dallaRiga = false
        ultimaLunghezza = 0
    }
}
