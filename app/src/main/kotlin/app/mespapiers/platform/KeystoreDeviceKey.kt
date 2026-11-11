package app.mespapiers.platform

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.mespapiers.core.access.DeviceKey
import app.mespapiers.core.access.DeviceKeyUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clé AES-256-GCM non exportable d'Android Keystore (StrongBox si disponible, sinon TEE).
 *
 * NON COMPILÉ NI TESTÉ SUR APPAREIL À CE STADE (voir ECARTS.md, E-02).
 *
 * La clé n'est créée que par [create], à la configuration du coffre. [unwrap] ne recrée
 * jamais une clé manquante : une clé invalidée ou perdue rend le coffre illisible et
 * mène à la restauration d'une archive.
 */
class KeystoreDeviceKey(private val alias: String) : DeviceKey {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(): Boolean = keyStore().containsAlias(alias)

    /** Crée la clé (remplace une clé existante de même alias). Renvoie vrai si StrongBox est utilisé. */
    fun create(): Boolean {
        delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(strongBox = true)
                return true
            } catch (e: StrongBoxUnavailableException) {
                // Pas de StrongBox sur cet appareil : repli sur le TEE.
            }
        }
        generate(strongBox = false)
        return false
    }

    fun delete() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(true)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec.build()) }.generateKey()
    }

    private fun key(): SecretKey =
        (keyStore().getKey(alias, null) as? SecretKey) ?: throw DeviceKeyUnavailableException()

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        try {
            val ivLen = blob[0].toInt()
            if (ivLen != 12 || blob.size < 1 + ivLen + 16) throw DeviceKeyUnavailableException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, ivLen))
            return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
        } catch (e: DeviceKeyUnavailableException) {
            throw e
        } catch (e: GeneralSecurityException) {
            // Clé invalidée, supprimée, ou enveloppe produite par un autre appareil.
            throw DeviceKeyUnavailableException(e)
        } catch (e: RuntimeException) {
            throw DeviceKeyUnavailableException(e)
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALIAS_SEED = "mespapiers.totp-seed.v1"
        const val ALIAS_DATA = "mespapiers.data-key.v1"
    }
}
