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
        scrollToCaret()
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
                trascinando = false
                giaSelezionato = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(pressioneLunga)
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

    private fun portaInVista(posizione: Int) {
        post {
            val l = layout ?: return@post
            val line = l.getLineForOffset(posizione.coerceIn(0, text.length))
            val visible = height - totalPaddingTop - totalPaddingBottom
            if (visible <= 0) return@post
            val top = l.getLineTop(line)
            val bottom = l.getLineBottom(line)
            val y = when {
                bottom > scrollY + visible -> bottom - visible
                top < scrollY -> top
                else -> return@post
            }
            scrollTo(0, y.coerceAtLeast(0))
        }
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
