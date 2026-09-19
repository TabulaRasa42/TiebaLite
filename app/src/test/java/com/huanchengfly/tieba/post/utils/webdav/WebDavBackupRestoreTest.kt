package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.backup.InMemoryBlockStore
import com.huanchengfly.tieba.post.utils.backup.InMemoryHistoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * 02 号票:备份上传/下载/恢复分发(WebDavBackupRestore)+ 判重合并(BackupMerge)的 JVM 测试。
 *
 * 测试设施(复用 03 号票模板):
 * - fake WebDavClient:内存 map 模拟远端,记录调用序列(断言 mkcol→put 顺序);
 * - 内存 DataStore:真文件实现在 Windows JVM 二次写必败(DataStore 1.2.x rename 问题);
 * - 内存 HistoryStore/BlockStore。
 *
 * 不用 coroutines-test(1.11.0 无 StandardTestScope):挂起代码直接 runBlocking。
 */
class WebDavBackupRestoreTest {

    // ── 测试设施 ──────────────────────────────────────────────

    /** 内存 fake WebDavClient:模拟远端文件系统并记录调用序列 */
    private class FakeWebDavClient : WebDavClient {
        val files = mutableMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()
        var failOnPut: Boolean = false

        override suspend fun put(path: String, content: ByteArray) {
            calls += "put:$path"
            if (failOnPut) throw WebDavException.Http(507, "insufficient storage")
            files[path] = content
        }

        override suspend fun get(path: String): ByteArray {
            calls += "get:$path"
            return files[path] ?: throw WebDavException.Http(404, "HTTP 404: GET $path")
        }

        override suspend fun mkcol(path: String) {
            calls += "mkcol:$path"
        }

        override suspend fun exists(path: String): Boolean {
            calls += "exists:$path"
            return files.containsKey(path)
        }
    }

    /** DataStore 接口的最小内存实现(03 号票 WebDavCredentialStoreTest 模板) */
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

    private class Fixture {
        val client = FakeWebDavClient()
        // 内存 store 夹具共享自 backup 包(06 号票,InMemoryStores.kt)
        val historyStore = InMemoryHistoryStore()
        val blockStore = InMemoryBlockStore()
        val dataStore = InMemoryDataStore()
        val backupRestore = WebDavBackupRestore(client, historyStore, blockStore, dataStore)
    }

    private fun sampleHistories() = listOf(
        History(title = "帖子A", data = "1001", type = 0, timestamp = 1694659200000, count = 1),
        History(title = "帖子B", data = "1002", type = 0, timestamp = 1694659300000, count = 2),
    )

    private fun sampleBlocks() = listOf(
        Block(category = 10, type = 0, keywords = """["广告"]""", username = ""),
    )

    private fun samplePrefs(): Preferences = mutablePreferencesOf(
        booleanPreferencesKey("dark_amoled") to true,
        intPreferencesKey("radius") to 12,
    )

    // ── 打包 → 上传(fake client 分发链路)────────────────────

    @Test
    fun `上传先建目录再放固定文件名`() = runBlocking {
        val f = Fixture()

        f.backupRestore.upload("/tblite/", sampleHistories(), sampleBlocks(), samplePrefs())

        assertEquals(listOf("mkcol:/tblite/", "put:/tblite/${BackupJson.FILE_NAME}"), f.client.calls)
        val uploaded = f.client.files["/tblite/${BackupJson.FILE_NAME}"]
        assertNotNull(uploaded)
        val parsed = BackupJson.parse(uploaded!!.toString(Charsets.UTF_8))
        assertEquals(2, parsed.historyRecords().size)
        assertEquals(1, parsed.blockRules().size)
    }

    @Test
    fun `上传远端路径不带尾斜杠时归一化`() = runBlocking {
        val f = Fixture()

        f.backupRestore.upload("tblite", emptyList(), emptyList(), emptyPreferences())

        assertEquals(listOf("mkcol:tblite", "put:tblite/${BackupJson.FILE_NAME}"), f.client.calls)
    }

    @Test
    fun `上传统计与排除清单过滤后的键数一致`() = runBlocking {
        val f = Fixture()
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("normal") to "v",
            stringPreferencesKey(WebDavCredentialsConst.KEY_SERVER_URL) to "https://x/",
        )

        val result = f.backupRestore.upload("/tblite/", sampleHistories(), sampleBlocks(), prefs)

        assertEquals(2, result.historyCount)
        assertEquals(1, result.blockRuleCount)
        assertEquals(1, result.preferenceKeyCount) // webdav_server_url 被排除
    }

    @Test
    fun `上传失败抛WebDavException且不吞异常`() {
        val f = Fixture()
        f.client.failOnPut = true

        assertFailsWith<WebDavException.Http> {
            runBlocking {
                f.backupRestore.upload("/tblite/", emptyList(), emptyList(), emptyPreferences())
            }
        }
    }

    // ── 打包 → 分发往返(JVM,经 fake WebDavClient)───────────

    @Test
    fun `打包到远端再下载还原为相同三部分`() = runBlocking {
        val f = Fixture()
        val histories = sampleHistories()
        val blocks = sampleBlocks()
        val prefs = samplePrefs()

        f.backupRestore.upload("/tblite/", histories, blocks, prefs)
        val downloaded = f.backupRestore.download("/tblite/")

        assertEquals(BackupJson.CURRENT_VERSION, downloaded.version)
        assertEquals(2, downloaded.historyRecords().size)
        assertEquals("帖子A", downloaded.historyRecords()[0].title)
        assertEquals(listOf("广告"), downloaded.blockRules()[0].keywords)
        val entries = downloaded.preferenceEntries().associate { it.first.name to it.second }
        assertEquals(true, entries["dark_amoled"])
        assertEquals(12, entries["radius"])
    }

    @Test
    fun `下载远端无备份文件抛Http404`() {
        val f = Fixture()

        assertFailsWith<WebDavException.Http> {
            runBlocking { f.backupRestore.download("/tblite/") }
        }
    }

    // ── 恢复:全选 ────────────────────────────────────────────

    @Test
    fun `全选恢复三部分各自写入且数量正确`() = runBlocking {
        val f = Fixture()
        f.backupRestore.upload("/tblite/", sampleHistories(), sampleBlocks(), samplePrefs())
        val backup = f.backupRestore.download("/tblite/")

        val result = f.backupRestore.restore(backup, true, true, true)

        assertEquals(2, result.history?.added)
        assertEquals(0, result.history?.skipped)
        assertEquals(1, result.blockRules?.added)
        assertEquals(0, result.blockRules?.skipped)
        assertEquals(2, result.preferencesOverwritten)
        // 数据真的写入了
        assertEquals(2, f.historyStore.rows.size)
        assertEquals(1, f.blockStore.rows.size)
        val prefs = f.dataStore.data.first() // runBlocking 内可直接取 first
        assertEquals(true, prefs[booleanPreferencesKey("dark_amoled")])
        assertEquals(12, prefs[intPreferencesKey("radius")])
    }

    // ── 恢复:部分勾选 ────────────────────────────────────────

    @Test
    fun `只勾选浏览记录时其余两部分完全不被触碰`() = runBlocking {
        val f = Fixture()
        // 本地已有设置与规则:未勾选部分必须原样
        f.dataStore.edit { it[stringPreferencesKey("local_only")] = "untouched" }
        f.blockStore.rows += Block(category = 10, type = 0, keywords = """["已有"]""", username = "")
        f.backupRestore.upload("/tblite/", sampleHistories(), sampleBlocks(), samplePrefs())
        val backup = f.backupRestore.download("/tblite/")

        val result = f.backupRestore.restore(backup, true, false, false)

        assertEquals(2, result.history?.added)
        assertNull(result.blockRules)
        assertNull(result.preferencesOverwritten)
        assertEquals(1, f.blockStore.rows.size) // 未追加
        assertFalse(f.dataStore.data.first().contains(stringPreferencesKey("dark_amoled")))
        assertEquals("untouched", f.dataStore.data.first()[stringPreferencesKey("local_only")])
    }

    @Test
    fun `只勾选设置时浏览记录与规则零写入`() = runBlocking {
        val f = Fixture()
        f.backupRestore.upload("/tblite/", sampleHistories(), sampleBlocks(), samplePrefs())
        val backup = f.backupRestore.download("/tblite/")

        val result = f.backupRestore.restore(backup, false, false, true)

        assertNull(result.history)
        assertNull(result.blockRules)
        assertEquals(2, result.preferencesOverwritten)
        assertTrue(f.historyStore.rows.isEmpty())
        assertTrue(f.blockStore.rows.isEmpty())
    }

    // ── 恢复:判重(与本地导入一致)──────────────────────────

    @Test
    fun `重复浏览记录跳过不重复入库`() = runBlocking {
        val f = Fixture()
        f.backupRestore.upload("/tblite/", sampleHistories(), emptyList(), emptyPreferences())
        val backup = f.backupRestore.download("/tblite/")

        // 第一次恢复:全部入库
        val first = f.backupRestore.restore(backup, true, false, false)
        assertEquals(2, first.history?.added)
        // 第二次恢复同备份:全部判重跳过
        val second = f.backupRestore.restore(backup, true, false, false)
        assertEquals(0, second.history?.added)
        assertEquals(2, second.history?.skipped)
        assertEquals(2, f.historyStore.rows.size)
    }

    @Test
    fun `重复屏蔽规则跳过且keywords与username归一化判重`() = runBlocking {
        val f = Fixture()
        f.backupRestore.upload("/tblite/", emptyList(), sampleBlocks(), emptyPreferences())
        val backup = f.backupRestore.download("/tblite/")

        val first = f.backupRestore.restore(backup, false, true, false)
        assertEquals(1, first.blockRules?.added)

        // 同规则再恢复 → 跳过
        val second = f.backupRestore.restore(backup, false, true, false)
        assertEquals(0, second.blockRules?.added)
        assertEquals(1, second.blockRules?.skipped)

        // 入库规则 username=""(orEmpty 归一),手构"导出形态"等价规则 username=null:
        // 归一化后判重必须命中,否则 null-username 规则每次恢复都重复入库
        val equivalent = BackupJson.parse(
            """
            {"version": 1,
             "history": {"records": []},
             "blockRules": {"rules": [
               {"category": 10, "type": 0, "keywords": ["广告"], "username": null, "uid": null, "isRegex": false}
             ]}}
            """.trimIndent()
        )
        val third = f.backupRestore.restore(equivalent, false, true, false)
        assertEquals(0, third.blockRules?.added)
        assertEquals(1, third.blockRules?.skipped)
    }

    @Test
    fun `uid为空串的规则与null-uid存量规则判重命中`() = runBlocking {
        // username 归一化的同一坑:库存 null(导出省略),导入文件可能写 ""(手工编辑/其他工具导出),
        // 不归一化则 null-uid 规则每次导入都重复入库
        val f = Fixture()
        f.backupRestore.upload("/tblite/", emptyList(), sampleBlocks(), emptyPreferences())
        val backup = f.backupRestore.download("/tblite/")
        f.backupRestore.restore(backup, false, true, false)

        val emptyUid = BackupJson.parse(
            """
            {"version": 1,
             "history": {"records": []},
             "blockRules": {"rules": [
               {"category": 10, "type": 0, "keywords": ["广告"], "username": "", "uid": "", "isRegex": false}
             ]}}
            """.trimIndent()
        )

        val result = f.backupRestore.restore(emptyUid, false, true, false)

        assertEquals(0, result.blockRules?.added)
        assertEquals(1, result.blockRules?.skipped)
        assertEquals(1, f.blockStore.rows.size)
    }

    @Test
    fun `同一备份文件内的重复规则只入库一次`() = runBlocking {
        // 原本地导入的关键不变量:文件内出现重复规则时,前一条插入后即可被后续判重命中
        val f = Fixture()
        val duplicated = BackupJson.parse(
            """
            {"version": 1,
             "history": {"records": []},
             "blockRules": {"rules": [
               {"category": 10, "type": 0, "keywords": ["广告"], "username": "", "uid": null, "isRegex": false},
               {"category": 10, "type": 0, "keywords": ["广告"], "username": "", "uid": null, "isRegex": false}
             ]}}
            """.trimIndent()
        )

        val result = f.backupRestore.restore(duplicated, false, true, false)

        assertEquals(1, result.blockRules?.added)
        assertEquals(1, result.blockRules?.skipped)
        assertEquals(1, f.blockStore.rows.size)
    }

    @Test
    fun `不同记录混合新增与跳过`() = runBlocking {
        val f = Fixture()
        f.historyStore.rows += History(title = "已有", data = "1001", type = 0, timestamp = 1, count = 5)
        f.backupRestore.upload(
            "/tblite/",
            sampleHistories(), // 1001(重复) + 1002(新)
            emptyList(),
            emptyPreferences(),
        )
        val backup = f.backupRestore.download("/tblite/")

        val result = f.backupRestore.restore(backup, true, false, false)

        assertEquals(1, result.history?.added)
        assertEquals(1, result.history?.skipped)
        // 新记录保留原始 timestamp/count(与本地导入的 raw 插入语义一致)
        val added = f.historyStore.rows.first { it.data == "1002" }
        assertEquals(1694659300000L, added.timestamp)
        assertEquals(2, added.count)
    }

    // ── 恢复:设置覆盖 ────────────────────────────────────────

    @Test
    fun `设置按键覆盖本地且本地独有键不动`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit {
            it[intPreferencesKey("radius")] = 8           // 将被覆盖
            it[stringPreferencesKey("local_only")] = "keep" // 本地独有,不动
        }
        f.backupRestore.upload("/tblite/", emptyList(), emptyList(), samplePrefs())
        val backup = f.backupRestore.download("/tblite/")

        f.backupRestore.restore(backup, false, false, true)

        val prefs = f.dataStore.data.first()
        assertEquals(true, prefs[booleanPreferencesKey("dark_amoled")]) // 新键写入
        assertEquals(12, prefs[intPreferencesKey("radius")])           // 覆盖
        assertEquals("keep", prefs[stringPreferencesKey("local_only")]) // 独有键不动
    }

    @Test
    fun `恢复侧永不写入排除清单键即使备份里带了`() = runBlocking {
        val f = Fixture()
        val malicious = BackupJson.parse(
            """
            {"version": 1,
             "history": {"records": []},
             "blockRules": {"rules": []},
             "preferences": {"webdav_password_cipher": {"t": "s", "v": "ENC{evil}"}}}
            """.trimIndent()
        )

        val result = f.backupRestore.restore(malicious, false, false, true)

        assertEquals(0, result.preferencesOverwritten)
        assertFalse(f.dataStore.data.first().contains(stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)))
    }

    @Test
    fun `设置值类型恢复后类型正确`() = runBlocking {
        val f = Fixture()
        val prefs = mutablePreferencesOf(
            stringSetPreferencesKey("a_set") to setOf("x", "y"),
            longPreferencesKey("a_long") to 123456789012345L,
            floatPreferencesKey("a_float") to 2.5f,
        )
        f.backupRestore.upload("/tblite/", emptyList(), emptyList(), prefs)
        val backup = f.backupRestore.download("/tblite/")

        f.backupRestore.restore(backup, false, false, true)

        val restored = f.dataStore.data.first()
        assertEquals(setOf("x", "y"), restored[stringSetPreferencesKey("a_set")])
        assertEquals(123456789012345L, restored[longPreferencesKey("a_long")])
        assertEquals(2.5f, restored[floatPreferencesKey("a_float")])
    }

    // ── 版本守卫 ──────────────────────────────────────────────

    @Test
    fun `version高于本机支持版本时整体拒绝且零写入`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit { it[stringPreferencesKey("local")] = "untouched" }
        val future = BackupJson.parse(
            """
            {"version": ${BackupJson.CURRENT_VERSION + 1},
             "history": {"records": [{"title": "t", "data": "d", "type": 0, "timestamp": 1, "count": 1}]},
             "blockRules": {"rules": [{}]},
             "preferences": {"k": {"t": "s", "v": "v"}}}
            """.trimIndent()
        )

        val e = assertFailsWith<BackupVersionException> {
            f.backupRestore.restore(future, true, true, true)
        }
        assertEquals(BackupJson.CURRENT_VERSION + 1, e.backupVersion)
        assertEquals(BackupJson.CURRENT_VERSION, e.supportedVersion)

        // 整体拒绝:三部分零写入
        assertTrue(f.historyStore.rows.isEmpty())
        assertTrue(f.blockStore.rows.isEmpty())
        assertEquals("untouched", f.dataStore.data.first()[stringPreferencesKey("local")])
        assertFalse(f.dataStore.data.first().contains(stringPreferencesKey("k")))
    }

    @Test
    fun `version等于本机支持版本时正常恢复`() = runBlocking {
        val f = Fixture()
        val current = BackupJson.parse(
            """{"version": ${BackupJson.CURRENT_VERSION}, "history": {"records": []}, "blockRules": {"rules": []}, "preferences": {}}"""
        )
        assertEquals(BackupJson.CURRENT_VERSION, current.version)
        // 不抛即通过;空备份各部分数量为 0
        val result = f.backupRestore.restore(current, true, true, true)
        assertEquals(0, result.history?.added)
        assertEquals(0, result.blockRules?.added)
        assertEquals(0, result.preferencesOverwritten)
    }

    @Test
    fun `勾选段在备份中缺失时整体拒绝且零写入`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit { it[stringPreferencesKey("local")] = "untouched" }
        // 备份没有 history 段(旧版本/被截断):勾选浏览记录恢复必须报错而非静默 0 条
        val truncated = BackupJson.parse(
            """{"version": 1, "blockRules": {"rules": []}, "preferences": {}}"""
        )

        assertFailsWith<BackupFormatException> {
            f.backupRestore.restore(truncated, true, false, false)
        }

        assertTrue(f.historyStore.rows.isEmpty())
        assertEquals("untouched", f.dataStore.data.first()[stringPreferencesKey("local")])
    }
}
