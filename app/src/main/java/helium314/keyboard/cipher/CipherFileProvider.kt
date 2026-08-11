package helium314.keyboard.cipher

import androidx.core.content.FileProvider
import helium314.keyboard.latin.R

/**
 * Espone alla chat il solo allegato cifrato appena prodotto.
 *
 * Sottoclasse invece del `FileProvider` di androidx usato direttamente: due
 * provider con la stessa classe e authority diverse convivono male, e questo
 * fork ne ha gia' altri due. Una classe propria rende anche ovvio, leggendo il
 * manifest, quale cartella sta esponendo chi.
 */
class CipherFileProvider : FileProvider(R.xml.cipher_file_path)
