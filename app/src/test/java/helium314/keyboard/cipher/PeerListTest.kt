// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il parser dell'elenco contatti, provato su input malformati.
 *
 * Il blob arriva dal nostro core, quindi non e' ostile — ma un file su disco
 * corrotto, o un disallineamento di versione dopo un aggiornamento, producono
 * gli stessi byte di un input ostile. E qui un'eccezione e' la schermata
 * contatti che non si apre piu': l'unico posto da cui si nominano, si
 * verificano e si dimenticano le persone.
 *
 * Niente Robolectric: questo file e' Kotlin puro su un `ByteArray`.
 */
class PeerListTest {

    private fun blob(
        versione: Byte = 4,
        conteggio: Int = 1,
        etichetta: String? = "Marco",
        chiave: Byte = 7,
    ): ByteArray {
        val nome = etichetta?.toByteArray() ?: ByteArray(0)
        val out = ArrayList<Byte>()
        out.add(versione)
        for (i in 0 until 4) out.add(((conteggio shr (8 * i)) and 0xFF).toByte())
        for (i in 0 until 32) out.add(chiave)
        for (i in 0 until 8) out.add(0)
        out.add(1) // verificato
        out.add((nome.size and 0xFF).toByte())
        out.add(((nome.size shr 8) and 0xFF).toByte())
        nome.forEach { out.add(it) }
        return out.toByteArray()
    }

    @Test
    fun unElencoValidoSiLegge() {
        val peers = PeerList.parse(blob())
        assertEquals(1, peers?.size)
        assertEquals("Marco", peers?.first()?.label)
        assertTrue(peers?.first()?.verified == true)
    }

    @Test
    fun unaVersioneSconosciutaSiRifiuta() {
        assertNull(PeerList.parse(blob(versione = 9)))
    }

    /** Troncato a meta' record: si rifiuta, non si legge oltre la fine. */
    @Test
    fun unBlobTroncatoSiRifiuta() {
        val intero = blob()
        for (taglio in intArrayOf(1, 6, 20, intero.size - 1)) {
            assertNull(
                PeerList.parse(intero.copyOfRange(0, taglio)),
                "un blob di $taglio byte doveva essere rifiutato",
            )
        }
    }

    /**
     * Un conteggio che come `Int` viene negativo: rifiutato prima di allocare.
     * Senza, sarebbe un `ArrayList` di dimensione assurda o un ciclo infinito.
     */
    @Test
    fun unConteggioNegativoSiRifiuta() {
        assertNull(PeerList.parse(blob(conteggio = -1)))
    }

    /**
     * Un conteggio enorme ma positivo non deve allocare: si ferma al primo
     * record che non ci sta.
     */
    @Test
    fun unConteggioEnormeNonAlloca() {
        assertNull(PeerList.parse(blob(conteggio = 1_000_000)))
    }

    /**
     * La lunghezza dell'etichetta si legge **senza segno**. Con la lettura
     * firmata un valore oltre 0x7FFF sarebbe negativo, avrebbe superato il
     * controllo sui limiti — che confronta `offset + len` con la dimensione — e
     * sarebbe finito in `String(bytes, offset, lunghezzaNegativa)`, cioe' in
     * un'eccezione.
     */
    @Test
    fun unaEtichettaLunghissimaSiRifiutaSenzaEccezioni() {
        val corpo = blob(etichetta = "")
        // Si riscrivono i due byte della lunghezza con 0xFFFF: dichiara
        // 65535 caratteri che nel blob non ci sono.
        corpo[corpo.size - 2] = 0xFF.toByte()
        corpo[corpo.size - 1] = 0xFF.toByte()
        assertNull(PeerList.parse(corpo))
    }
}
