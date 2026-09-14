package com.huanchengfly.tieba.post.utils.webdav

/**
 * WebDAV 凭据存取结果的三态语义:
 * - [Credentials]:读回完整凭据,密码已解密
 * - [Empty]:本机从未保存过凭据(密码键不存在)
 * - [NeedsPassword]:有存档但密码无法解密(Keystore 异常/密文损坏),其余三项仍在,
 *   按约定降级为"需重新输入密码",不崩溃(ADR-0002)
 */
sealed interface WebDavCredentialsState {
    data class Credentials(
        val serverUrl: String,
        val username: String,
        val password: String,
        val remotePath: String,
    ) : WebDavCredentialsState

    data object Empty : WebDavCredentialsState

    data class NeedsPassword(
        val serverUrl: String,
        val username: String,
        val remotePath: String,
    ) : WebDavCredentialsState
}

/**
 * "密码未配置"与"密码为空串"的区分信号:
 * [WebDavCredentialsState.Empty] = 从未配置;[WebDavCredentialsState.Credentials.password]
 * 为空串 = 用户显式保存过空密码。密码键存在与否是唯一判据。
 */
object WebDavCredentialsConst {
    const val KEY_SERVER_URL = "webdav_server_url"
    const val KEY_USERNAME = "webdav_username"
    const val KEY_PASSWORD_CIPHER = "webdav_password_cipher"
    const val KEY_REMOTE_PATH = "webdav_remote_path"
}
