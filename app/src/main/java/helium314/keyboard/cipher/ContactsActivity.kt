package helium314.keyboard.cipher

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Gestione contatti: elenco peer, fingerprint, conflitti di chiave, propria
 * identity card.
 *
 * Non esportata, non nel launcher: ci si arriva dalle impostazioni della
 * tastiera. SCHELETRO.
 */
class ContactsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Non mostra plaintext, ma mostra fingerprint e QR: roba che non deve
        // finire negli screenshot automatici dei Recenti.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)

        // TODO: elenco peer con fingerprint e stato "verificato".
        //
        // TODO: la propria identity card come stringa E come QR.
        //   Il QR di persona e' l'unica cosa che chiude il MITM al primo
        //   contatto, che il TOFU da solo non chiude: va reso facile da
        //   raggiungere, non sepolto.
        //   La scansione richiede CAMERA. Se lo si aggiunge: permesso a
        //   runtime, richiesto solo all'apertura dello scanner, mai
        //   all'installazione. Il fork non deve guadagnare permessi passivi.
        //
        // TODO: confronto fingerprint -> markVerified.
        //
        // TODO: schermata di conflitto chiave. Vincoli:
        //   - mostra ENTRAMBI i fingerprint, quello fissato e quello nuovo;
        //   - spiega le due letture possibili, senza scegliere per l'utente:
        //     il peer ha reinstallato l'app, oppure qualcuno si sta
        //     interponendo;
        //   - default = non fare nulla. La vecchia chiave resta fissata finche'
        //     non c'e' una conferma esplicita;
        //   - il pulsante di conferma non e' quello preselezionato.
    }
}
