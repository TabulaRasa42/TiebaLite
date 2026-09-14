package com.huanchengfly.tieba.post.utils.webdav

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import kotlin.concurrent.withLock

/**
 * [PasswordCipher] 的生产实现(ADR-0002):Android Keystore 生成/保管的 AES-256-GCM 密钥,
 * 密钥不出安全硬件(在支持的设备上)。密文格式:Base64(1 字节版本 + 12 字节 IV + 密文)。
 *
 * 任何 Keystore/加解密系统级失败都归一为 [CipherUnavailableException],由上层降级,
 * 不让底层异常逃逸成崩溃。真机行为需真机验证(JVM 无 Keystore)。
 *
 * 线程安全:[obtainKey] 加锁——Android Keystore 对同 alias 的并发 generateKey 会用
 * 新密钥覆盖旧密钥,导致旧密文永久不可解;生成必须只发生一次。
 */
class KeystorePasswordCipher : PasswordCipher {

    private fun obtainKey(): SecretKey {
        return keyLock.withLock {
            val keyStore = try {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            } catch (e: Exception) {
                throw CipherUnavailableException("keystore unavailable", e)
            }
            val existing = try {
                keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            } catch (e: Exception) {
                throw CipherUnavailableException("keystore entry read failed", e)
            }?.secretKey

            if (existing != null) {
                existing
            } else {
                try {
                    val generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                    )
                    generator.init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    generator.generateKey()
                } catch (e: Exception) {
                    throw CipherUnavailableException("keystore key generation failed", e)
                }
            }
        }
    }

    override fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(1 + iv.size + encrypted.size)
            payload[0] = FORMAT_VERSION
            iv.copyInto(payload, 1)
            encrypted.copyInto(payload, 1 + iv.size)
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: CipherUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw CipherUnavailableException("encrypt failed", e)
        }
    }

    override fun decrypt(cipherText: String): String {
        return try {
            val payload = Base64.decode(cipherText, Base64.NO_WRAP)
            if (payload.isEmpty() || payload[0] != FORMAT_VERSION) {
                throw CipherUnavailableException("unknown cipher format")
            }
            val iv = payload.copyOfRange(1, 1 + GCM_IV_LENGTH)
            val encrypted = payload.copyOfRange(1 + GCM_IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: CipherUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw CipherUnavailableException("decrypt failed", e)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "webdav_password_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val FORMAT_VERSION: Byte = 1

        /** 密钥生成互斥锁:防止并发首用导致同 alias 二次 generateKey 覆盖旧密钥 */
        val keyLock = ReentrantLock()
    }
}
