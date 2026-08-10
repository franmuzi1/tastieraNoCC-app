package helium314.keyboard.cipher

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Genera il QR della propria identity card.
 *
 * ## A cosa serve davvero
 *
 * Il TOFU accetta un rischio preciso: al **primo** contatto non c'e' modo di
 * sapere se la chiave che arriva sia davvero di chi dice di essere. Da quel
 * momento in poi il pin protegge, ma quel primo scambio resta scoperto.
 * Guardarsi in faccia e inquadrare un codice e' l'unica cosa che lo chiude, ed
 * e' il motivo per cui questo va reso facile da raggiungere invece che sepolto
 * in un sottomenu.
 *
 * ## Mostrare si', scansionare no
 *
 * Qui c'e' solo la generazione. Leggere un QR richiede `CAMERA`, e il fork non
 * ha permessi: non averne e' la sua proprieta' principale, e non si spende un
 * permesso di dispositivo per una comodita'. Il flusso che funziona senza:
 * l'altra persona inquadra con un lettore QR qualunque, ottiene il testo
 * `kc/...`, e lo condivide alla nostra Activity dallo share sheet. Se un
 * giorno si aggiunge lo scanner, `CAMERA` va chiesto **a runtime**,
 * all'apertura dello scanner, mai come permesso di installazione.
 *
 * ## Cosa contiene
 *
 * Esattamente la stessa stringa che il tasto "cifra" inserisce nel campo: la
 * identity card, cioe' una chiave **pubblica** con checksum e riempimento. Non
 * c'e' nessun segreto in questo codice; a proteggerlo dagli screenshot ci
 * pensa comunque `FLAG_SECURE` sull'Activity che lo mostra.
 */
internal object CipherQr {

    /**
     * Livello M: circa il 15% di ridondanza.
     *
     * Non il massimo di proposito. Alzare la correzione d'errore aumenta il
     * numero di moduli a parita' di dati, quindi rimpicciolisce ogni modulo
     * sullo schermo e rende la lettura *piu'* difficile, non meno — e qui il
     * codice si legge da vicino, su un display pulito, non stampato su una
     * scatola rovinata.
     */
    private val CORRECTION = ErrorCorrectionLevel.M

    /**
     * Margine in moduli. Lo standard ne chiede 4; 2 basta su schermo e lascia
     * piu' spazio ai moduli veri. Sotto questo valore certi lettori non
     * agganciano piu' il codice.
     */
    private const val MARGIN = 2

    /**
     * Costruisce il bitmap. `null` se la stringa non ci sta o se qualcosa va
     * storto: chi chiama mostra la stringa testuale, che resta comunque
     * leggibile ad alta voce.
     */
    fun encode(content: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to CORRECTION,
            EncodeHintType.MARGIN to MARGIN,
            // L'alfabeto e' ASCII (z-base-32 minuscolo piu' "kc/"), quindi
            // ISO-8859-1 evita che ZXing dichiari un ECI UTF-8 nel codice:
            // byte in piu' per niente.
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        // Sempre bianco e nero pieni, mai i colori del tema: un QR a basso
        // contrasto, o in tema scuro con i moduli chiari, e' un QR che i
        // lettori sbagliano.
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()
}
