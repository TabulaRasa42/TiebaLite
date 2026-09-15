package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.HistorySerializer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * 02 号票:备份打包/解析(BackupJson)的 JVM 测试。
 * 只测外部行为:三部分结构、version 字段、排除清单双侧过滤、类型往返、格式守卫。
 */
class BackupJsonTest {

    // ── 样例数据 ──────────────────────────────────────────────

    private fun sampleHistories() = listOf(
        History(title = "帖子A", data = "1001", type = 0, timestamp = 1694659200000, count = 1),
        History(title = "吧B", data = "lol", type = 1, timestamp = 1694659300000, count = 2, username = "user"),
    )

    private fun sampleBlocks() = listOf(
        Block(category = 10, type = 0, keywords = """["广告"]""", username = "", uid = null, isRegex = false),
        Block(category = 11, type = 1, keywords = null, username = "某人", uid = "123", isRegex = false),
    )

    private fun samplePrefs(): Preferences = mutablePreferencesOf(
        booleanPreferencesKey("a_bool") to true,
        intPreferencesKey("a_int") to 3,
        longPreferencesKey("a_long") to 9999999999L,
        floatPreferencesKey("a_float") to 1.5f,
        stringPreferencesKey("a_str") to "中文值",
        stringSetPreferencesKey("a_set") to setOf("x", "y"),
    )

    // ── 打包结构 ──────────────────────────────────────────────

    @Test
    fun `打包产出顶层version与三部分独立嵌套`() {
        val serialized = BackupJson.serialize(sampleHistories(), sampleBlocks(), samplePrefs())
        val parsed = BackupJson.parse(serialized.json)

        assertEquals(BackupJson.CURRENT_VERSION, parsed.version)
        assertTrue(parsed.hasHistory)
        assertTrue(parsed.hasBlockRules)
        assertTrue(parsed.hasPreferences)

        // 三部分条目与输入一一对应(结构与既有 serializer 的条目格式一致)
        val records = parsed.historyRecords()
        assertEquals(2, records.size)
        assertEquals("帖子A", records[0].title)
        assertEquals(0, records[0].type)
        assertEquals(1694659200000L, records[0].timestamp)

        val rules = parsed.blockRules()
        assertEquals(2, rules.size)
        assertEquals(listOf("广告"), rules[0].keywords)
        assertEquals("某人", rules[1].username)

        // 设置键数统计与解析结果一致
        assertEquals(parsed.preferenceEntries().size, serialized.preferenceKeyCount)
    }

    @Test
    fun `打包条目格式与本地导入导出serializer的records一致`() {
        // 备份嵌套的 records 与 HistorySerializer 独立导出的 records 逐条一致
        val histories = sampleHistories()
        val backupJson = BackupJson.serialize(histories, emptyList(), emptyPreferences()).json
        val standaloneJson = HistorySerializer.serialize(histories)

        val backupRecords = BackupJson.parse(backupJson).historyRecords()
        val standaloneRecords = HistorySerializer.deserialize(standaloneJson)
        assertEquals(standaloneRecords, backupRecords)
    }

    // ── 排除清单 ──────────────────────────────────────────────

    @Test
    fun `排除清单包含webdav四键与设备特定状态键`() {
        // 03 号票 review 跨票设计提醒:webdav_* 四键必须排除,否则跨设备恢复会
        // 打坏本机 Keystore 凭据(B 设备密钥独立,密文不可解 → NeedsPassword)
        val expected = setOf(
            WebDavCredentialsConst.KEY_SERVER_URL,
            WebDavCredentialsConst.KEY_USERNAME,
            WebDavCredentialsConst.KEY_PASSWORD_CIPHER,
            WebDavCredentialsConst.KEY_REMOTE_PATH,
            "ignoreBatteryOptimizationsDialog", // 电池优化弹窗标记
            "active_timestamp",                 // 运行时时间戳
            "userLikeLastRequestUnix",
            "client_id",
            "sample_id",
            "baidu_id",
            "translucent_theme_background_path",
        )
        assertEquals(expected, BackupJson.EXCLUDED_PREFERENCE_KEYS)
    }

    @Test
    fun `排除清单键不进备份JSON`() = runBlocking {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("normal_key") to "keep",
            stringPreferencesKey(WebDavCredentialsConst.KEY_PASSWORD_CIPHER) to "ENC{cipher}",
            longPreferencesKey("active_timestamp") to 123L,
        )

        val json = BackupJson.serialize(emptyList(), emptyList(), prefs).json

        assertFalse("凭据密文键进入了备份 JSON", json.contains(WebDavCredentialsConst.KEY_PASSWORD_CIPHER))
        assertFalse("运行时时间戳键进入了备份 JSON", json.contains("active_timestamp"))
        assertTrue(json.contains("normal_key"))
    }

    @Test
    fun `恢复侧防御性过滤排除清单键`() {
        // 手构/旧版本备份可能带排除键:恢复侧必须再滤一遍
        val backupJson = """
            {
              "version": 1,
              "history": {"records": []},
              "blockRules": {"rules": []},
              "preferences": {
                "normal_key": {"t": "s", "v": "keep"},
                "webdav_password_cipher": {"t": "s", "v": "ENC{cipher}"},
                "ignoreBatteryOptimizationsDialog": {"t": "b", "v": true}
              }
            }
        """.trimIndent()

        val entries = BackupJson.parse(backupJson).preferenceEntries()

        assertEquals(1, entries.size)
        assertEquals("normal_key", entries[0].first.name)
    }

    // ── 设置键值类型往返 ──────────────────────────────────────

    @Test
    fun `设置六种值类型打包后可还原`() {
        val json = BackupJson.serialize(emptyList(), emptyList(), samplePrefs()).json
        val entries = BackupJson.parse(json).preferenceEntries().associate { it.first.name to it.second }

        assertEquals(true, entries["a_bool"])
        assertEquals(3, entries["a_int"])
        assertEquals(9999999999L, entries["a_long"])
        assertEquals(1.5f, entries["a_float"] as Float)
        assertEquals("中文值", entries["a_str"])
        assertEquals(setOf("x", "y"), entries["a_set"])
    }

    @Test
    fun `未知类型标签的条目跳过`() {
        val backupJson = """
            {
              "version": 1,
              "history": {"records": []},
              "blockRules": {"rules": []},
              "preferences": {
                "known": {"t": "s", "v": "ok"},
                "future": {"t": "newtype", "v": 1}
              }
            }
        """.trimIndent()

        val entries = BackupJson.parse(backupJson).preferenceEntries()

        assertEquals(1, entries.size)
        assertEquals("known", entries[0].first.name)
    }

    // ── 格式守卫 ──────────────────────────────────────────────

    @Test
    fun `parse非JSON文本抛BackupFormatException`() {
        assertFailsWith<BackupFormatException> { BackupJson.parse("not json at all") }
    }

    @Test
    fun `parse非JSON对象抛BackupFormatException`() {
        assertFailsWith<BackupFormatException> { BackupJson.parse("[1,2,3]") }
    }

    @Test
    fun `parse缺version字段抛BackupFormatException`() {
        assertFailsWith<BackupFormatException> { BackupJson.parse("""{"history": {}}""") }
    }

    @Test
    fun `parse非法version值抛BackupFormatException`() {
        assertFailsWith<BackupFormatException> {
            BackupJson.parse("""{"version": "abc"}""")
        }
        assertFailsWith<BackupFormatException> {
            BackupJson.parse("""{"version": [1]}""")
        }
        assertFailsWith<BackupFormatException> {
            BackupJson.parse("""{"version": true}""")
        }
    }

    @Test
    fun `parse合法备份返回带version的解析结果`() {
        val parsed = BackupJson.parse("""{"version": 1, "history": {"records": []}}""")
        assertEquals(1, parsed.version)
        assertNotNull(parsed)
    }

    @Test
    fun `parse零或负version抛BackupFormatException`() {
        assertFailsWith<BackupFormatException> { BackupJson.parse("""{"version": 0}""") }
        assertFailsWith<BackupFormatException> { BackupJson.parse("""{"version": -1}""") }
    }

    @Test
    fun `parse非整数version抛BackupFormatException不被截断`() {
        // Gson asInt 会把 1.5 截断为 1:必须比较原值拦截,防过渡格式被当 v1 恢复
        assertFailsWith<BackupFormatException> { BackupJson.parse("""{"version": 1.5}""") }
        assertFailsWith<BackupFormatException> { BackupJson.parse("""{"version": 0.5}""") }
    }

    @Test
    fun `坏设置条目跳过不抛异常`() {
        val backupJson = """
            {
              "version": 1,
              "history": {"records": []},
              "blockRules": {"rules": []},
              "preferences": {
                "good": {"t": "s", "v": "ok"},
                "bad_int": {"t": "i", "v": "abc"},
                "bad_string": {"t": "s", "v": {"nested": 1}},
                "missing_value": {"t": "s"},
                "unknown_type": {"t": "future", "v": 1}
              }
            }
        """.trimIndent()

        val entries = BackupJson.parse(backupJson).preferenceEntries()

        // 只有 good 一条可还原;四条坏条目静默跳过,不抛任何异常
        assertEquals(1, entries.size)
        assertEquals("good", entries[0].first.name)
    }

    @Test
    fun `坏历史记录条目跳过不抛异常`() {
        val backupJson = """
            {
              "version": 1,
              "history": {"records": [
                {"title": "好", "data": "1001", "type": 0, "timestamp": 1, "count": 1},
                {"title": "坏", "data": "1002", "type": "not-a-number", "timestamp": 1, "count": 1}
              ]},
              "blockRules": {"rules": []}
            }
        """.trimIndent()

        val records = BackupJson.parse(backupJson).historyRecords()

        assertEquals(1, records.size)
        assertEquals("好", records[0].title)
    }
}
