// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
        if (enabled) CipherSettings.PREF_COMPOSE_MODE else null,
        // Solo con la riga attiva: senza, l'invio automatico non ha un momento
        // in cui scattare.
        if (enabled && CipherSettings.isComposeMode(prefs)) CipherSettings.PREF_AUTO_SEND else null,
        if (enabled) CipherSettings.PREF_AUTO_OPEN else null,
        if (enabled) CipherSettings.PREF_FORWARD_SECRECY else null,
        if (enabled) CipherSettings.PREF_FORWARD_SECRECY else null,
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
        context, CipherSettings.PREF_COMPOSE_MODE,
        R.string.cipher_compose_mode, R.string.cipher_compose_mode_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_COMPOSE_MODE) {
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
        context, CipherSettings.PREF_AUTO_OPEN,
        R.string.cipher_auto_open, R.string.cipher_auto_open_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_AUTO_OPEN)
    },
    Setting(
        context, CipherSettings.PREF_FORWARD_SECRECY,
        R.string.cipher_forward_secrecy, R.string.cipher_forward_secrecy_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_FORWARD_SECRECY)
    },
    Setting(
        context, CipherSettings.PREF_FORWARD_SECRECY,
        R.string.cipher_forward_secrecy, R.string.cipher_forward_secrecy_summary
    ) {
        SwitchPreference(it, CipherSettings.DEFAULT_FORWARD_SECRECY)
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
