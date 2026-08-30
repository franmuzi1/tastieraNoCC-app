// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Il marcatore che tiene il testo decifrato fuori dalla cronologia degli
 * appunti — che e' un archivio **persistito su disco** dalla tastiera — e fuori
 * dal suggerimento «incolla».
 *
 * Vale su tutte le versioni di Android, a differenza di `EXTRA_IS_SENSITIVE`
 * che esiste dalla 33: e' l'unica difesa disponibile sotto quella soglia, ed e'
 * il motivo per cui va provata.
 *
 * Kotlin puro: e' un confronto di digest.
 */
class CipherClipboardTest {

    @Test
    fun ilTestoMarcatoVieneRiconosciuto() {
        val chiaro = "ci vediamo alle otto"
        CipherClipboard.markSensitive(chiaro)
        assertTrue(CipherClipboard.isSensitive(chiaro))
    }

    /** Un testo qualunque non e' riservato: la cronologia deve tenerselo. */
    @Test
    fun unAltroTestoNonVieneRiconosciuto() {
        CipherClipboard.markSensitive("ci vediamo alle otto")
        assertFalse(CipherClipboard.isSensitive("un indirizzo copiato da una mappa"))
    }

    /**
     * Il confronto e' esatto: una differenza di un carattere non deve passare
     * per lo stesso testo. Sarebbe un falso positivo — innocuo qui, ma direbbe
     * che il confronto non e' quello che crediamo.
     */
    @Test
    fun unaDifferenzaDiUnCarattereNonPassa() {
        CipherClipboard.markSensitive("segreto")
        assertFalse(CipherClipboard.isSensitive("segretO"))
    }

    /**
     * Marcare due volte tiene solo l'ultimo, ed e' voluto: c'e' una sola
     * copia negli appunti alla volta. Il test lo fissa perche' e' un limite
     * del disegno, non un caso accidentale.
     */
    @Test
    fun soloLUltimoMarcatoResta() {
        CipherClipboard.markSensitive("primo")
        CipherClipboard.markSensitive("secondo")
        assertTrue(CipherClipboard.isSensitive("secondo"))
        assertFalse(CipherClipboard.isSensitive("primo"))
    }
}
