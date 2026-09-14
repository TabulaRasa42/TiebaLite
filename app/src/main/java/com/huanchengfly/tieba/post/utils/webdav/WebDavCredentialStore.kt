package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * WebDAV 凭据持久化(ADR-0002)。
 *
 * 服务器地址/用户名/远程路径明文存 DataStore;密码经 [PasswordCipher] 加密后以密文存 DataStore。
 * 保存与读回都是挂起函数,调度交调用方(与 DatabaseUtil 的 suspend 风格一致)。
 *
 * 三态语义见 [WebDavCredentialsState]:未配置 / 已配置 / 密码不可解(降级,不崩溃)。
 *
 * @param cipher 生产注入 [KeystorePasswordCipher],测试注入假实现
 */
class WebDavCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: PasswordCipher,
) {

    suspend fun save(
        serverUrl: String,
        username: String,
        password: String,
        remotePath: String,
    ) {
        // 空密码同样走加密:键存在即"已配置过",与"从未配置"保持可区分
        val passwordCipherText = cipher.encrypt(password)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_SERVER_URL)] = serverUrl
            prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_USERNAME)] = username
            prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)] = passwordCipherText
            prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_REMOTE_PATH)] = remotePath
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(WebDavCredentialsConst.KEY_SERVER_URL))
            prefs.remove(stringPreferencesKey(WebDavCredentialsConst.KEY_USERNAME))
            prefs.remove(stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER))
            prefs.remove(stringPreferencesKey(WebDavCredentialsConst.KEY_REMOTE_PATH))
        }
    }

    suspend fun load(): WebDavCredentialsState {
        return loadFrom(dataStore.data.first())
    }

    /**
     * 观测凭据状态流(UI 回填用):凭据四键变化时发射,解密在 IO 线程执行,
     * 不因其他无关设置键的写入而触发(密文不变则解密结果必然一致,以密文判变)。
     */
    fun loadFlow(): Flow<WebDavCredentialsState> {
        return dataStore.data
            .map { it[stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)] }
            .distinctUntilChanged()
            .map { cipherText ->
                loadFrom(cipherText, dataStore.data.first())
            }
            .flowOn(Dispatchers.IO)
    }

    private fun loadFrom(prefs: Preferences): WebDavCredentialsState {
        return loadFrom(
            prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)],
            prefs,
        )
    }

    /**
     * @param passwordCipherText 密文,null 即"从未保存过凭据"
     */
    private fun loadFrom(passwordCipherText: String?, prefs: Preferences): WebDavCredentialsState {
        if (passwordCipherText == null) {
            // 密码键不存在 = 从未保存过凭据(四键同写同删,单键判据足够)
            return WebDavCredentialsState.Empty
        }

        val serverUrl = prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_SERVER_URL)].orEmpty()
        val username = prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_USERNAME)].orEmpty()
        val remotePath = prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_REMOTE_PATH)].orEmpty()

        return try {
            WebDavCredentialsState.Credentials(
                serverUrl = serverUrl,
                username = username,
                password = cipher.decrypt(passwordCipherText),
                remotePath = remotePath,
            )
        } catch (e: CipherUnavailableException) {
            // Keystore 异常/密文损坏:降级为需重新输入密码,不崩溃(ADR-0002)
            WebDavCredentialsState.NeedsPassword(
                serverUrl = serverUrl,
                username = username,
                remotePath = remotePath,
            )
        }
    }
}
