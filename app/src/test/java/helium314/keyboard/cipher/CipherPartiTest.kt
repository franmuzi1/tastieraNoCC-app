// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il taglio di un messaggio troppo lungo.
 *
 * Qui si prova la sola cosa che [CipherParti.dividi] promette: che ogni parte
 * stia nel tetto, contrassegno compreso, e che rimettendole insieme torni fuori
 * esattamente il testo di partenza. La seconda meta' e' quella che conta —
 * spezzare un messaggio e perderne un pezzo per strada sarebbe peggio del
 * rifiuto che questa funzione sostituisce.
 *
 * **Senza Robolectric**, come [CipherFieldsTest]: `dividi` non tocca Android.
 */
class CipherPartiTest {

    /** Un tetto finto in byte, al posto della stima del blob. */
    private fun tetto(byte: Int): (String) -> Boolean = { it.toByteArray().size <= byte }

    private fun senzaContrassegni(parti: List<String>) =
        parti.joinToString("") { it.substringAfter(") ") }

    @Test
    fun ogniParteStaNelTetto() {
        val testo = "parola ".repeat(200)
        val ciSta = tetto(300)
        val parti = assertNotNull(CipherParti.dividi(testo, ciSta))
        assertTrue(parti.size > 1, "un testo cosi' deve venire spezzato")
        for (parte in parti) {
            assertTrue(ciSta(parte), "questa parte sfora il tetto: ${parte.length} caratteri")
        }
    }

    /**
     * La proprieta' per cui esiste tutto il resto: non si perde niente.
     */
    @Test
    fun lePartiRimesseInsiemeDannoIlTestoDiPartenza() {
        val testo = "Questo e' un messaggio lungo, con virgole, a capo\ne spazi. " .repeat(30)
        val parti = assertNotNull(CipherParti.dividi(testo, tetto(300)))
        assertTrue(parti.size > 1, "un testo cosi' deve venire spezzato")
        assertEquals(testo, senzaContrassegni(parti))
    }

    /** Il contrassegno dice quale parte e quante sono, e comincia da uno. */
    @Test
    fun ilContrassegnoNumeraLeParti() {
        val parti = assertNotNull(CipherParti.dividi("x".repeat(500), tetto(100)))
        val quante = parti.size
        parti.forEachIndexed { i, parte ->
            assertTrue(
                parte.startsWith("(${i + 1}/$quante) "),
                "contrassegno sbagliato: ${parte.take(10)}",
            )
        }
    }

    /**
     * Le emoji sono coppie surrogate: tagliare in mezzo produce due meta' di
     * carattere, e la seconda apre la parte successiva con un simbolo che non
     * esiste. Il testo ricomposto lo mostrerebbe comunque intero, quindi la
     * prova e' sulle singole parti.
     */
    @Test
    fun nonSiTagliaInMezzoAUnaCoppiaSurrogata() {
        val testo = "🔐".repeat(300)
        val parti = assertNotNull(CipherParti.dividi(testo, tetto(200)))
        assertTrue(parti.size > 1)
        for (parte in parti) {
            assertTrue(!parte.last().isHighSurrogate(), "parte finita a meta' di un'emoji")
            assertTrue(!parte.first().isLowSurrogate(), "parte cominciata a meta' di un'emoji")
        }
        assertEquals(testo, senzaContrassegni(parti))
    }

    /** Dove c'e' uno spazio vicino al taglio, si taglia li'. */
    @Test
    fun ilTaglioPreferisceLoStaccoDiParola() {
        val testo = ("parolalunga ").repeat(40)
        val parti = assertNotNull(CipherParti.dividi(testo, tetto(100)))
        // La prima parte finisce con uno spazio, non a meta' di "parolalunga".
        assertTrue(parti.first().endsWith(" "), "ha tagliato dentro una parola: ${parti.first()}")
    }

    /**
     * Oltre il tetto delle parti si torna al rifiuto: spezzare in venti
     * messaggi non e' un servizio a nessuno dei due.
     */
    @Test
    fun oltreIlMassimoDellePartiNonSiSpezza() {
        val testo = "x".repeat(100_000)
        assertNull(CipherParti.dividi(testo, tetto(100)))
    }

    /** Un testo che ci sta gia' non viene contrassegnato. */
    @Test
    fun unTestoCheCiStaTornaIntatto() {
        val parti = assertNotNull(CipherParti.dividi("corto", tetto(100)))
        assertEquals(listOf("corto"), parti)
    }

    @Test
    fun unTestoVuotoNonSiSpezza() {
        assertNull(CipherParti.dividi("", tetto(100)))
    }
}
