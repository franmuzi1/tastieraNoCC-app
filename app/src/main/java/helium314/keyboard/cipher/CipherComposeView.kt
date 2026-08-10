package helium314.keyboard.cipher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView

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
 */
class CipherComposeView(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {

    private val caretPaint = Paint().apply { isAntiAlias = false }
    private val selectionPaint = Paint().apply { isAntiAlias = false }

    private var selectionStart = 0
    private var selectionEnd = 0

    private var caretOn = true

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
    private fun scrollToCaret() {
        post {
            val l = layout ?: return@post
            val line = l.getLineForOffset(selectionEnd)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val left = totalPaddingLeft - scrollX
        val top = totalPaddingTop - scrollY

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
