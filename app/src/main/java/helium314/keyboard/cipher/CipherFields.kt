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
 * ## Il criterio, e perche' e' stato rovesciato
 *
 * Prima si partiva dal presupposto che un campo fosse un compositore di
 * messaggi e si spegneva su prove contrarie: variante "filtro", azione "cerca",
 * "vai", "avanti", "fatto", completamento automatico. Quell'elenco e' cresciuto
 * ogni volta che qualcuno trovava la riga dove non doveva stare, ed e' finito
 * per non bastare comunque: la barra di ricerca di WhatsApp non dichiara
 * NIENTE — testo, riga singola, nessuna azione — quindi passava tutte le prove
 * contrarie e si prendeva la riga cifrata.
 *
 * Il difetto non era nell'elenco, era nella direzione: un elenco di prove
 * contrarie puo' solo inseguire i campi che si comportano male, e chi non
 * dichiara niente non lo si prende mai.
 *
 * Adesso la riga si accende **solo su prova a favore**, e le prove sono due:
 *
 *  - il campo accetta gli **a capo**. Un campo multiriga e' fatto per un testo
 *    che si scrive, non per un parametro che si compila: le barre di ricerca, i
 *    codici e le caselle dei moduli sono a riga singola per costruzione, perche'
 *    li' l'invio serve a confermare. E' il segnale che gia' prima vinceva su
 *    tutti gli altri, ed e' quello che tiene dentro WhatsApp, Telegram, Signal e
 *    gli SMS: i loro compositori sono tutti multiriga;
 *  - il campo dichiara **"invia"**. E' la sola azione che significa "questo
 *    testo se ne va da qui", e i compositori a riga singola che esistono la
 *    dichiarano.
 *
 * ## Cosa costa il rovesciamento
 *
 * Un compositore di chat a riga singola che non dichiara nemmeno "invia" resta
 * fuori, e li' la riga va accesa a mano. E' un falso negativo, e si ripara con
 * un tocco sul tasto in toolbar che **forza** la riga; un falso positivo — la
 * riga accesa su una ricerca — non si ripara affatto, perche' li' il testo
 * finisce nel nostro buffer, il campo dell'app resta vuoto e il tasto "cerca"
 * viene ingoiato. La bilancia pende da questa parte, ed e' lo stesso argomento
 * che aveva gia' fatto allargare le prove contrarie: da quando la forzatura
 * esiste, sbagliare per difetto costa un tocco e sbagliare per eccesso rompe il
 * campo.
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
        // Le password vengono prima, e sono l'unico caso non scavalcabile.
        if (vietata(editorInfo)) return true

        val inputType = editorInfo.inputType

        // Fuori da TYPE_CLASS_TEXT non si compone niente: numeri, telefono,
        // date sono campi che l'app legge per farci qualcosa, non per spedirli.
        // La classe si estrae con TYPE_MASK_CLASS, non confrontando l'intero,
        // che porta i flag e le varianti.
        //
        // Il vincolo serve anche a proteggere la prova qui sotto: fuori da
        // questa classe il bit "multiriga" non significa "piu' righe",
        // significa un bit qualunque di un'altra classe, e un `inputType`
        // malformato accenderebbe la riga cifrata su un campo che non e'
        // nemmeno testo.
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return true

        // Prima prova a favore: accetta gli a capo, quindi e' fatto per un
        // testo che si scrive.
        if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false

        // Seconda prova a favore: dichiara "invia". Si guarda l'azione grezza e
        // non `getImeOptionsActionIdFromEditorInfo`, che traduce l'etichetta
        // personalizzata in un valore sentinella: qui serve sapere cosa ha
        // dichiarato l'app, non cosa la tastiera ne fa.
        //
        // "Cerca", "vai", "avanti", "indietro" e "fatto" non compaiono piu' in
        // un elenco di esclusioni: non essendo "invia", cadono da sole.
        if (editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND) {
            return false
        }

        // Nessuna prova a favore: riga spenta, e il tasto in toolbar resta la
        // via per accenderla dove serve.
        return true
    }
}
