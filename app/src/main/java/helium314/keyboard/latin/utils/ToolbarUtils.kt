// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.forEach
import helium314.keyboard.cipher.CipherClipboard
import helium314.keyboard.cipher.CipherSettings
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.Locale

fun createToolbarKey(context: Context, key: ToolbarKey): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = key
    button.contentDescription = key.name.lowercase().getStringResourceOrName("", context)
    setToolbarButtonActivatedState(button)
    button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
    return button
}

fun setToolbarButtonsActivatedStateOnPrefChange(buttonsGroup: ViewGroup, key: String?) {
    // settings need to be updated when buttons change
    if (key != Settings.PREF_AUTO_CORRECTION
        && key != Settings.PREF_ALWAYS_INCOGNITO_MODE
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_GATHERING_ENABLED
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_DISABLED_BEFORE_TIME_MILLIS
        && key?.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) == false)
        return

    GlobalScope.launch {
        delay(10) // need to wait until SettingsValues are reloaded
        buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
    }
}

/**
 * keyboard-cipher: ricalcola lo stato "acceso" dei tasti.
 *
 * Serve perche' i tasti si costruiscono UNA volta, e da allora il loro stato
 * resta congelato. Va bene per tutto cio' che dipende dai preferences, e non
 * va bene per il tasto "decifra", che dipende dagli appunti: il contenuto puo'
 * cambiare mentre la tastiera e' viva, e senza un ricalcolo l'indizio
 * resterebbe quello del momento in cui la striscia e' nata.
 */
fun refreshToolbarButtonStates(buttonsGroup: ViewGroup) {
    buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
}

private fun setToolbarButtonActivatedState(button: ImageButton) {
    button.isActivated = when (button.tag) {
        INCOGNITO -> button.context.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
        ONE_HANDED -> Settings.getValues().mOneHandedModeEnabled
        SPLIT -> Settings.getValues().mIsSplitKeyboardEnabled
        AUTOCORRECT -> Settings.getValues().mAutoCorrectionEnabledPerUserSettings
        BACKGROUND_GATHERING -> useBackgroundGathering
        // keyboard-cipher: acceso quando negli appunti c'e' qualcosa che ha la
        // forma di un nostro blob. Non promette che sia decifrabile — potrebbe
        // essere troncato o per un altro destinatario — dice solo che vale la
        // pena provare.
        //
        // Si accende solo con la cronologia clipboard attiva, perche' e' li'
        // che il contenuto viene letto: guardarlo per conto nostro
        // significherebbe leggere gli appunti a ogni sessione di digitazione.
        DECRYPT -> CipherClipboard.clipboardLooksDecryptable()
        // Acceso quando si scrive dentro la tastiera. E' un interruttore, non
        // un comando: deve mostrare in che stato si trova senza doverlo premere.
        COMPOSE -> CipherSettings.isComposeMode(button.context.prefs())
        else -> true
    }
}

fun getCodeForToolbarKey(key: ToolbarKey) = Settings.getInstance().getCustomToolbarKeyCode(key) ?: when (key) {
    VOICE -> KeyCode.VOICE_INPUT
    CLIPBOARD -> KeyCode.CLIPBOARD
    NUMPAD -> KeyCode.NUMPAD
    DPAD -> KeyCode.DPAD
    UNDO -> KeyCode.UNDO
    REDO -> KeyCode.REDO
    SETTINGS -> KeyCode.SETTINGS
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_ALL
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_WORD
    COPY -> KeyCode.CLIPBOARD_COPY
    CUT -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD_PASTE
    ONE_HANDED -> KeyCode.TOGGLE_ONE_HANDED_MODE
    INCOGNITO -> KeyCode.TOGGLE_INCOGNITO_MODE
    AUTOCORRECT -> KeyCode.TOGGLE_AUTOCORRECT
    CLEAR_CLIPBOARD -> KeyCode.CLIPBOARD_CLEAR_HISTORY
    CLOSE_HISTORY -> KeyCode.CLIPBOARD
    EMOJI -> KeyCode.EMOJI
    LEFT -> KeyCode.ARROW_LEFT
    RIGHT -> KeyCode.ARROW_RIGHT
    UP -> KeyCode.ARROW_UP
    DOWN -> KeyCode.ARROW_DOWN
    WORD_LEFT -> KeyCode.WORD_LEFT
    WORD_RIGHT -> KeyCode.WORD_RIGHT
    PAGE_UP -> KeyCode.PAGE_UP
    PAGE_DOWN -> KeyCode.PAGE_DOWN
    FULL_LEFT -> KeyCode.MOVE_START_OF_LINE
    FULL_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_START -> KeyCode.MOVE_START_OF_PAGE
    PAGE_END -> KeyCode.MOVE_END_OF_PAGE
    SPLIT -> KeyCode.SPLIT_LAYOUT
    FLOATING -> KeyCode.TOGGLE_FLOATING_WINDOW
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING
    ENCRYPT -> KeyCode.CIPHER_ENCRYPT
    DECRYPT -> KeyCode.CIPHER_DECRYPT
    SEND_PLAIN -> KeyCode.CIPHER_SEND_PLAIN
    COMPOSE -> KeyCode.CIPHER_TOGGLE_COMPOSE
    ATTACH -> KeyCode.CIPHER_ATTACH
    CONTACTS -> KeyCode.CIPHER_CONTACTS
}

fun getCodeForToolbarKeyLongClick(key: ToolbarKey) = Settings.getInstance().getCustomToolbarLongpressCode(key) ?: when (key) {
    // Pressione lunga su "cifra": inserisce la propria identity card. E' il
    // bootstrap del primo contatto, e sta qui perche' inserire nel campo e'
    // nativo per un IME mentre leggere la cronologia della chat non lo e'.
    ENCRYPT -> KeyCode.CIPHER_IDENTITY_CARD
    // Niente di diverso dalla pressione breve: consegnare il chiaro e' gia' la
    // cosa piu' irreversibile che questo tasto possa fare, e nasconderci sotto
    // una seconda azione significherebbe farla scattare per sbaglio.
    SEND_PLAIN -> KeyCode.CIPHER_SEND_PLAIN
    // Pressione lunga: salta il campo di input e vai dritto agli appunti.
    // Serve quando il campo contiene gia' altro — una risposta cominciata —
    // e la pressione breve userebbe quel testo dicendo "non e' cifrato".
    DECRYPT -> KeyCode.CIPHER_DECRYPT_CLIPBOARD
    CLIPBOARD -> KeyCode.CLIPBOARD_PASTE
    UNDO -> KeyCode.REDO
    REDO -> KeyCode.UNDO
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_WORD
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_ALL
    COPY -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD
    LEFT -> KeyCode.KEY_REPEAT
    RIGHT -> KeyCode.KEY_REPEAT
    UP -> KeyCode.KEY_REPEAT
    DOWN -> KeyCode.KEY_REPEAT
    WORD_LEFT -> KeyCode.KEY_REPEAT
    WORD_RIGHT -> KeyCode.KEY_REPEAT
    PAGE_UP -> KeyCode.MOVE_START_OF_PAGE
    PAGE_DOWN -> KeyCode.MOVE_END_OF_PAGE
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING_TEMP_OFF
    else -> KeyCode.UNSPECIFIED
}

// names need to be aligned with resources strings (using lowercase of key.name)
enum class ToolbarKey {
    VOICE, CLIPBOARD, NUMPAD, DPAD, UNDO, REDO, SETTINGS, SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE, ONE_HANDED, FLOATING, SPLIT,
    INCOGNITO, AUTOCORRECT, CLEAR_CLIPBOARD, CLOSE_HISTORY, EMOJI, LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT,
    PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END, BACKGROUND_GATHERING,
    // keyboard-cipher. In coda di proposito: l'ordine dell'enum e' l'ordine in
    // cui i tasti compaiono nel personalizzatore, e aggiungere in mezzo
    // sposterebbe quelli di HeliBoard senza motivo.
    ENCRYPT, DECRYPT, SEND_PLAIN, COMPOSE, ATTACH, CONTACTS
}

enum class ToolbarMode {
    EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN,
}

val toolbarKeyStrings = entries.associateWithTo(EnumMap(ToolbarKey::class.java)) { it.toString().lowercase(Locale.US) }

val defaultToolbarPref by lazy {
    // keyboard-cipher: ENCRYPT e DECRYPT sono attivi di default e stanno in
    // testa. E' la ragione per cui questo fork esiste, e lasciarli spenti
    // significava che chi installava non trovava niente e concludeva che non
    // funzionasse — succede anche a chi il progetto lo conosce.
    //
    // Attenzione: questo vale per le installazioni NUOVE. Chi ha gia' un
    // profilo di toolbar salvato se li vede aggiunti da `upgradeToolbarPref`,
    // che pero' aggiunge le voci nuove come spente — e va bene cosi': quelle
    // preferenze sono scelte dell'utente, non nostre da sovrascrivere.
    // CLIPBOARD subito dopo i tasti di cifratura, cioe' dentro la parte che si
    // vede senza espandere. Mettendo i nostri quattro in testa lo avevamo
    // spinto oltre il bordo: la lista degli appunti c'era e funzionava, ma per
    // arrivarci bisognava sapere che esiste una freccia che apre il resto —
    // e chi non lo sa conclude che la tastiera non abbia la cronologia.
    val default = listOf(COMPOSE, CLIPBOARD, ATTACH, CONTACTS, DECRYPT, ENCRYPT, SEND_PLAIN, SETTINGS, VOICE, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT)
    val others = entries.filterNot { it in default || it == CLOSE_HISTORY }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultPinnedToolbarPref by lazy {
    // keyboard-cipher: i due lucchetti sono fissati, cioe' restano visibili
    // accanto ai suggerimenti senza dover aprire la barra.
    //
    // Costa due posti alla striscia dei suggerimenti, ed e' un prezzo pagato
    // volentieri: attivi ma nascosti dietro una freccia significa che chi
    // installa non li trova, e una funzione che non si trova non esiste.
    //
    // CLIPBOARD e' fissato con loro: la lista degli appunti c'era gia' e
    // funzionava, ma stava oltre il bordo, e per arrivarci bisognava sapere
    // che la freccia apre altro. Chi non lo sa conclude che la tastiera non
    // abbia la cronologia — ed e' successo. Fissarlo qui e' l'unico modo per
    // farlo comparire senza espandere: l'ordine della barra, da solo, non
    // decide cosa si vede accanto ai suggerimenti.
    // L'ordine e' letto DA DESTRA, cioe' da dove sta il pollice: consegna in
    // chiaro, cifra, decifra, contatti, immagine, allega, appunti. La striscia
    // si disegna da sinistra, quindi la lista e' quella all'incontrario.
    //
    // COMPOSE resta in fondo a sinistra: e' l'interruttore che porta fuori da
    // qui, non un'azione sul messaggio, e sta lontano da cio' che si preme di
    // continuo.
    val pinned = listOf(COMPOSE, CLIPBOARD, ATTACH, CONTACTS, DECRYPT, ENCRYPT, SEND_PLAIN)
    val others = entries.filterNot { it in pinned || it == CLOSE_HISTORY }
    pinned.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultClipboardToolbarPref by lazy {
    val default = listOf(CLEAR_CLIPBOARD, UP, DOWN, LEFT, RIGHT, UNDO, CUT, COPY, PASTE, SELECT_WORD, CLOSE_HISTORY)
    val others = entries.filterNot { it in default }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

/** add missing keys, typically because a new key has been added */
fun upgradeToolbarPrefs(prefs: SharedPreferences) {
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
}

private fun upgradeToolbarPref(prefs: SharedPreferences, pref: String, default: String) {
    if (!prefs.contains(pref)) return
    val list = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    val splitDefault = defaultToolbarPref.split(Separators.ENTRY)
    splitDefault.forEach { entry ->
        val keyWithSeparator = entry.substringBefore(Separators.KV) + Separators.KV
        if (list.none { it.startsWith(keyWithSeparator) })
            list.add("${keyWithSeparator}false")
    }
    // likely not needed, but better prepare for possibility of key removal
    list.removeAll {
        try {
            ToolbarKey.valueOf(it.substringBefore(Separators.KV))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
    }
    prefs.edit { putString(pref, list.joinToString(Separators.ENTRY)) }
}

fun getEnabledToolbarKeys(prefs: SharedPreferences) =
    withCipherKeys(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)

fun getPinnedToolbarKeys(prefs: SharedPreferences) =
    withCipherKeys(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)

/**
 * keyboard-cipher: i tasti della cifratura seguono le proprie impostazioni, non
 * solo quelle della toolbar.
 *
 * Si filtra qui, e non spegnendo le preferenze della toolbar, perche' quelle
 * sono scelte dell'utente: chi disattiva la cifratura e poi la riattiva deve
 * ritrovare la barra com'era, non ricostruirla.
 *
 * **Senza la riga di composizione resta il solo `COMPOSE`**, e la tastiera e'
 * HeliBoard con in piu' quell'interruttore. I lucchetti spariscono, e non per
 * ordine: senza la riga, "cifra" prende cio' che c'e' nel campo dell'app e lo
 * manda al destinatario **ricordato per quell'app**, scelto da solo. Un tasto
 * che cifra per una persona che non hai indicato in quel momento e' il
 * fallimento peggiore che questo sistema possa produrre, e nasconderlo dietro
 * un tocco solo lo rendeva facile. Con la riga accesa il destinatario e'
 * scritto accanto a cio' che stai scrivendo, e la stessa scorciatoia diventa
 * leggibile.
 *
 * *Conseguenza da conoscere:* sparisce anche **"decifra"**. Con la riga spenta
 * un messaggio in arrivo si apre dall'apertura automatica alla copia, dal menu
 * di selezione del testo o dallo share sheet — non dalla toolbar.
 *
 * E quando la modalita' e' accesa i tasti vengono **aggiunti** se la preferenza
 * salvata non li nomina affatto. Non e' una forzatura: le preferenze esistenti
 * non vengono sovrascritte dai default, quindi senza questo chi aggiorna
 * accenderebbe la modalita' e non troverebbe piu' i lucchetti — cioe' avrebbe
 * una riga di composizione senza il tasto per cifrare. Un tasto messo a `false`
 * dall'utente resta a `false`: quella e' una scelta, e il nome nella preferenza
 * la registra.
 */
private fun withCipherKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val keys = getEnabledToolbarKeys(prefs, pref, default)
    // COMPOSE sopravvive anche a cifratura spenta: e' l'interruttore, e un
    // interruttore che sparisce spegnendosi si puo' solo riaccendere dalle
    // impostazioni. Tutto il resto se ne va.
    if (!CipherSettings.isEnabled(prefs)) {
        return keys.filterNot { it in cipherKeys && it != COMPOSE }
    }
    val composizione = CipherSettings.isComposeMode(prefs)
    // COMPOSE resta anche a modalita' spenta: e' l'interruttore, cioe' l'unico
    // modo per riaccenderla. Tutto il resto della cifratura vive dentro la riga.
    val wanted = if (composizione) listOf(COMPOSE, ATTACH, CONTACTS, DECRYPT, ENCRYPT, SEND_PLAIN)
        else listOf(COMPOSE)
    val result = keys.filterNot { !composizione && it in cipherKeys && it != COMPOSE }
        .toMutableList()
    // Aggiunti se la preferenza salvata non li nomina affatto: le preferenze
    // esistenti non vengono sovrascritte dai default, quindi senza questo chi
    // aggiorna non troverebbe mai i tasti nuovi. Un tasto messo a `false`
    // dall'utente resta a `false`: quella e' una scelta, e il nome nella
    // preferenza la registra.
    val saved = prefs.getString(pref, default).orEmpty()
    for (key in wanted) {
        if (key in result || saved.contains(key.name)) continue
        val after = result.indexOf(ENCRYPT)
        if (after >= 0) result.add(after.inc(), key) else result.add(key)
    }
    return result
}

private val cipherKeys = setOf(ENCRYPT, DECRYPT, SEND_PLAIN, COMPOSE, ATTACH, CONTACTS)

fun getEnabledClipboardToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)

fun addPinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // remove the existing version of this key and add the enabled one after the last currently enabled key
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    keys.removeAll { it.startsWith(key.name + Separators.KV) }
    val lastEnabledIndex = keys.indexOfLast { it.endsWith("true") }
    keys.add(lastEnabledIndex + 1, key.name + Separators.KV + "true")
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

fun removePinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // just set it to disabled
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV))
            key.name + Separators.KV + "false"
        else it
    }
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, result) }
}

private fun getEnabledToolbarKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val string = prefs.getString(pref, default)!!
    return string.split(Separators.ENTRY).mapNotNull {
        val split = it.split(Separators.KV)
        if (split.last() == "true") {
            try {
                ToolbarKey.valueOf(split.first())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null
    }
}

fun writeCustomKeyCodes(prefs: SharedPreferences, codes: EnumMap<ToolbarKey, Pair<Int?, Int?>>) {
    val string = codes.mapNotNull { entry -> entry.value?.let { "${entry.key.name},${it.first},${it.second}" } }.joinToString(";")
    prefs.edit { putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, string) }
}

fun readCustomKeyCodes(prefs: SharedPreferences): EnumMap<ToolbarKey, Pair<Int?, Int?>> {
    val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
    prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Defaults.PREF_TOOLBAR_CUSTOM_KEY_CODES)!!
        .split(";").forEach {
            runCatching {
                val s = it.split(",")
                map[ToolbarKey.valueOf(s[0])] = s[1].toIntOrNull() to s[2].toIntOrNull()
            }
        }
    return map
}

fun getCustomKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.first
}

fun getCustomLongpressKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.second
}

fun clearCustomToolbarKeyCodes() {
    customToolbarKeyCodes = null
}

fun onClickToolbarKey(view: View, onCodeInput: (Int) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
    val code = getCodeForToolbarKey(view.tag as ToolbarKey)
    if (code != KeyCode.UNSPECIFIED) {
        onCodeInput(code)
    }
}

fun onLongClickToolbarKey(view: View, onCodeInput: (Int, Boolean) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_LONG_PRESS)
    val longClickCode = getCodeForToolbarKeyLongClick(view.tag as ToolbarKey)
    if (longClickCode == KeyCode.KEY_REPEAT) {
        onClickToolbarKey(view) { onCodeInput(it, false) }
        repeatToolbarKey(view) { onClickToolbarKey(view) { onCodeInput(it, true) } }
    } else if (longClickCode != KeyCode.UNSPECIFIED) {
        onCodeInput(longClickCode, false)
    }
}

private fun repeatToolbarKey(view: View, onClick: (view: View) -> Unit) {
    view.handler.postDelayed({
        if (view.isPressed) {
            onClick(view)
            repeatToolbarKey(view, onClick)
        }
    }, view.resources.getInteger(R.integer.config_key_repeat_interval).toLong())
}

private var customToolbarKeyCodes: EnumMap<ToolbarKey, Pair<Int?, Int?>>? = null
