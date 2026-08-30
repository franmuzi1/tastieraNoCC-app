// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Il primo test del livello Android di questo fork, e non e' un caso che parta
 * da qui: la classificazione dei campi e' la cosa che ha gia' sbagliato due
 * volte in produzione.
 *
 * Prima allargando le esclusioni ai moduli, che ha spento la riga cifrata
 * **dentro Telegram** — cioe' proprio dove il progetto esiste. Poi leggendo il
 * bit «multiriga» fuori dalla sua classe.
 *
 * Questi campi arrivano dalle app di terzi e dichiarano quello che vogliono:
 * qui non si prova la crittografia, si prova un giudizio su un ingresso che non
 * controlliamo.
 *
 * **Senza Robolectric, di proposito.** `EditorInfo` e' una struttura di campi
 * pubblici senza logica, e `InputType` sono costanti compilate nel bytecode:
 * per questo test non serve un finto Android, e non chiederlo lo rende molto
 * piu' veloce e senza dipendenze da scaricare. Robolectric serve dove serve un
 * `Context`, non qui.
 */
class CipherFieldsTest {

    private fun campo(tipo: Int, azione: Int = EditorInfo.IME_ACTION_UNSPECIFIED) =
        EditorInfo().apply {
            inputType = tipo
            imeOptions = azione
        }

    private val testo = InputType.TYPE_CLASS_TEXT

    /**
     * Il caso Telegram. Un compositore di chat e' multiriga, e puo' dichiarare
     * qualunque azione sul tasto invio: la multiriga deve vincere.
     */
    @Test
    fun unCampoMultirigaComponeMessaggiQualunqueAzioneDichiari() {
        for (azione in intArrayOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        )) {
            val campo = campo(testo or InputType.TYPE_TEXT_FLAG_MULTI_LINE, azione)
            assertFalse(
                CipherFields.nonComponeMessaggi(campo),
                "la riga deve comparire su un campo multiriga (azione $azione)",
            )
        }
    }

    /** Anche con il completamento automatico dichiarato. */
    @Test
    fun laMultirigaVinceAncheSulCompletamentoAutomatico() {
        val campo = campo(
            testo or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE,
        )
        assertFalse(CipherFields.nonComponeMessaggi(campo))
    }

    /**
     * L'unica esclusione che nessun segnale scavalca. Se un giorno la multiriga
     * finisse prima di questo controllo, la riga cifrata comparirebbe su una
     * password mostrando a schermo cio' che il campo nasconde.
     */
    @Test
    fun unaPasswordRestaVietataAncheSeMultiriga() {
        val campo = campo(
            testo or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        assertTrue(CipherFields.vietata(campo))
        assertTrue(CipherFields.nonComponeMessaggi(campo))
    }

    /**
     * Il bit della multiriga vale solo dentro `TYPE_CLASS_TEXT`: fuori di li'
     * e' un bit qualunque di un'altra classe, e non deve accendere niente.
     */
    @Test
    fun ilBitMultirigaNonValeFuoriDalTesto() {
        val campo = campo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertTrue(CipherFields.nonComponeMessaggi(campo))
    }

    /** Le barre di ricerca restano fuori: a riga singola, azione «cerca». */
    @Test
    fun unaBarraDiRicercaNonComponeMessaggi() {
        assertTrue(CipherFields.nonComponeMessaggi(campo(testo, EditorInfo.IME_ACTION_SEARCH)))
    }

    /** Un campo di testo semplice, senza azioni: la riga ci va. */
    @Test
    fun unCampoDiTestoQualunqueComponeMessaggi() {
        assertFalse(CipherFields.nonComponeMessaggi(campo(testo)))
    }
}
