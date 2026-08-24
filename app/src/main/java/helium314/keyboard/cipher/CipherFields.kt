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
 * Le prove contrarie sono state allargate quando l'uso ha mostrato che la riga
 * restava accesa nei moduli — biglietti, registrazioni, ricerche interne alle
 * app — che dichiarano "avanti", "indietro" o "fatto" invece di "cerca".
 * Allargare e' diventato piu' sicuro da quando il tasto in toolbar puo'
 * **forzare** la riga: un falso negativo si ripara con un tocco, un falso
 * positivo no.
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
     * `true` se la riga e' **vietata** su questo campo, non solo non prevista.
     *
     * E' l'unica esclusione che l'utente non puo' scavalcare, e la differenza
     * con [nonComponeMessaggi] e' tutta qui. Una barra di ricerca non e' un
     * posto *previsto* per comporre messaggi, ma se qualcuno ci vuole la riga
     * sono affari suoi. Un campo password e' un'altra cosa: la riga mostrerebbe
     * a schermo cio' che il campo nasconde con i pallini, e lo terrebbe in un
     * buffer nostro. Non e' una preferenza, e' il motivo per cui la riga li'
     * non esiste.
     */
    fun vietata(editorInfo: EditorInfo): Boolean =
        InputTypeUtils.isPasswordInputType(editorInfo.inputType) ||
            InputTypeUtils.isVisiblePasswordInputType(editorInfo.inputType)

    /**
     * `true` se la riga di composizione non si accende **da sola** su questo
     * campo. Scavalcabile a mano, tranne dove [vietata] dice di no.
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

        // Le password vengono prima, e sono l'unico caso non scavalcabile.
        if (vietata(editorInfo)) return true

        // ## Multiriga vuol dire prosa, e la prosa e' un messaggio
        //
        // Questa prova viene PRIMA di tutte le altre e le annulla, ed e' una
        // correzione: allargando le esclusioni ai moduli — "avanti", "fatto",
        // completamento automatico — si e' preso dentro anche Telegram, dove la
        // riga ha smesso di comparire. Cioe' proprio il posto per cui esiste.
        //
        // Un campo che accetta gli a capo e' fatto per un testo che si scrive,
        // non per un parametro che si compila: le barre di ricerca, i codici e
        // le caselle dei moduli sono a riga singola per costruzione, perche' li'
        // l'invio serve a confermare. Percio' e' un segnale piu' forte di
        // qualunque azione dichiarata sul tasto invio, e la vince.
        //
        // Il prezzo: una casella "note" multiriga dentro un modulo avra' la
        // riga cifrata senza che serva. E' un fastidio, mentre una chat senza
        // riga e' la funzione che non c'e' — e la bilancia, qui, pende da
        // quella parte.
        if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false

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
            // "Avanti" e "indietro" sono navigazione fra i campi di un modulo:
            // un compositore di messaggi non ce li ha mai, perche' non c'e' un
            // campo dopo. Sono il segnale piu' pulito che esista qui —
            // biglietti, registrazioni, indirizzi di spedizione.
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            // "Fatto" chiude la scrittura, non la spedisce. Le chat usano
            // "invia" oppure nessuna azione (sono multiriga): "fatto" e' dei
            // campi che si compilano.
            EditorInfo.IME_ACTION_DONE,
            -> return true
        }

        // Completamento automatico: e' il campo che propone voci mentre scrivi
        // — ricerche, stazioni, indirizzi. Chi compone un messaggio non ha
        // niente da completare.
        if (inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) return true

        return false
    }
}
