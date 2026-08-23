package helium314.keyboard.cipher

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * In quali campi la riga di composizione si accende da sola.
 *
 * ## Il difetto da cui nasce
 *
 * Prima la riga si accendeva **ovunque**, con l'unica eccezione delle password.
 * Per la tastiera una barra di ricerca era indistinguibile da una chat: il testo
 * finiva nel nostro buffer, il campo dell'app restava vuoto, e il tasto
 * "cerca" veniva ingoiato da [CipherCompose] — che lo ingoia apposta, per non
 * consegnare all'app il comando di spedire mentre il chiaro non e' ancora
 * cifrato. Le due decisioni sono giuste dentro una chat; fuori, la prima ruba il
 * testo e la seconda blocca l'unica strada per restituirlo.
 *
 * Il risultato per chi scrive: la ricerca parte vuota e il testo resta
 * prigioniero della riga.
 *
 * ## Perche' non si risolve consegnando il testo e poi eseguendo l'azione
 *
 * E' la soluzione ovvia ed e' pericolosa: su una chat che dichiara "invia",
 * quel percorso consegnerebbe il **chiaro** e premerebbe invio. Si aggiusterebbe
 * la ricerca rompendo la cifratura. Il posto giusto per decidere e' prima: non
 * accendere la riga dove non si compone un messaggio.
 *
 * ## Il criterio
 *
 * Si parte dal presupposto che un campo sia un compositore di messaggi, e si
 * spegne su prove contrarie. E' l'ordine giusto per due motivi: le app di chat
 * dichiarano il minimo indispensabile — spesso solo "testo" — quindi una lista
 * di campi AMMESSI le lascerebbe fuori; e un falso negativo (riga spenta in una
 * chat) toglie una funzione, mentre un falso positivo (riga accesa su una
 * ricerca) rompe il campo.
 *
 * Le prove contrarie, e cosa hanno in comune: sono tutte campi a **uso unico**,
 * dove il testo e' un parametro e non un discorso.
 */
internal object CipherFields {

    /**
     * La riga di composizione si accendera' su questo campo.
     *
     * Serve a chi deve decidere PRIMA che la riga si sia accesa, e quindi non
     * puo' guardare lo stato di [CipherCompose]: si ricava dalle stesse due
     * cose da cui dipende la riga — la preferenza e il tipo di campo — invece
     * di leggere uno stato che potrebbe non essere ancora aggiornato. Un
     * predicato che dipende dall'ordine delle chiamate e' un predicato che un
     * giorno risponde male senza che nessuno abbia cambiato niente.
     */
    fun rigaPrevistaSu(context: Context, editorInfo: EditorInfo?): Boolean =
        CipherSettings.isEnabled(context) &&
            editorInfo != null &&
            !nonComponeMessaggi(editorInfo)

    /**
     * `true` se la riga di composizione NON deve accendersi su questo campo.
     *
     * Il parametro e' NON nullo apposta. Un [EditorInfo] nullo non significa
     * "campo che non compone messaggi", significa "non si sa ancora niente", e
     * chi chiama lo tratta gia' come tale: sospendere li' svuoterebbe il buffer
     * a ogni passaggio a vuoto del ciclo di vita, cioe' butterebbe via il
     * messaggio in corso. Accettare un nullo qui renderebbe quell'errore
     * possibile senza che si veda.
     */
    fun nonComponeMessaggi(editorInfo: EditorInfo): Boolean {
        val inputType = editorInfo.inputType

        // Le password hanno la loro ragione, che viene prima di questa: la riga
        // mostrerebbe a schermo cio' che il campo nasconde con i pallini.
        if (InputTypeUtils.isPasswordInputType(inputType) ||
            InputTypeUtils.isVisiblePasswordInputType(inputType)
        ) {
            return true
        }

        // Numeri, telefono, date. Non sono discorsi, e cifrarli non ha senso —
        // ma soprattutto sono campi che l'app legge per farci qualcosa, non per
        // spedirli. Nota: la classe si estrae con TYPE_MASK_CLASS, non
        // confrontando l'intero, che porta i flag e le varianti.
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            -> return true
        }

        // Varianti del testo che dicono a cosa serve il campo. `FILTER` e' la
        // variante delle barre di ricerca e dei filtri di lista, ed e' il caso
        // che ha fatto scoprire il difetto.
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            -> return true
        }

        // L'azione dichiarata sul tasto invio. "Cerca" e "vai" sono di campi a
        // uso unico; "invia" NON e' fra queste, ed e' proprio quella delle
        // chat.
        //
        // Si guarda l'azione grezza e non `getImeOptionsActionIdFromEditorInfo`,
        // che traduce l'etichetta personalizzata in un valore sentinella: qui
        // serve sapere cosa ha dichiarato l'app, non cosa la tastiera ne fa.
        when (editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            -> return true
        }

        return false
    }
}
