// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.view.inputmethod.EditorInfo
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
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

    // --- La coda ---------------------------------------------------------

    /**
     * [CipherParti] e' un oggetto solo per tutto il processo: la coda di un
     * test arriverebbe nel successivo. Si butta prima di ognuno.
     */
    @BeforeTest
    fun codaPulita() {
        CipherParti.scarta()
    }

    private fun campo(pacchetto: String = "com.whatsapp", id: Int = 7) =
        EditorInfo().apply {
            packageName = pacchetto
            fieldId = id
            inputType = 180225
        }

    /**
     * **Il difetto che questo test blocca.** Prima si toglieva la parte dalla
     * coda e poi si provava a consegnarla: un `commitText` fallito la lasciava
     * fuori dalla coda e mai arrivata, e il codice reagiva buttando anche il
     * resto del messaggio. Guardare non deve consumare.
     */
    @Test
    fun guardareLaProssimaParteNonLaToglieDallaCoda() {
        val c = campo()
        CipherParti.accoda(c, listOf("due", "tre"), 3, dallaRiga = true)
        assertEquals("due", CipherParti.prossima(c))
        assertEquals("due", CipherParti.prossima(c), "guardare due volte da' la stessa parte")
        assertEquals(Pair(2, 3), CipherParti.prossimaEtichetta())
        assertTrue(CipherParti.inAttesaSu(c))
    }

    /** Si avanza solo dopo che il campo ha preso la parte. */
    @Test
    fun laCodaAvanzaSoloConConsuma() {
        val c = campo()
        CipherParti.accoda(c, listOf("due", "tre"), 3, dallaRiga = true)
        CipherParti.consuma()
        assertEquals("tre", CipherParti.prossima(c))
        assertEquals(Pair(3, 3), CipherParti.prossimaEtichetta())
        CipherParti.consuma()
        assertFalse(CipherParti.inAttesaSu(c), "finita la coda, non resta niente")
        assertNull(CipherParti.prossima(c))
    }

    /**
     * Dentro WhatsApp ci sono tutte le conversazioni: una parte consegnata
     * dopo aver cambiato chat andrebbe a un'altra persona.
     */
    @Test
    fun laCodaNonSiConsegnaSuUnAltroCampo() {
        CipherParti.accoda(campo(id = 7), listOf("due"), 2, dallaRiga = true)
        val altraChat = campo(id = 9)
        assertFalse(CipherParti.inAttesaSu(altraChat))
        assertNull(CipherParti.prossima(altraChat))
        // E nemmeno in un'altra app.
        assertFalse(CipherParti.inAttesaSu(campo(pacchetto = "org.telegram.messenger")))
    }

    /**
     * Senza identita' del campo una coda non si puo' tenere, e va saputo
     * **prima** di spezzare: dirlo dopo significherebbe aver gia' annunciato
     * «parte 1 di 3» per non consegnare mai le altre due.
     */
    @Test
    fun senzaIdentitaDelCampoNonSiTieneUnaCoda() {
        assertFalse(CipherParti.campoUtilizzabile(null))
        assertFalse(CipherParti.campoUtilizzabile(EditorInfo()))
        assertTrue(CipherParti.campoUtilizzabile(campo()))
        CipherParti.accoda(null, listOf("due"), 2, dallaRiga = true)
        assertFalse(CipherParti.inAttesaSu(campo()))
    }

    /**
     * La lunghezza dell'ultima parte consegnata e' il segno che distingue un
     * campo svuotato **da un invio** da uno svuotato a mano. Senza, la parte
     * successiva ricomparirebbe addosso a chi ha appena cancellato quella
     * prima per non mandarla.
     */
    @Test
    fun laCodaRicordaQuantoEraLungaLUltimaParteConsegnata() {
        val c = campo()
        CipherParti.accoda(c, listOf("due"), 2, dallaRiga = true)
        assertEquals(0, CipherParti.ultimaLunghezza(), "prima di consegnare non c'e' niente da confrontare")
        CipherParti.consegnata("kc/unblobqualunque")
        assertEquals("kc/unblobqualunque".length, CipherParti.ultimaLunghezza())
        CipherParti.scarta()
        assertEquals(0, CipherParti.ultimaLunghezza())
    }

    /**
     * Da dove e' uscita la prima parte decide come partono le altre: in
     * modalita' campo il fork non spedisce mai da solo.
     */
    @Test
    fun laCodaRicordaSeLaPrimaParteVenivaDallaRiga() {
        val c = campo()
        CipherParti.accoda(c, listOf("due"), 2, dallaRiga = false)
        assertFalse(CipherParti.daRiga())
        CipherParti.accoda(c, listOf("due"), 2, dallaRiga = true)
        assertTrue(CipherParti.daRiga())
        CipherParti.scarta()
        assertFalse(CipherParti.daRiga(), "buttata la coda, non resta nemmeno quello")
    }

    /**
     * Il giro completo del salvataggio, senza Keystore: quello che si scrive
     * su disco deve tornare indietro identico. E' il punto dove una coda si
     * corromperebbe in silenzio, e il silenzio qui vuol dire mezzo messaggio
     * che non parte piu'.
     */
    @Test
    fun laCodaScrittaSuDiscoTornaIndietroUguale() {
        val c = campo(pacchetto = "com.whatsapp", id = 42)
        CipherParti.accoda(c, listOf("kc/secondo", "kc/terzo"), 3, dallaRiga = true)
        CipherParti.consegnata("kc/primo")
        val bytes = CipherParti.codifica(Triple("com.whatsapp", 42, 180225))
        CipherParti.scarta()
        assertFalse(CipherParti.inAttesaSu(c))

        assertTrue(CipherParti.decodifica(bytes))
        assertTrue(CipherParti.inAttesaSu(c))
        assertEquals("kc/secondo", CipherParti.prossima(c))
        assertEquals(Pair(2, 3), CipherParti.prossimaEtichetta())
        assertTrue(CipherParti.daRiga())
        assertEquals("kc/primo".length, CipherParti.ultimaLunghezza())
        CipherParti.consuma()
        assertEquals("kc/terzo", CipherParti.prossima(c))
    }

    /** Un file corrotto non diventa una coda a caso: si rifiuta e basta. */
    @Test
    fun unFileCorrottoNonProduceUnaCoda() {
        val c = campo()
        CipherParti.accoda(c, listOf("kc/due"), 2, dallaRiga = false)
        val bytes = CipherParti.codifica(Triple("com.whatsapp", 7, 180225))
        CipherParti.scarta()
        // Versione sbagliata.
        val versione = bytes.copyOf().also { it[0] = 9 }
        assertFalse(CipherParti.decodifica(versione))
        // Troncato a meta'.
        assertFalse(runCatching { CipherParti.decodifica(bytes.copyOf(bytes.size / 2)) }.getOrDefault(false))
        assertFalse(CipherParti.inAttesaSu(c))
    }
}
