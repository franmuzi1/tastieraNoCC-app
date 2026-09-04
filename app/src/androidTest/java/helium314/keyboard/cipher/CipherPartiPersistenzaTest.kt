// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La coda delle parti che sopravvive alla morte della tastiera, provata **su un
 * dispositivo**.
 *
 * Perche' strumentato e non su JVM: la meta' che conta passa da Android
 * Keystore — che su JVM non esiste — e da `noBackupFilesDir`, cioe' un
 * filesystem vero. I test su JVM coprono il codice e il suo inverso; qui si
 * prova cio' che quei due estremi hanno in mezzo, ed e' esattamente il tratto
 * in cui un messaggio a meta' si perderebbe.
 *
 * Perche' proprio questo: la coda esiste per un difetto che aveva gia'
 * colpito. Consegnata la prima parte il chiaro sparisce dalla riga, e finche'
 * le altre vivevano solo in memoria bastava che Android chiudesse la tastiera
 * — cosa che fa, ed e' il motivo per cui esiste tutto il keep-alive — perche'
 * all'altro arrivasse mezzo messaggio e il resto non esistesse piu' da nessuna
 * parte.
 *
 * ATTENZIONE: se sul dispositivo non c'e' ancora un'identita', [Before] ne
 * crea una. Va eseguito su un emulatore di prova.
 */
@RunWith(AndroidJUnit4::class)
class CipherPartiPersistenzaTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** `180225` e' un campo di testo multiriga, come un compositore di chat. */
    private fun campo(pacchetto: String = "com.whatsapp", id: Int = 42) =
        EditorInfo().apply {
            packageName = pacchetto
            fieldId = id
            inputType = 180225
        }

    /**
     * Senza identita' non c'e' chiave maestra, e senza quella [CipherParti]
     * non scrive niente — di proposito: una coda esiste solo dopo che si e'
     * cifrato qualcosa, e cifrare richiede l'identita'.
     */
    @Before
    fun identitaProntaECodaVuota() {
        assertTrue("il core nativo non si e' caricato", CipherCore.available)
        assertEquals(CipherState.Ready, CipherIdentity.ensureReady(context))
        CipherParti.scarta()
        CipherParti.salva(context)
        assertFalse(CipherStorage.exists(context, CipherStorage.PARTS))
    }

    @After
    fun nonLasciareCode() {
        CipherParti.scarta()
        CipherParti.salva(context)
    }

    /**
     * Il caso per cui tutto questo esiste: la tastiera muore fra una parte e
     * l'altra, e al riavvio il resto del messaggio e' ancora li'.
     *
     * `scarta()` in mezzo e' la morte del processo: azzera lo stato in memoria
     * senza toccare il disco, che e' quello che succede davvero quando Android
     * chiude la tastiera.
     */
    @Test
    fun laCodaTornaDopoCheLaTastieraEMorta() {
        val c = campo()
        CipherParti.accoda(c, listOf("kc/seconda", "kc/terza"), 3, dallaRiga = true)
        CipherParti.consegnata("kc/prima")
        CipherParti.salva(context)
        assertTrue("la coda non e' arrivata su disco", CipherStorage.exists(context, CipherStorage.PARTS))

        CipherParti.scarta()
        assertFalse("lo stato in memoria doveva sparire", CipherParti.inAttesaSu(c))

        CipherParti.ripristina(context)
        assertTrue("la coda non e' tornata", CipherParti.inAttesaSu(c))
        assertEquals("kc/seconda", CipherParti.prossima(c))
        assertEquals(Pair(2, 3), CipherParti.prossimaEtichetta())
        assertTrue("si e' persa la memoria di come era uscita la prima parte", CipherParti.daRiga())
        assertEquals("kc/prima".length, CipherParti.ultimaLunghezza())
    }

    /** E si consegna solo dove era cominciata: un'altra chat non la prende. */
    @Test
    fun laCodaRipristinataRestaLegataAlSuoCampo() {
        CipherParti.accoda(campo(id = 42), listOf("kc/seconda"), 2, dallaRiga = false)
        CipherParti.salva(context)
        CipherParti.scarta()
        CipherParti.ripristina(context)

        assertFalse(CipherParti.inAttesaSu(campo(id = 99)))
        assertFalse(CipherParti.inAttesaSu(campo(pacchetto = "org.telegram.messenger")))
        assertTrue(CipherParti.inAttesaSu(campo(id = 42)))
    }

    /**
     * Finita la coda il file sparisce. Restare sarebbe due cose insieme: uno
     * spazio occupato per niente, e la traccia che a quell'ora stavi scrivendo
     * a qualcuno.
     */
    @Test
    fun ilFileSparisceQuandoLaCodaFinisce() {
        val c = campo()
        CipherParti.accoda(c, listOf("kc/seconda"), 2, dallaRiga = true)
        CipherParti.salva(context)
        assertTrue(CipherStorage.exists(context, CipherStorage.PARTS))

        CipherParti.consuma()
        CipherParti.salva(context)
        assertFalse("il file e' rimasto a coda finita", CipherStorage.exists(context, CipherStorage.PARTS))
    }

    /**
     * I blob sono gia' cifrati per il destinatario, ma sul disco non ci vanno
     * in chiaro lo stesso: un file leggibile direbbe a chi guarda il telefono
     * che stavi scrivendo a qualcuno, e quando. Qui si verifica che il giro di
     * Keystore ci sia davvero, e non che sia "previsto".
     */
    @Test
    fun suDiscoIlBlobNonSiLegge() {
        val riconoscibile = "kc/questaStringaNonDeveComparireSulDisco"
        CipherParti.accoda(campo(), listOf(riconoscibile), 2, dallaRiga = true)
        CipherParti.salva(context)

        val bytes = CipherStorage.read(context, CipherStorage.PARTS)
        assertNotNull("il file non c'e'", bytes)
        val comeTesto = String(bytes!!, Charsets.ISO_8859_1)
        assertFalse(
            "il blob si legge in chiaro sul disco: il giro di Keystore non c'e' stato",
            comeTesto.contains(riconoscibile),
        )
        // E il pacchetto della chat nemmeno, che e' il metadato piu' parlante.
        assertFalse(comeTesto.contains("com.whatsapp"))
    }

    /**
     * Un file che non si apre non diventa una coda a caso e non resta li' a
     * farsi ritrovare al prossimo avvio.
     */
    @Test
    fun unFileIlleggibileNonProduceUnaCodaEVieneButtato() {
        assertTrue(
            CipherStorage.write(context, CipherStorage.PARTS, ByteArray(64) { it.toByte() }),
        )
        CipherParti.ripristina(context)
        assertFalse(CipherParti.inAttesaSu(campo()))
        assertNull(CipherParti.prossima(campo()))
        assertFalse(
            "un file illeggibile e' rimasto su disco",
            CipherStorage.exists(context, CipherStorage.PARTS),
        )
    }

    /** Nessun file, nessuna coda: e' il primo avvio, non un guasto. */
    @Test
    fun senzaFileNonSuccedeNiente() {
        CipherParti.ripristina(context)
        assertFalse(CipherParti.inAttesaSu(campo()))
    }
}
