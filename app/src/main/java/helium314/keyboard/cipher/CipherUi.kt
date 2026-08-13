package helium314.keyboard.cipher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.Theme

/**
 * I pezzi di interfaccia della cifratura, in un posto solo.
 *
 * Le schermate del fork sono tre — contatti, scelta del destinatario, testo
 * decifrato — e mostrano le stesse cose: nomi, impronte, righe che si toccano.
 * Tenendo i pezzi qui hanno la stessa faccia per costruzione, invece che per
 * disciplina: e' gia' successo di ritrovarsi con tre approssimazioni diverse
 * dello stesso elenco.
 *
 * Nessuno di questi tocca il keyring e nessuno sa cosa mostra: prendono
 * stringhe. E' anche cio' che rende possibili le anteprime, dato che le
 * finestre vere hanno `FLAG_SECURE` e fotografarle da' un'immagine vuota.
 */

/** Titolo di sezione, come nelle impostazioni. */
@Composable
internal fun Titolo(testo: String) {
    Text(
        text = testo,
        color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Il contenitore dagli angoli smussati che raccoglie una sezione. */
@Composable
internal fun Riquadro(contenuto: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { contenuto() }
    }
}

/** Una riga che si tocca, con le stesse misure delle voci di impostazioni. */
@Composable
internal fun Voce(testo: String, distruttiva: Boolean = false, quando: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { quando() }
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyLarge,
            color = if (distruttiva) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

/** Una riga dell'elenco contatti: nome, impronta, quando e' comparso. */
@Composable
internal fun Contatto(nome: String, impronta: String, visto: String?, quando: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { quando() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(text = nome, style = MaterialTheme.typography.bodyLarge)
        // Qui l'impronta NON e' selezionabile: un testo selezionabile consuma il
        // tocco, e occupando quasi tutta la riga renderebbe il contatto apribile
        // solo dalla striscia sottile del nome. Verificato sul dispositivo —
        // sembrava che i contatti non si aprissero affatto.
        Impronta(impronta, selezionabile = false, Modifier.padding(top = 2.dp))
        if (visto != null) Didascalia(visto, Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun Divisore() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Un messaggio breve dentro un riquadro: stati, errori, "niente qui". */
@Composable
internal fun Avviso(testo: String) {
    Riquadro {
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
internal fun Vuoto(testo: String) {
    Didascalia(testo, Modifier.padding(12.dp))
}

@Composable
internal fun Didascalia(testo: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text = testo, style = MaterialTheme.typography.bodySmall, modifier = modifier)
    }
}

/**
 * Monospaziato: sono 24 caratteri che due persone si leggono a voce o
 * confrontano a schermo, e un font proporzionale rende quel confronto piu'
 * difficile di quanto serva.
 *
 * @param selezionabile va acceso solo dove la riga non e' cliccabile: un testo
 * selezionabile consuma il tocco.
 */
@Composable
internal fun Impronta(testo: String, selezionabile: Boolean, modifier: Modifier = Modifier) {
    val riga = @Composable {
        Text(
            text = testo,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
    }
    if (selezionabile) SelectionContainer { riga() } else riga()
}

/**
 * Le schermate del decifrato, con dati finti.
 *
 * Sono `FLAG_SECURE`: fotografarle da' un'immagine vuota, quindi senza
 * anteprima l'unico modo di vedere come vengono e' guardare il telefono.
 * Niente qui dentro tocca il keyring — il testo e le impronte sono inventati.
 */
@Preview
@Composable
internal fun AnteprimaDecifrato() {
    Theme {
        Surface {
            Column(Modifier.padding(12.dp)) {
                Titolo("Messaggio decifrato")
                Riquadro {
                    Column(Modifier.padding(12.dp)) {
                        Didascalia("Scritto il 13/08/2026 02:14")
                        Spacer(Modifier.height(10.dp))
                        Text("ci vediamo alle sette sotto casa", style = MaterialTheme.typography.bodyLarge)
                    }
                    Divisore()
                    Voce("Copia") { }
                    Divisore()
                    Voce("Contatti") { }
                }
                Titolo("Presentazione ricevuta")
                Riquadro {
                    Column(Modifier.padding(12.dp)) {
                        Impronta("8ejk mcpq xot1 uwis zybn drfg", selezionabile = false)
                        Spacer(Modifier.height(6.dp))
                        Didascalia("Questa chiave e' ora fra i tuoi contatti.")
                    }
                    Divisore()
                    Voce("Scrivi a questo contatto") { }
                }
                Titolo("Avviso")
                Avviso("Questo testo non e' cifrato")
            }
        }
    }
}
