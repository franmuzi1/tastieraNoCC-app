package helium314.keyboard.cipher

import android.inputmethodservice.InputMethodService
import android.view.WindowManager

/**
 * Chi decide se la finestra della tastiera e' protetta dagli screenshot.
 *
 * ## Perche' non lo fa ognuno per conto suo
 *
 * `FLAG_SECURE` sta sulla FINESTRA, e la finestra dell'IME e' una sola: la riga
 * di composizione e il pannello di lettura ci vivono dentro insieme. Con due
 * proprietari indipendenti che chiamano `setFlags` e `clearFlags`, chiudere il
 * pannello mentre la riga e' a schermo toglierebbe la protezione alla riga —
 * un guasto silenzioso, perche' il chiaro continua a vedersi e niente segnala
 * che non e' piu' protetto.
 *
 * Quindi nessuno mette o toglie il flag: si dichiara che qualcosa e' cambiato e
 * qui si **ricalcola** da capo. Ricalcolare invece di contare i proprietari e'
 * deliberato: un contatore si sbilancia se qualcuno dimentica di rilasciare, e
 * si sbilancia in silenzio nella direzione peggiore — protezione che resta
 * accesa per sempre, cioe' una tastiera che impedisce gli screenshot a tutte le
 * app senza che nulla lo spieghi.
 *
 * ## Il prezzo, che e' reale
 *
 * Mentre il flag e' acceso il sistema rifiuta lo screenshot. Non e' gratis: chi
 * cifra in una chat non puo' fotografare quella schermata finche' la tastiera e'
 * aperta. E' il costo di non far uscire il chiaro dallo schermo, ed e' lo stesso
 * che il pannello di lettura paga gia'.
 *
 * Il flag segue la riga e non il suo contenuto: si accende quando la riga
 * compare, non quando ci finisce dentro il primo carattere. Aspettare il primo
 * carattere significherebbe che il primo carattere e' gia' stato a schermo
 * senza protezione.
 */
internal object CipherSchermoProtetto {

    /**
     * Ricalcola dallo stato attuale. Va chiamata da chiunque cambi qualcosa che
     * possa mostrare del chiaro: la riga che compare o sparisce, il pannello che
     * si apre o si chiude.
     *
     * Idempotente: chiamarla di piu' del necessario non costa niente ed e' molto
     * meglio del contrario.
     */
    fun aggiorna(ime: InputMethodService?) {
        val finestra = ime?.window?.window ?: return
        val serve = CipherPanel.isAperto() || CipherCompose.rigaASchermo()
        runCatching {
            if (serve) {
                finestra.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                finestra.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
