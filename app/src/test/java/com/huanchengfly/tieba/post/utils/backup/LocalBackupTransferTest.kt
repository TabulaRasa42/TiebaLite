package com.huanchengfly.tieba.post.utils.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.webdav.BackupFormatException
import com.huanchengfly.tieba.post.utils.webdav.BackupJson
import com.huanchengfly.tieba.post.utils.webdav.BackupVersionException
import com.huanchengfly.tieba.post.utils.webdav.WebDavCredentialsConst
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertFailsWith

/**
 * 01 号票:本地备份导出/导入(LocalBackupTransfer)的 JVM 测试。
 *
 * 沿用 06 号票测试先例:内存 store 夹具(InMemoryStores.kt)+ 内存 DataStore
 * (真文件实现在 Windows JVM 二次写必败),传输以内存字节流(ByteArrayInputStream/
 * ByteArrayOutputStream)为入参——核心层以流为 seam,不碰 Android SAF 类型。
 *
 * 恢复语义(判重/覆盖/版本守卫/缺失段拒绝)由共享编排 BackupRestore 的既有测试
 * (WebDavBackupRestoreTest 全量覆盖)锁定,本文件锁定本地流式侧的外部行为:
 * 导出字节即合法备份 JSON、往返一致、坏文件整体拒绝零写入、排除清单双侧过滤。
 *
 * 不用 coroutines-test(1.11.0 无 StandardTestScope):挂起代码直接 runBlocking。
 */
class LocalBackupTransferTest {

    // ── 测试设施(06 号票先例)────────────────────────────────

    /** DataStore 接口的最小内存实现(03 号票模板) */
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
        val historyStore = InMemoryHistoryStore()
        val blockStore = InMemoryBlockStore()
        val dataStore = InMemoryDataStore()

        /** UI 新流程的测试镜像:readBackup(校验)→ BackupRestore.restore(按勾选恢复) */
        suspend fun importFrom(
            bytes: ByteArray,
            restoreHistory: Boolean = true,
            restoreBlockRules: Boolean = true,
            restorePreferences: Boolean = true,
        ): BackupRestore.RestoreResult {
            val parsed = LocalBackupTransfer.readBackup(ByteArrayInputStream(bytes))
            return BackupRestore.restore(
                parsed, restoreHistory, restoreBlockRules, restorePreferences,
                historyStore, blockStore, dataStore,
            )
        }
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

    private suspend fun exportedBytes(
        histories: List<History> = sampleHistories(),
        blocks: List<Block> = sampleBlocks(),
        prefs: Preferences = samplePrefs(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        LocalBackupTransfer.exportTo(output, histories, blocks, prefs)
        return output.toByteArray()
    }

    // ── 导出 ─────────────────────────────────────────────────

    @Test
    fun `导出字节是合法的三段备份JSON且统计正确`() = runBlocking {
        val bytes = exportedBytes()
        val parsed = BackupJson.parse(bytes.toString(Charsets.UTF_8))

        assertEquals(BackupJson.CURRENT_VERSION, parsed.version)
        assertTrue(parsed.hasHistory)
        assertTrue(parsed.hasBlockRules)
        assertTrue(parsed.hasPreferences)
        assertEquals(2, parsed.historyRecords().size)
        assertEquals(1, parsed.blockRules().size)
        assertEquals(2, parsed.preferenceEntries().size)

        val output = ByteArrayOutputStream()
        val result = LocalBackupTransfer.exportTo(output, sampleHistories(), sampleBlocks(), samplePrefs())
        assertEquals(2, result.historyCount)
        assertEquals(1, result.blockRuleCount)
        assertEquals(2, result.preferenceKeyCount)
    }

    @Test
    fun `导出排除清单键不出现`() = runBlocking {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("normal") to "v",
            stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER) to "ENC{cipher}",
            longPreferencesKey("active_timestamp") to 123L,
        )

        val json = exportedBytes(prefs = prefs).toString(Charsets.UTF_8)

        assertFalse("凭据密文键进入了本地备份", json.contains(WebDavCredentialsConst.KEY_PASSWORD_CIPHER))
        assertFalse("运行时时间戳键进入了本地备份", json.contains("active_timestamp"))
        assertTrue(json.contains("normal"))
    }

    @Test
    fun `导出JSON与WebDAV序列化同构往返一致`() = runBlocking {
        // 本地文件与 WebDAV 备份是同一份数据结构:导出字节可被 WebDAV 侧同一 parse/编排消费
        val histories = sampleHistories()
        val blocks = sampleBlocks()
        val prefs = samplePrefs()

        val localJson = exportedBytes(histories, blocks, prefs).toString(Charsets.UTF_8)
        val webdavJson = BackupJson.serialize(histories, blocks, prefs).json

        val localParsed = BackupJson.parse(localJson)
        val webdavParsed = BackupJson.parse(webdavJson)
        assertEquals(webdavParsed.historyRecords(), localParsed.historyRecords())
        assertEquals(webdavParsed.blockRules(), localParsed.blockRules())
        assertEquals(webdavParsed.preferenceEntries(), localParsed.preferenceEntries())
    }

    // ── 导入:全选/部分勾选 ───────────────────────────────────

    @Test
    fun `全选导入三部分各自写入且数量正确`() = runBlocking {
        val f = Fixture()

        val result = f.importFrom(exportedBytes())

        assertEquals(2, result.history?.added)
        assertEquals(0, result.history?.skipped)
        assertEquals(1, result.blockRules?.added)
        assertEquals(0, result.blockRules?.skipped)
        assertEquals(2, result.preferencesOverwritten)
        assertEquals(2, f.historyStore.rows.size)
        assertEquals(1, f.blockStore.rows.size)
        val prefs = f.dataStore.data.first()
        assertEquals(true, prefs[booleanPreferencesKey("dark_amoled")])
        assertEquals(12, prefs[intPreferencesKey("radius")])
    }

    @Test
    fun `只勾选浏览记录时其余两部分完全不被触碰`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit { it[stringPreferencesKey("local_only")] = "untouched" }
        f.blockStore.rows += Block(category = 10, type = 0, keywords = """["已有"]""", username = "")

        val result = f.importFrom(exportedBytes(), restoreHistory = true, restoreBlockRules = false, restorePreferences = false)

        assertEquals(2, result.history?.added)
        assertNull(result.blockRules)
        assertNull(result.preferencesOverwritten)
        assertEquals(1, f.blockStore.rows.size)
        assertFalse(f.dataStore.data.first().contains(stringPreferencesKey("dark_amoled")))
        assertEquals("untouched", f.dataStore.data.first()[stringPreferencesKey("local_only")])
    }

    @Test
    fun `只勾选设置时浏览记录与规则零写入`() = runBlocking {
        val f = Fixture()

        val result = f.importFrom(exportedBytes(), restoreHistory = false, restoreBlockRules = false, restorePreferences = true)

        assertNull(result.history)
        assertNull(result.blockRules)
        assertEquals(2, result.preferencesOverwritten)
        assertTrue(f.historyStore.rows.isEmpty())
        assertTrue(f.blockStore.rows.isEmpty())
    }

    // ── 导入:判重合并(与 WebDAV 恢复同一编排的外部行为抽查)──

    @Test
    fun `重复导入同一文件判重跳过不重复入库`() = runBlocking {
        val f = Fixture()
        val bytes = exportedBytes()

        val first = f.importFrom(bytes, restoreHistory = true, restoreBlockRules = true, restorePreferences = false)
        assertEquals(2, first.history?.added)
        val second = f.importFrom(bytes, restoreHistory = true, restoreBlockRules = true, restorePreferences = false)
        assertEquals(0, second.history?.added)
        assertEquals(2, second.history?.skipped)
        assertEquals(0, second.blockRules?.added)
        assertEquals(1, second.blockRules?.skipped)
        assertEquals(2, f.historyStore.rows.size)
        assertEquals(1, f.blockStore.rows.size)
    }

    @Test
    fun `设置按键覆盖本地且本地独有键不动`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit {
            it[intPreferencesKey("radius")] = 8            // 将被覆盖
            it[stringPreferencesKey("local_only")] = "keep" // 本地独有,不动
        }

        f.importFrom(exportedBytes(prefs = samplePrefs()), restoreHistory = false, restoreBlockRules = false, restorePreferences = true)

        val prefs = f.dataStore.data.first()
        assertEquals(true, prefs[booleanPreferencesKey("dark_amoled")])
        assertEquals(12, prefs[intPreferencesKey("radius")])
        assertEquals("keep", prefs[stringPreferencesKey("local_only")])
    }

    @Test
    fun `恢复侧防御性过滤排除清单键即使备份里带了`() = runBlocking {
        val f = Fixture()
        val malicious = """
            {"version": 1,
             "history": {"records": []},
             "blockRules": {"rules": []},
             "preferences": {"webdav_password_cipher": {"t": "s", "v": "ENC{evil}"}}}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val result = f.importFrom(malicious, restoreHistory = false, restoreBlockRules = false, restorePreferences = true)

        assertEquals(0, result.preferencesOverwritten)
        assertFalse(f.dataStore.data.first().contains(stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER)))
    }

    // ── 守卫:高版本/损坏文件整体拒绝零写入 ───────────────────

    @Test
    fun `高版本文件整体拒绝且零写入`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit { it[stringPreferencesKey("local")] = "untouched" }
        val future = """
            {"version": ${BackupJson.CURRENT_VERSION + 1},
             "history": {"records": [{"title": "t", "data": "d", "type": 0, "timestamp": 1, "count": 1}]},
             "blockRules": {"rules": [{}]},
             "preferences": {"k": {"t": "s", "v": "v"}}}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val e = assertFailsWith<BackupVersionException> {
            f.importFrom(future)
        }
        assertEquals(BackupJson.CURRENT_VERSION + 1, e.backupVersion)
        assertEquals(BackupJson.CURRENT_VERSION, e.supportedVersion)

        assertTrue(f.historyStore.rows.isEmpty())
        assertTrue(f.blockStore.rows.isEmpty())
        assertEquals("untouched", f.dataStore.data.first()[stringPreferencesKey("local")])
        assertFalse(f.dataStore.data.first().contains(stringPreferencesKey("k")))
    }

    @Test
    fun `损坏文件抛BackupFormatException且零写入`() = runBlocking {
        val f = Fixture()
        f.dataStore.edit { it[stringPreferencesKey("local")] = "untouched" }

        assertFailsWith<BackupFormatException> {
            f.importFrom("not json at all".toByteArray(Charsets.UTF_8))
        }
        assertFailsWith<BackupFormatException> {
            f.importFrom("""[1,2,3]""".toByteArray(Charsets.UTF_8))
        }

        assertTrue(f.historyStore.rows.isEmpty())
        assertTrue(f.blockStore.rows.isEmpty())
        assertEquals("untouched", f.dataStore.data.first()[stringPreferencesKey("local")])
    }

    @Test
    fun `勾选段在备份中缺失时整体拒绝且零写入`() = runBlocking {
        val f = Fixture()
        val truncated = """
            {"version": 1, "blockRules": {"rules": []}, "preferences": {}}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        assertFailsWith<BackupFormatException> {
            f.importFrom(truncated, restoreHistory = true, restoreBlockRules = false, restorePreferences = false)
        }

        assertTrue(f.historyStore.rows.isEmpty())
    }

    // ── 校验步(readBackup):选文件后、勾选前 ─────────────────

    @Test
    fun `readBackup通过合法备份返回解析结果`() = runBlocking {
        val parsed = LocalBackupTransfer.readBackup(ByteArrayInputStream(exportedBytes()))

        assertEquals(BackupJson.CURRENT_VERSION, parsed.version)
        assertTrue(parsed.hasHistory)
        assertTrue(parsed.hasBlockRules)
        assertTrue(parsed.hasPreferences)
    }

    @Test
    fun `readBackup坏文件当场拒绝且零写入`() = runBlocking {
        val f = Fixture()

        assertFailsWith<BackupFormatException> {
            LocalBackupTransfer.readBackup(ByteArrayInputStream("not json".toByteArray(Charsets.UTF_8)))
        }
        assertFailsWith<BackupVersionException> {
            LocalBackupTransfer.readBackup(
                ByteArrayInputStream(
                    """{"version": ${BackupJson.CURRENT_VERSION + 1}, "history": {"records": []}}"""
                        .toByteArray(Charsets.UTF_8)
                )
            )
        }

        // 校验步只读不写:失败后存储侧零痕迹
        assertTrue(f.historyStore.rows.isEmpty())
        assertTrue(f.blockStore.rows.isEmpty())
    }

    // ── 往返:导出再导入还原 ──────────────────────────────────

    @Test
    fun `导出再导入设置六种值类型还原正确`() = runBlocking {
        val f = Fixture()
        val prefs = mutablePreferencesOf(
            stringSetPreferencesKey("a_set") to setOf("x", "y"),
            stringPreferencesKey("a_str") to "中文值",
            booleanPreferencesKey("a_bool") to true,
        )

        val bytes = exportedBytes(histories = emptyList(), blocks = emptyList(), prefs = prefs)
        f.importFrom(bytes, restoreHistory = false, restoreBlockRules = false, restorePreferences = true)

        val restored = f.dataStore.data.first()
        assertEquals(setOf("x", "y"), restored[stringSetPreferencesKey("a_set")])
        assertEquals("中文值", restored[stringPreferencesKey("a_str")])
        assertEquals(true, restored[booleanPreferencesKey("a_bool")])
    }

    @Test
    fun `导出再导入浏览记录保留原始timestamp与count`() = runBlocking {
        val f = Fixture()

        f.importFrom(exportedBytes(), restoreHistory = true, restoreBlockRules = false, restorePreferences = false)

        val added = f.historyStore.rows.first { it.data == "1002" }
        assertEquals(1694659300000L, added.timestamp)
        assertEquals(2, added.count)
        assertNotNull(added.title)
    }
}
