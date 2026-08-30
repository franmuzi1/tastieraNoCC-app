// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il giro completo del backup, provato **su un dispositivo**.
 *
 * Perche' strumentato e non su JVM: qui si passa dal core nativo — il `.so` e'
 * un binario Android e sulla JVM non si carica — e da Android Keystore, che su
 * JVM non esiste. Sono le due cose che i test con Robolectric non possono
 * toccare, e sono esattamente quelle da cui dipende il backup.
 *
 * Perche' proprio questo: il backup e' **l'unica via** per portare un'identita'
 * da un'installazione all'altra, e quindi e' la premessa del passaggio alla
 * chiave di firma vera — che obbligherebbe tutti a disinstallare e
 * reinstallare. Una settimana fa l'importazione non poteva riuscire mai, e a
 * scoprirlo e' stato un utente: la rete di sicurezza era rotta e nessuno lo
 * sapeva.
 *
 * ATTENZIONE: questo test **sostituisce l'identita'** del dispositivo su cui
 * gira. Va eseguito solo su un emulatore di prova.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun identitaPronta(): String {
        assertTrue("il core nativo non si e' caricato", CipherCore.available)
        assertEquals(CipherState.Ready, CipherIdentity.ensureReady(context))
        val impronta = CipherCore.nativeMyFingerprint()
        assertNotNull("nessuna impronta: identita' non pronta", impronta)
        return impronta!!
    }

    /**
     * Esporta, butta via tutto, reimporta: l'identita' deve essere la stessa.
     *
     * L'impronta e' il modo giusto di verificarlo — e' cio' che due persone si
     * confrontano a voce — e non la chiave grezza, che da qui non deve nemmeno
     * uscire.
     */
    @Test
    fun esportaEReimportaMantieneLIdentita() {
        val prima = identitaPronta()
        val passphrase = "una passphrase lunga abbastanza".toByteArray()

        val blob = CipherIdentity.exportBackup(passphrase.copyOf())
        assertNotNull("l'esportazione non ha prodotto niente", blob)

        // Si distrugge tutto, come farebbe una disinstallazione.
        CipherIdentity.resetIdentity(context)
        val dopoIlReset = CipherCore.nativeMyFingerprint()
        assertNotEquals("il reset non ha cambiato identita'", prima, dopoIlReset)

        val esito = CipherIdentity.importBackup(context, blob!!, passphrase.copyOf())
        assertEquals(CipherState.Ready, esito)
        assertEquals("l'identita' non e' tornata quella di prima", prima, CipherCore.nativeMyFingerprint())
    }

    /** Una passphrase sbagliata non apre, e non deve lasciare mezzo stato. */
    @Test
    fun unaPassphraseSbagliataNonApre() {
        identitaPronta()
        val blob = CipherIdentity.exportBackup("giusta".toByteArray())
        assertNotNull(blob)
        val prima = CipherCore.nativeMyFingerprint()
        val esito = CipherIdentity.importBackup(context, blob!!, "sbagliata".toByteArray())
        assertNotEquals(CipherState.Ready, esito)
        assertEquals("un import fallito ha comunque toccato l'identita'", prima, CipherCore.nativeMyFingerprint())
    }

    /**
     * La passphrase vuota non produce un backup: Argon2 la digerirebbe senza
     * lamentarsi, e ne uscirebbe un file **che sembra cifrato** e che chiunque
     * lo trovi apre premendo invio. La regola sta nel core; qui si verifica che
     * arrivi fino all'app.
     */
    @Test
    fun unaPassphraseVuotaNonProduceBackup() {
        identitaPronta()
        assertNull(CipherIdentity.exportBackup(ByteArray(0)))
    }
}
