// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.cipher

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La regola che decide se la riga cifrata puo' comparire su un campo, messa
 * insieme all'interruttore generale.
 *
 * Serve un `Context` — le impostazioni stanno nelle preferenze — quindi qui
 * Robolectric serve davvero, a differenza di [CipherFieldsTest].
 *
 * Le due meta' vanno provate insieme perche' un difetto segnalato dall'utente
 * stava proprio nella loro combinazione: con la cifratura spenta dalle
 * impostazioni la riga non deve comparire **su nessun campo**, nemmeno su
 * quelli che la classificazione considera adatti.
 */
@RunWith(RobolectricTestRunner::class)
class CipherRigaPrevistaTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cifratura(accesa: Boolean) {
        context.prefs().edit().putBoolean(CipherSettings.PREF_ENABLED, accesa).apply()
    }

    private val campoDaMessaggi = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private val barraDiRicerca = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
    }

    @Test
    fun conLaCifraturaAccesaLaRigaEPrevistaSuUnCampoDaMessaggi() {
        cifratura(true)
        assertTrue(CipherFields.rigaPrevistaSu(context, campoDaMessaggi))
    }

    /**
     * Il caso segnalato: spenta vuol dire spenta, anche dove il campo sarebbe
     * adattissimo. Se questa cade, la riga ricompare in chat a chi l'ha
     * disattivata.
     */
    @Test
    fun conLaCifraturaSpentaLaRigaNonEMaiPrevista() {
        cifratura(false)
        assertFalse(CipherFields.rigaPrevistaSu(context, campoDaMessaggi))
        assertFalse(CipherFields.rigaPrevistaSu(context, barraDiRicerca))
    }

    /** Una barra di ricerca resta esclusa anche a cifratura accesa. */
    @Test
    fun unaBarraDiRicercaRestaEsclusaAncheAccesa() {
        cifratura(true)
        assertFalse(CipherFields.rigaPrevistaSu(context, barraDiRicerca))
    }

    /** Nessun campo, nessuna riga: non si indovina su un fuoco che non c'e'. */
    @Test
    fun senzaCampoNonSiPrevedeNiente() {
        cifratura(true)
        assertFalse(CipherFields.rigaPrevistaSu(context, null))
    }
}
