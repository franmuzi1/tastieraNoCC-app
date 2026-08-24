package helium314.keyboard.cipher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

/**
 * "A chi sto scrivendo?" — la scelta esplicita del destinatario.
 *
 * ## Perche' serve
 *
 * Il destinatario si stabilisce in tre modi: decifrando (chi legge e poi
 * risponde ha gia' scelto leggendo), per memoria dell'app, oppure
 * esplicitamente. Il terzo non era mai stato implementato, e questo lasciava
 * un vicolo cieco molto facile da imboccare: fissi la chiave di qualcuno dalla
 * sua presentazione, vai nella chat, premi il lucchetto — e non succede
 * niente, perche' per **quell'app** un destinatario non c'e' mai stato e non
 * c'era modo di sceglierlo.
 *
 * ## Perche' un'Activity e non un menu della tastiera
 *
 * Mostra i fingerprint, che non devono finire nella miniatura dei Recenti:
 * serve `FLAG_SECURE`, e una finestra dell'IME non ce l'ha. In cambio si perde
 * il fuoco sul campo, ed e' il motivo per cui questa schermata **non cifra**:
 * sceglie e basta, poi l'utente torna e preme il lucchetto.
 *
 * ## Da quale app
 *
 * Dal gettone di [CipherHandoff], come per [DecryptActivity]: e' l'unica
 * attribuzione che un'app esterna non puo' falsificare. Senza gettone non si
 * sceglie niente — attribuire la scelta all'app sbagliata significherebbe
 * dirottare per chi cifri, che e' il fallimento peggiore di questo sistema.
 *
 * ## L'aspetto
 *
 * Lo stesso dialogo della scheda contatto, con gli stessi componenti: e' la
 * stessa cosa — un elenco di persone con la loro impronta — e vista da due
 * schermate diverse deve avere la stessa faccia. Da qui la finestra
 * trasparente: il riquadro se lo disegna [ThreeButtonAlertDialog], e un tema
 * di dialogo sotto ne avrebbe messo un secondo intorno al primo.
 */
internal class RecipientActivity : ComponentActivity() {

    private var appDiProvenienza: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)

        appDiProvenienza = CipherHandoff
            .consume(intent.getStringExtra(CipherHandoff.extraName()))
            .orEmpty()

        // Senza gettone non si guarda nemmeno il keyring: non si sa per quale
        // app si starebbe scegliendo, e sceglierlo per quella sbagliata
        // significherebbe dirottare per chi cifri.
        val stato = if (appDiProvenienza.isEmpty()) null else CipherIdentity.ensureReady(this)

        setContent {
            Theme {
                when (stato) {
                    CipherState.Ready -> Scelta()
                    CipherState.Locked -> Avviso(stringResource(R.string.cipher_locked))
                    else -> Avviso(stringResource(R.string.cipher_unavailable))
                }
            }
        }
    }

    @Composable
    private fun Scelta() {
        val peers = remember {
            // Ordinati per uso recente: chi scrivi spesso sta in cima invece
            // che in fondo a un elenco che cresce. Nessuna preselezione — vedi
            // CipherUsage: l'ordine non afferma chi sia il destinatario.
            CipherUsage.ordinati(
                this,
                CipherCore.nativeListPeers()?.let { PeerList.parse(it) }.orEmpty(),
            )
        }
        var revisione by remember { mutableIntStateOf(0) }
        val gruppi = remember(revisione) { CipherGroups.tutti(this) }
        var multiplo by remember { mutableStateOf(false) }
        val scelti = remember { mutableStateListOf<Int>() }
        var chiediNome by remember { mutableStateOf(false) }

        val senzaNome = stringResource(R.string.cipher_unnamed_peer)
        val righe = peers.map { peer ->
            val nome = peer.label ?: senzaNome
            VoceDestinatario(
                nome = if (peer.verified) stringResource(R.string.cipher_sender_verified, nome) else nome,
                impronta = CipherCore.nativeFingerprintOf(peer.key).orEmpty(),
            )
        }

        if (chiediNome) {
            TextInputDialog(
                onDismissRequest = { chiediNome = false },
                onConfirmed = { nome -> salvaGruppo(nome, scelti.map { peers[it].key }) },
                title = { Text(stringResource(R.string.cipher_group_name_title)) },
                description = { Text(stringResource(R.string.cipher_group_name_hint)) },
                checkTextValid = { it.isNotBlank() },
            )
            return
        }

        SceltaDestinatario(
            righe = righe,
            gruppi = gruppi.map {
                stringResource(R.string.cipher_recipient_group, it.nome, it.membri.size)
            },
            multiplo = multiplo,
            scelti = scelti.toSet(),
            // NON si chiude se stiamo passando al nome del gruppo.
            // `ThreeButtonAlertDialog` chiama `onConfirmed()` e SUBITO DOPO
            // `onDismissRequest()` sullo stesso tocco: senza questo controllo
            // premere "Avanti" chiudeva l'Activity prima che il dialogo del
            // nome potesse comparire, e il gruppo non si poteva salvare in
            // nessun modo. Il difetto era invisibile — nessun errore, la
            // schermata semplicemente spariva.
            onChiudi = { if (!chiediNome) finish() },
            onScegli = { indice ->
                if (multiplo) {
                    if (!scelti.remove(indice)) scelti.add(indice)
                } else {
                    scegli(peers[indice])
                }
            },
            onScegliGruppo = { indice -> scegliGruppo(gruppi[indice].nome) },
            onDimenticaGruppo = { indice ->
                CipherGroups.dimentica(this, gruppi[indice].nome)
                revisione++
            },
            onMultiplo = {
                multiplo = true
                scelti.clear()
            },
            onConferma = { chiediNome = true },
        )
    }

    /**
     * Salva il gruppo e lo sceglie subito come destinatario.
     *
     * Sceglierlo subito e' il punto: chi ha appena spuntato tre persone e dato
     * un nome vuole scrivere a quelle tre, non tornare all'elenco e sceglierle
     * di nuovo. Salvare senza selezionare sarebbe una schermata che chiede due
     * volte la stessa cosa.
     */
    private fun salvaGruppo(nome: String, membri: List<ByteArray>) {
        if (!CipherGroups.salva(this, nome, membri)) {
            Toast.makeText(this, R.string.cipher_group_not_saved, Toast.LENGTH_SHORT).show()
            return
        }
        scegliGruppo(nome)
    }

    private fun scegliGruppo(nome: String) {
        if (!CipherGroups.scegli(this, appDiProvenienza, nome)) {
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    @Composable
    private fun Avviso(testo: String) {
        ThreeButtonAlertDialog(
            onDismissRequest = { finish() },
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
            reducePadding = true,
            content = { Text(testo) },
        )
    }

    private fun scegli(peer: Peer) {
        if (CipherCore.nativeSetCurrentPeer(appDiProvenienza, peer.key) != CipherCore.OK) {
            Toast.makeText(this, R.string.cipher_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        // Su disco, altrimenti la scelta muore al primo riavvio del servizio —
        // che e' il guasto che questo progetto ha gia' pagato una volta.
        CipherRecipients.remember(this, appDiProvenienza, peer.key)
        CipherUsage.nota(this, peer.key)
        // Via il gruppo, se ce n'era uno scelto per questa app. Il gruppo vince
        // sul singolo quando si cifra — e' l'ordine giusto — quindi lasciarlo
        // qui significherebbe che scegliere una persona non ha nessun effetto:
        // il tasto sembra funzionare e il messaggio va ancora a tutti.
        CipherGroups.scegli(this, appDiProvenienza, null)
        // Nessun avviso: diceva "ora in questa app cifri per questo contatto",
        // e non e' cosi'. Il destinatario corrente lo stabilisce anche —
        // e soprattutto — l'ultimo messaggio decifrato in quell'app, quindi
        // quella frase prometteva una stabilita' che non c'e'.
        finish()
    }

    companion object {
        /** Intent gia' pronto, con il gettone che dice da quale app si arriva. */
        fun intent(context: android.content.Context, appPackage: String): Intent =
            Intent(context, RecipientActivity::class.java).apply {
                putExtra(CipherHandoff.extraName(), CipherHandoff.issue(appPackage))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

/** Una riga dell'elenco, gia' pronta per essere mostrata. */
internal class VoceDestinatario(val nome: String, val impronta: String)

/**
 * Il dialogo, senza sapere niente del keyring.
 *
 * Fuori dall'Activity per la stessa ragione dei pezzi della schermata contatti:
 * cosi' esiste [AnteprimaDestinatario], e questa finestra ha `FLAG_SECURE` —
 * fotografarla da' un'immagine vuota, quindi senza anteprima l'unico modo di
 * vedere come viene e' guardare il telefono.
 */
@Composable
private fun SceltaDestinatario(
    righe: List<VoceDestinatario>,
    onChiudi: () -> Unit,
    onScegli: (Int) -> Unit,
    gruppi: List<String> = emptyList(),
    multiplo: Boolean = false,
    scelti: Set<Int> = emptySet(),
    onScegliGruppo: (Int) -> Unit = {},
    onDimenticaGruppo: (Int) -> Unit = {},
    onMultiplo: () -> Unit = {},
    onConferma: () -> Unit = {},
) {
    ThreeButtonAlertDialog(
        onDismissRequest = onChiudi,
        onConfirmed = onConferma,
        // A scelta singola non c'e' niente da confermare: la scelta e' il tocco
        // sul nome, e un "OK" chiederebbe due gesti per una decisione sola. A
        // selezione multipla invece il pulsante serve, perche' i tocchi sono
        // molti e la fine la decide chi sceglie.
        confirmButtonText = if (multiplo && scelti.size >= 2) {
            stringResource(R.string.cipher_group_save)
        } else {
            null
        },
        cancelButtonText = stringResource(android.R.string.cancel),
        scrollContent = true,
        reducePadding = true,
        title = { Text(stringResource(R.string.cipher_pick_recipient)) },
        content = {
            Column {
                if (righe.isEmpty()) {
                    // Non e' un errore: e' il primo avvio. Chi non ha ancora
                    // nessun contatto deve sapere cosa fare, non leggere
                    // "vuoto".
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.cipher_pick_no_contacts),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    // I gruppi in cima, e solo fuori dalla selezione multipla:
                    // mentre si sta costruendo un gruppo, sceglierne un altro
                    // butterebbe via la selezione a meta'.
                    if (!multiplo) {
                        gruppi.forEachIndexed { indice, nome ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onScegliGruppo(indice) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = nome,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onDimenticaGruppo(indice) }) {
                                    Text(stringResource(R.string.cipher_group_forget))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        TextButton(onClick = onMultiplo) {
                            Text(stringResource(R.string.cipher_group_new))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        // Si dice cosa si sta facendo: senza, una schermata con
                        // le spunte al posto della scelta diretta sembra la
                        // stessa di prima, rotta.
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.cipher_group_pick_members),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    righe.forEachIndexed { indice, riga ->
                        if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onScegli(indice) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (multiplo && indice in scelti) "✓  ${riga.nome}" else riga.nome,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            // L'impronta sotto il nome, sempre: due contatti
                            // senza nome sono distinguibili solo da quella, ed
                            // e' anche cio' che si confronta di persona. Non
                            // selezionabile, altrimenti si prenderebbe il tocco
                            // destinato alla riga.
                            Text(
                                text = riga.impronta,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Vedi la nota su [SceltaDestinatario]. Le impronte qui sono inventate. */
@Preview
@Composable
internal fun AnteprimaDestinatario() {
    Theme {
        Surface {
            Spacer(Modifier.height(240.dp))
            SceltaDestinatario(
                righe = listOf(
                    VoceDestinatario("Giulia ✓", "8ejk mcpq xot1 uwis zybn drfg"),
                    VoceDestinatario("Senza nome", "qxot 1uwi szyb ndrf g8ej kmcp"),
                ),
                onChiudi = { },
                onScegli = { },
            )
        }
    }
}
