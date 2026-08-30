package helium314.keyboard.cipher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.TextView
import kotlin.math.abs

/**
 * La riga di composizione, con il cursore disegnato a mano.
 *
 * ## Perche' a mano
 *
 * La finestra di un IME non prende il fuoco — se lo prendesse, lo toglierebbe
 * al campo dell'app, che e' esattamente cio' che la tastiera serve a riempire.
 * Ma un `TextView` disegna il caret solo quando ha il fuoco, e un `EditText`
 * qui dentro non lo avrebbe mai. Quindi il cursore che si vede in questa riga
 * non e' quello di sistema: e' una barra disegnata in [onDraw] alla posizione
 * che il buffer dichiara.
 *
 * Non e' un vezzo estetico. Senza, chi scrive non sa dove finira' il prossimo
 * carattere dopo aver spostato il cursore o cancellato — e in un campo dove il
 * testo non e' visibile altrove, non c'e' modo di dedurlo.
 *
 * ## Lampeggio
 *
 * Mezzo secondo acceso, mezzo spento, come qualunque campo di testo. Va
 * fermato quando la riga non e' visibile o non e' attaccata: un `invalidate`
 * ogni mezzo secondo su una tastiera chiusa e' lavoro puro a spese della
 * batteria.
 *
 * Digitando il caret torna **acceso e la fase riparte**: se non lo facesse,
 * potrebbe risultare spento proprio nell'istante in cui si guarda dove si sta
 * scrivendo.
 *
 * ## Il tocco
 *
 * Tocco = cursore li'. Trascinamento = selezione. Pressione lunga = la parola
 * sotto il dito. Sono i tre gesti di qualunque campo di testo, e qui vanno
 * scritti a mano per lo stesso motivo del cursore: un `TextView` senza fuoco
 * non li fa da solo.
 *
 * Senza, l'unico modo di cancellare una frase era premere cancella tante volte
 * o tenerlo premuto — perche' il cursore si sposta di un carattere per volta e
 * non c'e' niente che si possa prendere in blocco.
 */
class CipherComposeView(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {

    private val caretPaint = Paint().apply { isAntiAlias = false }
    private val selectionPaint = Paint().apply { isAntiAlias = false }

    private var selectionStart = 0
    private var selectionEnd = 0

    private var caretOn = true

    /**
     * Dove il dito ha toccato per primo: la selezione si estende **da li'**, e
     * non dal punto in cui si trova adesso.
     */
    private var ancora = 0

    private var trascinando = false

    private var xIniziale = 0f
    private var yIniziale = 0f

    /** Vero dalla pressione lunga fino al rilascio: il tocco ha gia' fatto. */
    private var giaSelezionato = false

    /**
     * Vero dal primo tocco al rilascio, qualunque cosa il gesto stia facendo.
     *
     * Mentre il dito e' giu' **decide il dito** dove si guarda, e [setComposed]
     * non deve rimettere in vista il cursore. Non e' un dettaglio: selezionando
     * all'indietro, `selectionEnd` e' l'**ancora** — il punto da cui il gesto e'
     * partito, cioe' quello piu' avanti nel testo — e riportarla in vista a
     * ogni movimento ritirava indietro la vista proprio mentre si cercava di
     * leggere piu' su. Da fuori sembrava che il testo tornasse sempre
     * sull'ultima riga scritta.
     *
     * Misurato: per ogni spostamento del dito arrivavano tre richieste, due
     * `portaInVista(63)` dall'ancora e una `portaInVista(30)` dal dito, che si
     * annullavano a vicenda.
     */
    private var gestoInCorso = false

    /**
     * La parola presa dalla pressione lunga.
     *
     * Continuando a tenere premuto arrivano altri `ACTION_MOVE` sullo stesso
     * punto, e senza questi due la selezione si richiudeva a cursore mezzo
     * istante dopo essersi aperta: la parola si vedeva sparire da sola. Da qui
     * in poi il trascinamento **allarga** — sotto la parola non si scende.
     */
    private var parolaInizio = -1
    private var parolaFine = -1

    /**
     * Chi ascolta la selezione. La vista non tocca il buffer: dice dove il dito
     * ha messo il cursore, e chi possiede il testo decide. Il buffer e' l'unica
     * fonte di verita' su dove finisce il prossimo carattere, e due posti che
     * lo scrivono sarebbero due verita'.
     */
    var onSelezione: ((Int, Int) -> Unit)? = null

    /**
     * Il dito si e' alzato e sotto e' rimasta una selezione: e' il momento di
     * offrire cosa farci. Riceve la x del punto lasciato, per far uscire la
     * tendina da li' invece che da un angolo fisso.
     *
     * Al rilascio e non durante: mentre si trascina la selezione cambia a ogni
     * pixel, e un menu che compare in mezzo al gesto e' un menu che si finisce
     * per toccare per sbaglio.
     */
    var onMenu: ((Float) -> Unit)? = null

    /** Chiude la tendina: la selezione non c'e' piu', o non e' piu' sua. */
    var onMenuDaChiudere: (() -> Unit)? = null

    /**
     * Pressione lunga sulla riga VUOTA. Serve a offrire "incolla": prima non
     * succedeva niente, e l'unico modo di incollare era il tasto in barra —
     * che pero' sparisce quando si aprono le emoji.
     */
    var onMenuVuoto: ((Float) -> Unit)? = null

    /**
     * Il dito ha toccato la riga, qualunque cosa stia per fare.
     *
     * Serve a togliere di mezzo il pannello del messaggio decifrato: toccare la
     * riga vuol dire "voglio rispondere", e il pannello copre i tasti. Chiuderlo
     * a mano prima era un passaggio che non aggiungeva niente.
     *
     * Scatta **prima** dell'uscita anticipata a testo vuoto qui sotto: la riga
     * vuota e' proprio il caso in cui si sta per rispondere.
     */
    var onToccata: (() -> Unit)? = null

    /**
     * Cosa sta facendo il dito.
     *
     * ## Perche' il trascinamento SCORRE e non seleziona
     *
     * Prima ogni trascinamento era una selezione, e questo lasciava la riga
     * senza il gesto piu' ovvio: quello per leggere cio' che non ci sta. In una
     * casella alta due o tre righe il testo sotto era irraggiungibile, perche'
     * una selezione arriva solo dove arriva il dito e sotto il bordo non c'e'
     * spazio per andare.
     *
     * Il modello dei campi di testo di Android e' un altro, ed e' quello giusto
     * anche qui: **trascinare scorre**, si seleziona con la pressione lunga o
     * con il doppio tocco, e poi si aggiustano gli estremi con le maniglie. Da
     * qui vengono, tutte insieme, l'inerzia e lo scorrimento al bordo mentre si
     * trascina una maniglia.
     */
    private enum class Gesto { NESSUNO, ATTESA, SCORRIMENTO, MANIGLIA_INIZIO, MANIGLIA_FINE, ESTENDE }

    private var gesto = Gesto.NESSUNO
    private val scroller = OverScroller(context)
    private var velocita: VelocityTracker? = null
    private var yPrecedente = 0f

    /** Il dito si e' mosso durante questo gesto. Vedi il rilascio di una maniglia. */
    private var mossoDurante = false
    private var ultimoRilascio = 0L
    private var xUltimoRilascio = 0f
    private var yUltimoRilascio = 0f

    /** Le maniglie prendono il colore del cursore: sono la stessa cosa. */
    private val manigliePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun raggioManiglia(): Float = RAGGIO_MANIGLIA_DP * resources.displayMetrics.density

    /**
     * Un offset che si puo' dare a `Layout` senza farlo esplodere.
     *
     * `getPrimaryHorizontal` e `getLineForOffset` indicizzano il testo: fuori
     * intervallo sollevano, e qui sollevare vuol dire **la tastiera che
     * sparisce mentre si scrive** — `disegnaManiglie` gira dentro `onDraw`.
     *
     * Non e' teoria. Gli estremi della selezione appartengono alla vista, ma il
     * buffer viene svuotato **da fuori**: `CipherCompose.clear()` scrive
     * direttamente sull'`Editable` dopo un invio riuscito. Fra quella scrittura
     * e l'aggiornamento della selezione c'e' una finestra in cui gli estremi
     * puntano oltre la fine del testo — cioe' selezionare qualcosa e premere
     * invia.
     *
     * La protezione c'era su una chiamata e mancava su quella subito dopo, il
     * che dice che il rischio era stato visto e poi perso di vista.
     */
    private fun offsetValido(offset: Int): Int = offset.coerceIn(0, text?.length ?: 0)

    /** Il centro della maniglia di un estremo, in coordinate della vista. */
    private fun centroManiglia(offset: Int): Pair<Float, Float>? {
        val l = layout ?: return null
        val sicuro = offsetValido(offset)
        val riga = l.getLineForOffset(sicuro)
        val x = l.getPrimaryHorizontal(sicuro) + totalPaddingLeft - scrollX
        val y = l.getLineBottom(riga).toFloat() + totalPaddingTop - scrollY + raggioManiglia()
        return x to y
    }

    private fun manigliaToccata(x: Float, y: Float): Gesto {
        if (selectionEnd <= selectionStart) return Gesto.NESSUNO
        // Il bersaglio e' il doppio del disegno: una pallina di sette punti e'
        // facile da vedere e difficile da centrare.
        val tolleranza = raggioManiglia() * 2f
        centroManiglia(selectionStart)?.let { (cx, cy) ->
            if (abs(x - cx) < tolleranza && abs(y - cy) < tolleranza) return Gesto.MANIGLIA_INIZIO
        }
        centroManiglia(selectionEnd)?.let { (cx, cy) ->
            if (abs(x - cx) < tolleranza && abs(y - cy) < tolleranza) return Gesto.MANIGLIA_FINE
        }
        return Gesto.NESSUNO
    }

    /** Quanto si puo' scorrere in tutto, in pixel. */
    private fun scorrimentoMassimo(): Int {
        val l = layout ?: return 0
        val visibile = (if (height > 0) height else measuredHeight) -
            totalPaddingTop - totalPaddingBottom
        // Si puo' scorrere un po' OLTRE l'ultima riga, quanto basta per la
        // maniglia: quella si disegna sotto la riga, e sull'ultima finiva fuori
        // dal contenuto scorribile — visibile a meta' e impossibile da
        // afferrare. E' anche il motivo per cui non serve una riga vuota in
        // fondo: il testo dell'utente non si tocca, si allarga lo spazio.
        val perLaManiglia = (raggioManiglia() * 3f).toInt()
        return (l.height + perLaManiglia - visibile).coerceAtLeast(0)
    }

    /**
     * Lo scorrimento comandato dal dito **disarma** quello che insegue il
     * cursore.
     *
     * [applicaScorrimento] e' un invariante e si riapplica a ogni disegno: senza
     * questa riga, ogni trascinamento, inerzia o scorrimento al bordo veniva
     * annullato al frame successivo. Da fuori sembrava che lo scorrimento
     * manuale non esistesse — ed era proprio cosi', durava un fotogramma.
     */
    private fun scorriAMano(y: Int) {
        daPortareInVista = -1
        scrollTo(0, y)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scorriAMano(scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    /**
     * Le due palline agli estremi della selezione.
     *
     * Servono a **correggere** una selezione senza rifarla: prima, sbagliata la
     * parola, si doveva ricominciare il gesto da capo.
     */
    private fun disegnaManiglie(canvas: Canvas, left: Float, top: Float) {
        if (selectionEnd <= selectionStart) return
        val l = layout ?: return
        val raggio = raggioManiglia()
        for (grezzo in intArrayOf(selectionStart, selectionEnd)) {
            val offset = offsetValido(grezzo)
            val riga = l.getLineForOffset(offset)
            canvas.drawCircle(
                left + l.getPrimaryHorizontal(offset),
                top + l.getLineBottom(riga) + raggio,
                raggio,
                manigliePaint,
            )
        }
    }

    /** Ultima posizione del dito, per lo scorrimento al bordo. */
    private var xCorrente = 0f
    private var yCorrente = 0f

    /**
     * Scorre di una riga mentre il dito sta sul bordo, e allunga la selezione
     * fin dove e' arrivato.
     *
     * E' il comportamento standard della selezione di testo su Android — il
     * dito al bordo fa scorrere — e serve perche' una selezione arriva solo
     * dove arriva il dito: senza, il testo fuori dalla finestra non si puo'
     * selezionare, in una casella alta due o tre righe.
     */
    private val scorrimentoAlBordo = object : Runnable {
        override fun run() {
            if (gesto == Gesto.NESSUNO || gesto == Gesto.SCORRIMENTO) return
            val margine = MARGINE_BORDO_DP * resources.displayMetrics.density
            val giu = yCorrente > height - margine
            val su = yCorrente < margine
            if (!giu && !su) {
                // Il dito non e' al bordo ADESSO, ma il gesto e' ancora in
                // corso: si resta armati. Uscendo senza riprogrammarsi, bastava
                // fermare il dito un attimo lontano dal bordo perche' lo
                // scorrimento non ripartisse piu' — e senza nuovi movimenti
                // nessuno lo avrebbe riacceso.
                postDelayed(this, INTERVALLO_BORDO_MS)
                return
            }

            val passo = lineHeight.coerceAtLeast(1)
            val nuovo = if (giu) {
                (scrollY + passo).coerceAtMost(scorrimentoMassimo())
            } else {
                (scrollY - passo).coerceAtLeast(0)
            }
            if (nuovo != scrollY) {
                scorriAMano(nuovo)
                // La selezione segue: il punto sotto il dito e' cambiato perche'
                // e' cambiato cio' che c'e' sotto, non il dito.
                val offset = getOffsetForPosition(xCorrente, yCorrente)
                    .coerceIn(0, text?.length ?: 0)
                when (gesto) {
                    Gesto.MANIGLIA_INIZIO ->
                        onSelezione?.invoke(minOf(offset, parolaFine), maxOf(offset, parolaFine))
                    Gesto.MANIGLIA_FINE ->
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaInizio, offset))
                    Gesto.ESTENDE ->
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaFine, offset))
                    else -> {}
                }
            }
            postDelayed(this, INTERVALLO_BORDO_MS)
        }
    }

    private val menuVuoto = Runnable { onMenuVuoto?.invoke(xIniziale) }

    private val pressioneLunga = Runnable {
        val (inizio, fine) = parolaIntorno(ancora)
        if (fine > inizio) {
            gesto = Gesto.ESTENDE
            giaSelezionato = true
            parolaInizio = inizio
            parolaFine = fine
            onSelezione?.invoke(inizio, fine)
            invalidate()
        }
    }

    private val blink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            postDelayed(this, BLINK_MILLIS)
        }
    }

    /**
     * Il colore del testo, non un accento: la tavolozza di HeliBoard non ne
     * espone uno, e un colore inventato sarebbe l'unico elemento della
     * tastiera a non seguire il tema scelto dall'utente.
     */
    fun setCaretColor(color: Int) {
        caretPaint.color = color
        // La selezione e' lo stesso colore molto trasparente: sotto ci deve
        // restare leggibile il testo.
        selectionPaint.color = (color and 0x00FFFFFF) or (SELECTION_ALPHA shl 24)
        // Le maniglie sono il cursore con un'altra forma: stesso colore pieno.
        manigliePaint.color = color
        invalidate()
    }

    /** Testo e posizione del cursore, presi dal buffer. */
    fun setComposed(text: CharSequence, start: Int, end: Int) {
        setText(text)
        selectionStart = start.coerceIn(0, text.length)
        selectionEnd = end.coerceIn(selectionStart, text.length)
        caretOn = true
        restartBlink()
        // Mentre il dito e' giu' comanda lui: vedi [gestoInCorso].
        if (!gestoInCorso) scrollToCaret()
        invalidate()
    }

    /**
     * I tre gesti.
     *
     * Si consuma **tutto** dal primo tocco in poi: se si lasciasse passare
     * qualcosa, il resto della sequenza andrebbe a chi sta sotto, e un
     * trascinamento comincerebbe qui per finire altrove.
     *
     * `requestDisallowInterceptTouchEvent` perche' la riga sta dentro la
     * striscia dei suggerimenti, che i movimenti orizzontali li intercetta per
     * scorrere: senza, il trascinamento per selezionare glielo verrebbe portato
     * via a meta'.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onToccata?.invoke()
        if (text.isNullOrEmpty()) {
            // Riga vuota: l'unico gesto che ha senso e' la pressione lunga per
            // incollare. Il resto — selezione, maniglie, scorrimento — non ha
            // niente su cui lavorare.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xIniziale = event.x
                    postDelayed(menuVuoto, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(event.x - xIniziale) >
                        ViewConfiguration.get(context).scaledTouchSlop
                    ) {
                        removeCallbacks(menuVuoto)
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(menuVuoto)
            }
            return true
        }
        val offset = offsetDelTocco(event)
        if (velocita == null) velocita = VelocityTracker.obtain()
        velocita?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                gestoInCorso = true
                xIniziale = event.x
                yIniziale = event.y
                xCorrente = event.x
                yCorrente = event.y
                yPrecedente = event.y
                ancora = offset
                trascinando = false
                giaSelezionato = false

                // Una maniglia ha la precedenza su tutto: se il dito parte da
                // li', si sta aggiustando una selezione che esiste gia'.
                val maniglia = manigliaToccata(event.x, event.y)
                if (maniglia != Gesto.NESSUNO) {
                    gesto = maniglia
                    mossoDurante = false
                    parolaInizio = selectionStart
                    parolaFine = selectionEnd
                    return true
                }

                // Un tocco nuovo annulla quello di prima: la tendina rimasta
                // aperta si riferirebbe a una selezione che sta per cambiare.
                onMenuDaChiudere?.invoke()

                // Doppio tocco: seleziona la parola, e da li' si allarga
                // trascinando. E' il gesto piu' usato per selezionare, e prima
                // funzionava solo la pressione lunga.
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val doppio = event.eventTime - ultimoRilascio <=
                    ViewConfiguration.getDoubleTapTimeout() &&
                    abs(event.x - xUltimoRilascio) < slop &&
                    abs(event.y - yUltimoRilascio) < slop
                if (doppio) {
                    val (i, f) = parolaIntorno(offset)
                    if (f > i) {
                        gesto = Gesto.ESTENDE
                        giaSelezionato = true
                        parolaInizio = i
                        parolaFine = f
                        onSelezione?.invoke(i, f)
                        invalidate()
                        return true
                    }
                }

                gesto = Gesto.ATTESA
                parolaInizio = -1
                parolaFine = -1
                postDelayed(pressioneLunga, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                xCorrente = event.x
                yCorrente = event.y
                mossoDurante = true
                if (gesto == Gesto.ATTESA) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(event.x - xIniziale) > slop || abs(event.y - yIniziale) > slop) {
                        // Il dito si e' mosso senza aver selezionato niente:
                        // vuole leggere, non scegliere. Via la pressione lunga,
                        // che a questo punto sarebbe una sorpresa.
                        removeCallbacks(pressioneLunga)
                        gesto = Gesto.SCORRIMENTO
                        trascinando = true
                    }
                }
                when (gesto) {
                    Gesto.SCORRIMENTO -> {
                        val dy = (yPrecedente - event.y).toInt()
                        val nuovo = (scrollY + dy).coerceIn(0, scorrimentoMassimo())
                        if (nuovo != scrollY) scorriAMano(nuovo)
                        yPrecedente = event.y
                    }
                    Gesto.MANIGLIA_INIZIO -> {
                        onSelezione?.invoke(minOf(offset, parolaFine), maxOf(offset, parolaFine))
                        avviaScorrimentoAlBordo()
                    }
                    Gesto.MANIGLIA_FINE -> {
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaInizio, offset))
                        avviaScorrimentoAlBordo()
                    }
                    Gesto.ESTENDE -> {
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaFine, offset))
                        avviaScorrimentoAlBordo()
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(pressioneLunga)
                removeCallbacks(scorrimentoAlBordo)
                when (gesto) {
                    // Tocco secco: il cursore va li'.
                    Gesto.ATTESA -> onSelezione?.invoke(offset, offset)
                    Gesto.SCORRIMENTO -> lanciaInerzia()
                    // Una maniglia toccata e rilasciata SENZA muoversi non e'
                    // un aggiustamento: e' un tocco, e un tocco sposta il
                    // cursore. Senza questo caso, toccando altrove per uscire
                    // dalla selezione il dito finiva spesso su una maniglia — le
                    // due palline stanno proprio dove si e' appena guardato — e
                    // il cursore restava dov'era mentre la tendina si riapriva:
                    // da fuori sembrava che il cursore saltasse a caso.
                    Gesto.MANIGLIA_INIZIO, Gesto.MANIGLIA_FINE ->
                        if (!mossoDurante) {
                            onSelezione?.invoke(offset, offset)
                            onMenuDaChiudere?.invoke()
                        } else if (selectionEnd > selectionStart) {
                            onMenu?.invoke(event.x)
                        }
                    // Un gesto che ha prodotto una selezione apre la tendina.
                    else -> if (selectionEnd > selectionStart) onMenu?.invoke(event.x)
                }
                ultimoRilascio = event.eventTime
                xUltimoRilascio = event.x
                yUltimoRilascio = event.y
                chiudiGesto()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pressioneLunga)
                removeCallbacks(scorrimentoAlBordo)
                chiudiGesto()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun avviaScorrimentoAlBordo() {
        // Si riarma a ogni movimento e si ferma da solo quando il dito rientra:
        // cosi' non c'e' uno stato "sto gia' scorrendo" da tenere allineato, ed
        // e' proprio quel tipo di stato che resta acceso quando il gesto
        // finisce male.
        removeCallbacks(scorrimentoAlBordo)
        post(scorrimentoAlBordo)
    }

    private fun lanciaInerzia() {
        velocita?.computeCurrentVelocity(1000)
        val vy = velocita?.yVelocity ?: 0f
        if (abs(vy) <= ViewConfiguration.get(context).scaledMinimumFlingVelocity) return
        scroller.fling(0, scrollY, 0, -vy.toInt(), 0, 0, 0, scorrimentoMassimo())
        postInvalidateOnAnimation()
    }

    private fun chiudiGesto() {
        gesto = Gesto.NESSUNO
        gestoInCorso = false
        trascinando = false
        giaSelezionato = false
        velocita?.recycle()
        velocita = null
    }

    private fun offsetDelTocco(event: MotionEvent): Int {
        val posizione = getOffsetForPosition(event.x, event.y)
        return if (posizione < 0) text.length else posizione.coerceIn(0, text.length)
    }

    /**
     * La parola intorno a un punto, per la pressione lunga.
     *
     * "Parola" qui e' la sequenza di lettere e cifre: non serve la stessa
     * definizione che usa il correttore, serve quella che chi guarda si aspetta
     * di veder evidenziare. Su uno spazio o un segno di punteggiatura non
     * seleziona niente, e il gesto resta un tocco.
     */
    private fun parolaIntorno(posizione: Int): Pair<Int, Int> {
        val testo = text ?: return 0 to 0
        if (testo.isEmpty()) return 0 to 0
        // Toccando fra due caratteri conta quello a sinistra, come fa il
        // cursore: e' quello che il dito ha appena superato.
        val dentro = (if (posizione >= testo.length) testo.length - 1 else posizione)
            .coerceAtLeast(0)
        if (!testo[dentro].isLetterOrDigit()) return 0 to 0
        var inizio = dentro
        while (inizio > 0 && testo[inizio - 1].isLetterOrDigit()) inizio--
        var fine = dentro
        while (fine < testo.length && testo[fine].isLetterOrDigit()) fine++
        return inizio to fine
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blink)
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) restartBlink() else removeCallbacks(blink)
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        if (!isShown) return
        caretOn = true
        postDelayed(blink, BLINK_MILLIS)
    }

    /**
     * Porta in vista la riga in cui sta il cursore.
     *
     * Non l'ultima riga del testo: chi ha spostato il cursore all'indietro sta
     * guardando **li'**, e inseguire la fine del testo lo porterebbe via
     * proprio dal punto che gli interessa.
     */
    /**
     * L'offset che sta una riga sopra o sotto, alla stessa altezza orizzontale.
     *
     * Serve al cursore su/giu': la riga gestiva solo sinistra e destra, quindi
     * con tre righe piene ci si spostava di un carattere alla volta. Si tiene
     * la x della colonna corrente, come fa qualunque campo di testo — muoversi
     * in verticale non deve far saltare il cursore a inizio riga.
     */
    fun offsetDiRiga(offset: Int, delta: Int): Int {
        val l = layout ?: return offset
        val lunghezza = text?.length ?: 0
        val corrente = l.getLineForOffset(offset.coerceIn(0, lunghezza))
        val destinazione = corrente + delta
        if (destinazione < 0) return 0
        if (destinazione >= l.lineCount) return lunghezza
        val x = l.getPrimaryHorizontal(offset.coerceIn(0, lunghezza))
        return l.getOffsetForHorizontal(destinazione, x).coerceIn(0, lunghezza)
    }

    private fun scrollToCaret() = portaInVista(selectionEnd)

    /**
     * Posizione che deve restare in vista, in attesa di un layout su cui
     * calcolarla. `-1` quando non c'e' niente in sospeso.
     */
    private var daPortareInVista = -1

    /**
     * ## Perche' non in `post`
     *
     * `setText` **azzera lo scorrimento**. Rimetterlo a posto in un `post`
     * significa lasciare che venga disegnato un fotogramma con il testo a
     * scorrimento zero, e solo dopo rimediare: a ogni lettera battuta l'intero
     * blocco di testo saltava su e giu'.
     *
     * Misurato con un log in `onDraw`, riga di tre righe di testo, un tasto:
     *
     *     portaInVista(71) chiamata, scrollY ora=31
     *     draw scrollY=0            <- il fotogramma sbagliato
     *       -> scrollTo 31 (era 0)
     *     draw scrollY=31
     *
     * Quel salto di 31 pixel e' il tremolio. Qui si applica subito: dopo
     * `setText` il layout c'e' gia' — la larghezza e' fissa, quindi `TextView`
     * lo ricostruisce nello stesso giro invece di rimandare — e lo scorrimento
     * e' corretto **prima** che si disegni.
     *
     * Se il layout non c'e' ancora, o se qualcosa lo azzera piu' avanti nel
     * giro, la posizione resta memorizzata e la riapplica [onPreDraw]. E' il
     * motivo per cui si ricorda invece di perdersi.
     */
    private fun portaInVista(posizione: Int) {
        daPortareInVista = posizione.coerceAtLeast(0)
        applicaScorrimento()
    }

    /**
     * Rimette in vista [daPortareInVista], se ce n'e' bisogno.
     *
     * Non consuma la richiesta: e' un **invariante**, non un evento. `TextView`
     * azzera lo scorrimento piu' volte per giro — una per ogni passata di
     * misura, e dentro un `LinearLayout` con i pesi il figlio viene misurato
     * due volte — quindi una richiesta consumata alla prima occasione si
     * perderebbe alla successiva, e la riga resterebbe inchiodata in cima.
     */
    private fun applicaScorrimento() {
        val posizione = daPortareInVista
        if (posizione < 0) return
        val l = layout ?: return
        // In misura l'altezza definitiva non e' ancora assegnata.
        val altezza = if (height > 0) height else measuredHeight
        val visible = altezza - totalPaddingTop - totalPaddingBottom
        if (visible <= 0) return
        val line = l.getLineForOffset(posizione.coerceIn(0, text?.length ?: 0))
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        val y = when {
            bottom > scrollY + visible -> bottom - visible
            top < scrollY -> top
            else -> return
        }
        scrollTo(0, y.coerceAtLeast(0))
    }

    /**
     * L'ultima parola sullo scorrimento, e va detta qui.
     *
     * `TextView` si registra come `OnPreDrawListener` quando rifa' il layout, e
     * il suo `onPreDraw` chiama `bringTextIntoView`, che per una vista senza
     * fuoco significa `scrollTo(0, 0)`. Gira **dopo** misura e layout e
     * **subito prima** del disegno: qualunque correzione fatta prima — in
     * `onMeasure`, in `onLayout`, o al momento di `setText` — viene cancellata
     * da qui. Trovato leggendo lo stack di chi azzerava, non la documentazione:
     *
     *     at android.widget.TextView.bringTextIntoView(TextView.java:11381)
     *     at android.widget.TextView.onPreDraw(TextView.java:8477)
     *
     * E' anche il motivo per cui la versione originale rimediava in un `post`:
     * quello girava a giro finito, cioe' dopo `onPreDraw` — e dopo un
     * fotogramma gia' disegnato storto, che era il tremolio.
     */
    override fun onPreDraw(): Boolean {
        val esito = super.onPreDraw()
        applicaScorrimento()
        return esito
    }

    /**
     * ## Niente `scrollX`/`scrollY` qui dentro
     *
     * La tentazione e' sottrarli, visto che la riga scorre. E' sbagliato: la
     * tela arriva a `onDraw` **gia' traslata** di `-scrollX,-scrollY` — lo fa
     * `View` prima di chiamarci — quindi qui si disegna in coordinate del
     * contenuto, le stesse che restituisce `Layout.getLineTop`. Sottraendoli si
     * contava lo scorrimento due volte.
     *
     * Si vedeva solo **dalla terza riga in poi**, perche' prima la riga non
     * scorre e `scrollY` vale zero: il cursore si staccava dal testo e
     * tremolava a ogni tasto, e toccando per spostarlo finiva altrove rispetto
     * a dove compariva — l'offset del tocco lo calcola `getOffsetForPosition`,
     * che lo scorrimento lo conta giusto.
     *
     * `totalPaddingTop` e' quello giusto anche a testo corto: comprende gia' lo
     * scostamento del `gravity="center_vertical"`, che e' quanto `TextView`
     * sposta il testo quando non riempie la riga.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val left = totalPaddingLeft
        val top = totalPaddingTop

        if (selectionEnd > selectionStart) {
            drawSelection(canvas, left.toFloat(), top.toFloat())
        }
        if (!caretOn) return

        // Come nelle maniglie: gli estremi passano da `offsetValido` prima di
        // toccare `Layout`. Siamo dentro `onDraw`, e qui un'eccezione chiude la
        // tastiera.
        val fine = offsetValido(selectionEnd)
        val line = l.getLineForOffset(fine)
        val x = left + l.getPrimaryHorizontal(fine)
        canvas.drawRect(
            x,
            (top + l.getLineTop(line)).toFloat(),
            x + CARET_WIDTH_PX * resources.displayMetrics.density,
            (top + l.getLineBottom(line)).toFloat(),
            caretPaint,
        )
    }

    /**
     * Una selezione puo' attraversare piu' righe, e allora non e' un
     * rettangolo: si disegna riga per riga, con quelle in mezzo piene fino al
     * bordo del testo.
     */
    private fun drawSelection(canvas: Canvas, left: Float, top: Float) {
        val l = layout ?: return
        val inizio = offsetValido(selectionStart)
        val fine = offsetValido(selectionEnd)
        val firstLine = l.getLineForOffset(inizio)
        val lastLine = l.getLineForOffset(fine)
        for (line in firstLine..lastLine) {
            val startX = if (line == firstLine) l.getPrimaryHorizontal(inizio) else l.getLineLeft(line)
            val endX = if (line == lastLine) l.getPrimaryHorizontal(fine) else l.getLineRight(line)
            canvas.drawRect(
                left + startX,
                top + l.getLineTop(line),
                left + endX,
                top + l.getLineBottom(line),
                selectionPaint,
            )
        }
        disegnaManiglie(canvas, left, top)
    }

    private companion object {
        const val BLINK_MILLIS = 500L
        const val CARET_WIDTH_PX = 2f
        const val SELECTION_ALPHA = 0x40

        /** Raggio della pallina che si trascina per aggiustare la selezione. */
        const val RAGGIO_MANIGLIA_DP = 7f

        /** Quanto vicino al bordo deve stare il dito perche' si scorra. */
        const val MARGINE_BORDO_DP = 16f

        /** Un passo ogni tanto: piu' fitto scorrerebbe troppo in fretta. */
        const val INTERVALLO_BORDO_MS = 60L
    }
}
