package com.huanchengfly.tieba.post.utils.webdav

/**
 * 密码加密器抽象(ADR-0002 的测试 seam)。
 *
 * WebDAV 密码落 DataStore 前经此加密,读回时解密。生产实现为 [KeystorePasswordCipher]
 * (Android Keystore AES-GCM);JVM 单元测试注入假实现即可验证"存进去是密文、取出来还原"。
 */
interface PasswordCipher {

    /**
     * 加密明文,返回可直接落 DataStore 的密文字符串。
     *
     * 空串也是合法输入(用户显式保存的空密码),加密后不得与明文形态相同。
     *
     * @throws CipherUnavailableException 系统级失败(Keystore 不可用等),调用方按"需重新输入密码"降级处理
     */
    fun encrypt(plainText: String): String

    /**
     * 解密 [encrypt] 产出的密文,还原明文。
     *
     * @throws CipherUnavailableException 密文不可解(密钥丢失/密文损坏/Keystore 异常)
     */
    fun decrypt(cipherText: String): String
}

/**
 * 加密器系统级失败的统一信号:Keystore 生成/加解密失败、密文损坏等。
 * 模块不把它当 bug 崩溃,而是映射为"需重新输入密码"的降级状态。
 */
class CipherUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
