package org.swatonly.xmrvault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The device pepper: 32 random bytes mixed into the vault's Argon2id key, stored only wrapped by
 * a non-exportable AES-256-GCM key in the Android Keystore (StrongBox when available).
 *
 * A stolen vault file is useless without this phone's secure hardware, and deleting the Keystore
 * key is a cryptographic erase of the wallet.
 */
object KeyGuard {
    private const val ALIAS = "xmrvault.pepper.v1"
    private const val PROVIDER = "AndroidKeyStore"

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "pepper.bin")

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun spec(strongBox: Boolean, unlockedOnly: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            // Unusable while the phone is locked.
            .setUnlockedDeviceRequired(unlockedOnly)
            .build()

    private fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        // Best first; some devices reject StrongBox or the unlocked-device flag.
        for ((strongBox, unlockedOnly) in listOf(true to true, true to false, false to true, false to false)) {
            try {
                gen.init(spec(strongBox, unlockedOnly))
                return gen.generateKey()
            } catch (e: StrongBoxUnavailableException) {
                continue
            } catch (e: Exception) {
                continue
            }
        }
        throw IllegalStateException("Android Keystore unavailable")
    }

    private fun key(): SecretKey =
        (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("device key missing: the wallet can't be opened on this install")

    fun exists(ctx: Context) = file(ctx).exists()

    /** New pepper for a new wallet. The caller must zero the returned array. */
    fun create(ctx: Context): ByteArray {
        destroy(ctx)
        val pepper = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, generateKey())
        val sealed = cipher.iv + cipher.doFinal(pepper)
        val tmp = File(ctx.noBackupFilesDir, "pepper.tmp")
        tmp.outputStream().use { it.write(sealed); it.fd.sync() }
        if (!tmp.renameTo(file(ctx))) throw IllegalStateException("can't store device key")
        return pepper
    }

    /** Unwrap the pepper. The caller must zero the returned array. */
    fun load(ctx: Context): ByteArray {
        val sealed = file(ctx).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    /** Crypto-erase: without the Keystore key the vault can never be decrypted again. */
    fun destroy(ctx: Context) {
        try { keyStore().deleteEntry(ALIAS) } catch (_: Exception) {}
        file(ctx).delete()
        File(ctx.noBackupFilesDir, "pepper.tmp").delete()
    }

    /** "StrongBox", "TEE", "software" or "none". */
    fun securityLevel(): String = try {
        val k = key()
        val info = SecretKeyFactory.getInstance(k.algorithm, PROVIDER).getKeySpec(k, KeyInfo::class.java) as KeyInfo
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                else -> "software"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "secure hardware" else "software"
        }
    } catch (_: Exception) {
        "none"
    }
}
