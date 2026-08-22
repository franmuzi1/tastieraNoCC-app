package helium314.keyboard.cipher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
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

    private val pressioneLunga = Runnable {
        val (inizio, fine) = parolaIntorno(ancora)
        if (fine > inizio) {
            giaSelezionato = true
            parolaInizio = inizio
            parolaFine = fine
            onSelezione?.invoke(inizio, fine)
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
        if (text.isNullOrEmpty()) return super.onTouchEvent(event)
        val offset = offsetDelTocco(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                ancora = offset
                gestoInCorso = true
                trascinando = false
                giaSelezionato = false
                parolaInizio = -1
                parolaFine = -1
                xIniziale = event.x
                yIniziale = event.y
                postDelayed(pressioneLunga, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trascinando && !giaSelezionato) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(event.x - xIniziale) > slop || abs(event.y - yIniziale) > slop) {
                        trascinando = true
                        removeCallbacks(pressioneLunga)
                    }
                }
                when {
                    giaSelezionato -> {
                        onSelezione?.invoke(minOf(parolaInizio, offset), maxOf(parolaFine, offset))
                        portaInVista(offset)
                    }
                    trascinando -> {
                        onSelezione?.invoke(minOf(ancora, offset), maxOf(ancora, offset))
                        portaInVista(offset)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(pressioneLunga)
                // Un tocco secco che non ha ne' trascinato ne' selezionato una
                // parola vuol dire una cosa sola: il cursore va li'.
                if (!trascinando && !giaSelezionato) onSelezione?.invoke(offset, offset)
                gestoInCorso = false
                trascinando = false
                giaSelezionato = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pressioneLunga)
                gestoInCorso = false
                trascinando = false
                giaSelezionato = false
                return true
            }
        }
        return super.onTouchEvent(event)
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

        val line = l.getLineForOffset(selectionEnd)
        val x = left + l.getPrimaryHorizontal(selectionEnd)
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
        val firstLine = l.getLineForOffset(selectionStart)
        val lastLine = l.getLineForOffset(selectionEnd)
        for (line in firstLine..lastLine) {
            val startX = if (line == firstLine) l.getPrimaryHorizontal(selectionStart) else l.getLineLeft(line)
            val endX = if (line == lastLine) l.getPrimaryHorizontal(selectionEnd) else l.getLineRight(line)
            canvas.drawRect(
                left + startX,
                top + l.getLineTop(line),
                left + endX,
                top + l.getLineBottom(line),
                selectionPaint,
            )
        }
    }

    private companion object {
        const val BLINK_MILLIS = 500L
        const val CARET_WIDTH_PX = 2f
        const val SELECTION_ALPHA = 0x40
    }
}
