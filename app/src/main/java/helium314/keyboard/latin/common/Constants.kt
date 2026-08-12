// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EXPERIMENTAL_SUFFIX = "dictionaries_experimental/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    /**
     * Il progetto a monte.
     *
     * Resta la radice di wiki, discussioni e link della comunita': quelle
     * pagine esistono **li'** e non in questo fork, quindi puntarle altrove
     * significherebbe mandare l'utente su un 404. E' anche l'attribuzione: la
     * tastiera e' lavoro loro.
     */
    const val GITHUB = "https://github.com/HeliBorg/HeliBoard"

    /**
     * Questo fork.
     *
     * Separato apposta: "vedi su GitHub" deve portare al codice che si sta
     * usando — se un bug e' nella parte cifrata, va segnalato qui e non a chi
     * non l'ha scritta.
     */
    const val PROJECT_GITHUB = "https://github.com/franmuzi1/tastieraNoCC-app"
    const val LICENSE = "$PROJECT_GITHUB/blob/cipher/LICENSE"
    const val WIKI_URL = "$GITHUB/wiki"
    const val LAYOUT_WIKI_URL = "$WIKI_URL/2.-Layouts"
    const val CUSTOM_LAYOUTS = "$GITHUB/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$GITHUB/discussions/categories/custom-colors"
    const val GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/cQECfDkuLGR9eUQquUEo4K"
    const val GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/CyjumVTWtJA"
    const val GESTURE_DATA_WIKI = "$WIKI_URL/Tutorial:-How-to-Contribute-Gesture-Data"
    const val BACKGROUND_GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/pPywMiF7kjumFfMrQKHzoU"
    const val BACKGROUND_GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/VJLzVEhY2PY"
    const val BACKGROUND_GESTURE_DATA_WIKI = "$WIKI_URL/Background-Gesture-Data-Gathering"
    const val SWIPE_O_SCOPE = "https://codeberg.org/eclexic/swipe-o-scope"
    const val COMMUNITY_LINKS = "$GITHUB#to-community"
}

val combiningRange = 0x300..0x35b
