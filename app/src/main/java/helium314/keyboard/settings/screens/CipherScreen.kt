// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.cipher.CipherKeepAlive
import helium314.keyboard.cipher.CipherSettings
import helium314.keyboard.cipher.ContactsActivity
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference

/**
 * Tutto cio' che riguarda la cifratura in un posto solo.
 *
 * Prima le due preferenze stavano sparse — una fra le Preferences generali,
 * i contatti come voce a se' nel menu principale — e la funzione che
 * distingue questa tastiera sembrava un dettaglio di configurazione.
 */
@Composable
fun CipherScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val enabled = CipherSettings.isEnabled(prefs)
    val items = listOf(
        CipherSettings.PREF_ENABLED,
        // Le altre voci esistono solo se la cifratura e' accesa: mostrarle
        // spente e inerti e' il modo piu' rapido per far credere che siano
        // rotte.
        // Solo con la riga attiva: senza, l'invio automatico non ha un momento
        // in cui scattare.
        if (enabled) CipherSettings.PREF_AUTO_SEND else null,
        // Stessa ragione: senza la riga il chiaro sta gia' nel campo dell'app, e
        // un divieto di copiarlo non proteggerebbe niente.
        if (enabled) CipherSettings.PREF_BLOCK_COPY else null,
        // Accanto al divieto di copia perche' rispondono alla stessa domanda —
        // dove finisce il chiaro oltre al campo dell'app — e questa e' la via
        // che non si vede: il dizionario personale entra nel backup di Android.
        if (enabled) CipherSettings.PREF_LEARN else null,
        if (enabled) CipherSettings.PREF_AUTO_OPEN else null,
        if (enabled) CipherSettings.PREF_FORWARD_SECRECY else null,
        // Le due risposte a "il telefono ferma la tastiera e copiare non fa
        // piu' niente", nell'ordine giusto: prima quella che non costa niente.
        if (enabled) SettingsWithoutKey.CIPHER_BATTERY else null,
        if (enabled) CipherSettings.PREF_KEEP_ALIVE else null,
        if (enabled) SettingsWithoutKey.CIPHER_CONTACTS else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.cipher_settings_category),
        settings = items
    )
}

fun createCipherSettings(context: Context) = listOf(
    Setting(
        context, CipherSettings.PREF_ENABLED,
        R.string.cipher_enabled, R.string.cipher_enabled_summary
    ) {
        // setThemeNeedsReload e non reloadKeyboard: i tasti in toolbar si
        // costruiscono UNA volta, quando nasce la striscia dei suggerimenti, e
        // reloadKeyboard rifa' la tastiera lasciando la striscia com'era.
        // Verificato sul dispositivo: il tasto nuovo compariva solo dopo aver
        // cambiato tastiera e essere tornati indietro, cioe' l'interruttore
        // sembrava non funzionare.
        SwitchPreference(it, CipherSettings.DEFAULT_ENABLED) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(
        context, CipherSettings.PREF_AUTO_SEND,
        R.string.cipher_auto_send, R.string.cipher_auto_send_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_AUTO_SEND)
    },
    Setting(
        context, CipherSettings.PREF_BLOCK_COPY,
        R.string.cipher_block_copy, R.string.cipher_block_copy_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_BLOCK_COPY)
    },
    Setting(
        context, CipherSettings.PREF_LEARN,
        R.string.cipher_learn, R.string.cipher_learn_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_LEARN)
    },
    Setting(
        context, CipherSettings.PREF_AUTO_OPEN,
        R.string.cipher_auto_open, R.string.cipher_auto_open_summary
    ) {
        // Il permesso serve all'avviso di ripiego, quello che compare quando il
        // telefono rifiuta di aprire la schermata da solo. Si chiede accendendo
        // l'interruttore e non alla prima copia: quando servirebbe, la tastiera
        // e' chiusa e un IME non apre un dialogo di permessi. Chi rifiuta perde
        // il ripiego, non la funzione.
        val chiediNotifiche = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        SwitchPreference(it, CipherSettings.DEFAULT_AUTO_OPEN) { acceso ->
            if (acceso && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    },
    Setting(
        context, CipherSettings.PREF_FORWARD_SECRECY,
        R.string.cipher_forward_secrecy, R.string.cipher_forward_secrecy_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_FORWARD_SECRECY)
    },
    Setting(
        context, SettingsWithoutKey.CIPHER_BATTERY,
        R.string.cipher_battery, R.string.cipher_battery_summary
    ) {
        val ctx = LocalContext.current
        // Lo stato si puo' LEGGERE senza permessi; e' chiederlo con un dialogo
        // che ne vorrebbe uno. Qui si apre la schermata di sistema e basta:
        // un tocco in piu' per l'utente, un permesso in meno nel manifest, e
        // "zero permessi" resta un vincolo vero invece di un ricordo.
        val gia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        Preference(
            name = it.title,
            description = if (gia) stringResource(R.string.cipher_battery_ok) else it.description,
            onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure {
                    // Non tutti i telefoni hanno quella schermata: si ripiega
                    // sulla pagina dell'app, da cui la batteria si raggiunge.
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", ctx.packageName, null),
                            )
                        )
                    }
                }
            },
        ) { NextScreenIcon() }
    },
    Setting(
        context, CipherSettings.PREF_KEEP_ALIVE,
        R.string.cipher_keep_alive, R.string.cipher_keep_alive_summary
    ) {
        val ctx = LocalContext.current
        // Il permesso si chiede accendendo, non alla prima notifica: senza, il
        // servizio partirebbe e la notifica non comparirebbe, cioe' l'unica
        // cosa che lo rende rispettato dai gestori di batteria.
        val chiediNotifiche = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { CipherKeepAlive.aggiorna(ctx) }
        SwitchPreference(it, CipherSettings.DEFAULT_KEEP_ALIVE) { acceso ->
            if (acceso && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                CipherKeepAlive.aggiorna(ctx)
            }
        }
    },
    Setting(context, SettingsWithoutKey.CIPHER_CONTACTS, R.string.cipher_contacts) {
        // Un'Activity e non una destinazione Compose: ContactsActivity ha
        // bisogno di FLAG_SECURE sulla propria finestra — mostra fingerprint,
        // che non devono finire nello screenshot dei Recenti — e una
        // destinazione dentro il grafo di navigazione condividerebbe la
        // finestra con tutte le impostazioni.
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            onClick = { ctx.startActivity(Intent(ctx, ContactsActivity::class.java)) },
        ) { NextScreenIcon() }
    },
)
