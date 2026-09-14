package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 03 号票验收项的 JVM 单元测试:假加密器 + DataStore fake,
 * 只测模块外部行为(存→取往返、密文落盘、异常降级、状态区分)。
 *
 * 多次写入的测试用手写内存 DataStore:真文件 DataStore 在 Windows JVM 上
 * 第二次写入会因 rename 不能覆盖已存在文件而失败(真机 Android 不受影响,
 * 该行为是 DataStore 库自身的问题,不在本模块测试范围内)。
 * "密文落盘"用单次写入的真实文件测试直接断言 preferences_pb 字节。
 *
 * 不用 coroutines-test(1.11.0 无 StandardTestScope):挂起代码直接 runBlocking。
 */
class WebDavCredentialStoreTest {

    // ── 测试设施 ──────────────────────────────────────────────

    /** 确定性假加密器:前缀反转模拟加密,可被强制故障模拟 Keystore 异常 */
    private class FakePasswordCipher(
        private val failOnEncrypt: Boolean = false,
        private val failOnDecrypt: Boolean = false,
    ) : PasswordCipher {
        override fun encrypt(plainText: String): String {
            if (failOnEncrypt) throw CipherUnavailableException("fake encrypt failure")
            return "ENC{" + plainText.reversed() + "}"
        }

        override fun decrypt(cipherText: String): String {
            if (failOnDecrypt) throw CipherUnavailableException("fake decrypt failure")
            if (!cipherText.startsWith("ENC{") || !cipherText.endsWith("}")) {
                throw CipherUnavailableException("fake corrupt ciphertext")
            }
            return cipherText.removePrefix("ENC{").removeSuffix("}").reversed()
        }
    }

    /** DataStore 接口的最小内存实现:串行化 updateData,data 回放当前值 */
    private class InMemoryDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private fun storeTest(
        cipher: PasswordCipher = FakePasswordCipher(),
        block: suspend (store: WebDavCredentialStore, dataStore: DataStore<Preferences>) -> Unit,
    ) {
        val dataStore = InMemoryDataStore()
        runBlocking { block(WebDavCredentialStore(dataStore, cipher), dataStore) }
    }

    // ── 存→取往返 ──────────────────────────────────────────────

    @Test
    fun `保存后读回四项凭据一致`() = storeTest { store, _ ->
        store.save(
            serverUrl = "https://dav.jianguoyun.com/dav/",
            username = "user@example.com",
            password = "app-password-123",
            remotePath = "/tblite/",
        )

        val state = store.load()
        state as WebDavCredentialsState.Credentials
        assertEquals("https://dav.jianguoyun.com/dav/", state.serverUrl)
        assertEquals("user@example.com", state.username)
        assertEquals("app-password-123", state.password)
        assertEquals("/tblite/", state.remotePath)
    }

    @Test
    fun `覆盖保存后读回新值`() = storeTest { store, _ ->
        store.save("https://a.example.com/", "user-a", "password-a", "/a/")
        store.save("https://b.example.com/", "user-b", "password-b", "/b/")

        val state = store.load()
        state as WebDavCredentialsState.Credentials
        assertEquals("https://b.example.com/", state.serverUrl)
        assertEquals("user-b", state.username)
        assertEquals("password-b", state.password)
        assertEquals("/b/", state.remotePath)
    }

    // ── 密文落盘 ──────────────────────────────────────────────

    @Test
    fun `密码在 DataStore 里为密文而非明文`() = storeTest { store, dataStore ->
        store.save("https://dav.example.com/", "user", "my-plain-secret", "/tblite/")

        val storedCipher = dataStore.data
            .first()[stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)]
        assertEquals("ENC{" + "my-plain-secret".reversed() + "}", storedCipher)
        assertNotEquals("my-plain-secret", storedCipher)
        // 明文不应出现在任何键值里
        val allValues = dataStore.data.first().asMap().values.filterIsInstance<String>()
        assertTrue(allValues.none { it == "my-plain-secret" })
    }

    /** 真文件落盘断言:preferences_pb 字节里无明文密码、有密文(单次写入,避开 Windows rename 问题) */
    @Test
    fun `密码落盘到真实 DataStore 文件时为密文而非明文`() {
        val dir = File.createTempFile("webdav-store-file-test", "").let { marker ->
            marker.delete()
            File(marker.absolutePath).apply { mkdirs() }
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val file = File(dir, "cipher.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            runBlocking {
                WebDavCredentialStore(dataStore, FakePasswordCipher())
                    .save("https://dav.example.com/", "user", "my-plain-secret", "/tblite/")
            }
            val raw = file.readBytes()
            val cipherText = "ENC{" + "my-plain-secret".reversed() + "}"
            assertFalse(
                "明文密码出现在落盘文件里",
                raw.toString(Charsets.ISO_8859_1).contains("my-plain-secret")
            )
            assertTrue(
                "密文未出现在落盘文件里",
                raw.toString(Charsets.ISO_8859_1).contains(cipherText)
            )
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `明文三项按明文落盘`() = storeTest { store, dataStore ->
        store.save("https://dav.example.com/", "user", "secret", "/tblite/")

        val prefs = dataStore.data.first()
        assertEquals(
            "https://dav.example.com/",
            prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_SERVER_URL)]
        )
        assertEquals("user", prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_USERNAME)])
        assertEquals("/tblite/", prefs[stringPreferencesKey(WebDavCredentialsConst.KEY_REMOTE_PATH)])
    }

    // ── 状态区分 ──────────────────────────────────────────────

    @Test
    fun `未保存过凭据时为 Empty`() = storeTest { store, _ ->
        assertEquals(WebDavCredentialsState.Empty, store.load())
    }

    @Test
    fun `显式保存空密码后为 Credentials 且密码为空串`() = storeTest { store, dataStore ->
        store.save("https://dav.example.com/", "user", "", "/tblite/")

        val state = store.load()
        state as WebDavCredentialsState.Credentials
        assertEquals("", state.password)

        // 与"从未配置"可区分:空密码走加密,密码键存在
        assertTrue(
            dataStore.data.first().contains(
                stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)
            )
        )
    }

    // ── 异常降级 ──────────────────────────────────────────────

    @Test
    fun `解密失败时降级为 NeedsPassword 且不崩溃且保留其余三项`() = storeTest { store, dataStore ->
        store.save("https://dav.example.com/", "user", "secret", "/tblite/")

        // 用故障加密器读:模拟 Keystore 密钥丢失/失效
        val brokenStore = WebDavCredentialStore(dataStore, FakePasswordCipher(failOnDecrypt = true))
        val state = brokenStore.load()
        state as WebDavCredentialsState.NeedsPassword
        assertEquals("https://dav.example.com/", state.serverUrl)
        assertEquals("user", state.username)
        assertEquals("/tblite/", state.remotePath)
    }

    @Test
    fun `密文损坏时降级为 NeedsPassword`() = storeTest { _, dataStore ->
        dataStore.edit {
            it[stringPreferencesKey(WebDavCredentialsConst.KEY_SERVER_URL)] = "https://dav.example.com/"
            it[stringPreferencesKey(WebDavCredentialsConst.KEY_USERNAME)] = "user"
            it[stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)] = "not-a-valid-cipher"
            it[stringPreferencesKey(WebDavCredentialsConst.KEY_REMOTE_PATH)] = "/tblite/"
        }

        val state = WebDavCredentialStore(dataStore, FakePasswordCipher()).load()
        assertTrue(state is WebDavCredentialsState.NeedsPassword)
    }

    @Test
    fun `降级后重新保存凭据恢复为 Credentials`() = storeTest { store, dataStore ->
        store.save("https://dav.example.com/", "user", "secret", "/tblite/")

        val brokenStore = WebDavCredentialStore(dataStore, FakePasswordCipher(failOnDecrypt = true))
        assertTrue(brokenStore.load() is WebDavCredentialsState.NeedsPassword)

        store.save("https://dav.example.com/", "user", "new-secret", "/tblite/")
        val state = WebDavCredentialStore(dataStore, FakePasswordCipher()).load()
        state as WebDavCredentialsState.Credentials
        assertEquals("new-secret", state.password)
    }

    // ── clear ─────────────────────────────────────────────────

    @Test
    fun `clear 后回到 Empty`() = storeTest { store, _ ->
        store.save("https://dav.example.com/", "user", "secret", "/tblite/")
        store.clear()
        assertEquals(WebDavCredentialsState.Empty, store.load())
    }

    @Test
    fun `真文件测试的临时目录在测试结束后清理干净`() {
        val dir = File.createTempFile("webdav-store-cleanup-test", "").let { marker ->
            marker.delete()
            File(marker.absolutePath).apply { mkdirs() }
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "cleanup.preferences_pb")
            }
            runBlocking {
                WebDavCredentialStore(dataStore, FakePasswordCipher())
                    .save("https://dav.example.com/", "user", "secret", "/tblite/")
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
        // DataStore scope 已 cancel、句柄应已释放:Windows 上句柄未释放时删除会留下文件
        assertEquals(
            "临时目录未清理干净(文件句柄未释放?)",
            emptyList<String>(),
            dir.list()?.toList() ?: emptyList<String>(),
        )
    }

    // ── loadFlow ──────────────────────────────────────────────

    @Test
    fun `loadFlow 观测到保存后的凭据`() = storeTest { store, _ ->
        assertEquals(WebDavCredentialsState.Empty, store.loadFlow().first())

        store.save("https://dav.example.com/", "user", "secret", "/tblite/")
        val state = store.loadFlow().first()
        state as WebDavCredentialsState.Credentials
        assertEquals("secret", state.password)
    }
}
