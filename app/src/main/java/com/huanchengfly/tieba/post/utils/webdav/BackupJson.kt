package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.GsonUtil
import com.huanchengfly.tieba.post.utils.ThemeUtil
import com.huanchengfly.tieba.post.utils.backup.BlockRuleData
import com.huanchengfly.tieba.post.utils.backup.HistoryRecordData
import com.huanchengfly.tieba.post.utils.backup.toBlockRuleData
import com.huanchengfly.tieba.post.utils.backup.toHistoryRecordData

/**
 * 备份文件(术语见 CONTEXT.md)的打包与解析:单个 JSON、固定文件名 [FILE_NAME],
 * 顶层 [CURRENT_VERSION] 版本号,三部分(浏览记录/屏蔽规则/软件设置)各自独立嵌套
 * (为部分恢复服务):
 *
 * ```json
 * {
 *   "version": 1,
 *   "exportedAt": 1694659200000,
 *   "history":     { "records": [ HistoryRecordData... ] },
 *   "blockRules":  { "rules":   [ BlockRuleData... ] },
 *   "preferences": { "键名": { "t": "b|i|l|f|s|ss", "v": 值 } }
 * }
 * ```
 *
 * 浏览记录/屏蔽规则条目复用 [HistoryRecordData]/[BlockRuleData] 的数据格式;
 * 软件设置键值带类型标签,恢复时按标签还原 Preferences.Key 与值。
 *
 * 软件设置经 [EXCLUDED_PREFERENCE_KEYS] 双侧过滤:打包侧不导出设备特定键,
 * 恢复侧再滤一遍(防御旧版本/手构备份把设备特定键写回本机)。
 */
object BackupJson {

    /** 本机支持的备份格式版本;恢复 version 大于此值整体拒绝(见 [BackupVersionException]) */
    const val CURRENT_VERSION = 1

    /** 远端固定文件名(相对远程路径目录) */
    const val FILE_NAME = "tblite_backup.json"

    private const val FIELD_VERSION = "version"
    private const val FIELD_EXPORTED_AT = "exportedAt"
    private const val FIELD_HISTORY = "history"
    private const val FIELD_RECORDS = "records"
    private const val FIELD_BLOCK_RULES = "blockRules"
    private const val FIELD_RULES = "rules"
    private const val FIELD_PREFERENCES = "preferences"
    private const val FIELD_TYPE = "t"
    private const val FIELD_VALUE = "v"

    // preferences 值类型标签
    private const val TYPE_BOOLEAN = "b"
    private const val TYPE_INT = "i"
    private const val TYPE_LONG = "l"
    private const val TYPE_FLOAT = "f"
    private const val TYPE_STRING = "s"
    private const val TYPE_STRING_SET = "ss"

    /**
     * 设备特定状态键排除清单:不进备份 JSON,恢复时也永不写回本机(活文档,
     * 发现新的设备特定键就补进来)。键名尽量引用定义处常量防漂移。
     */
    val EXCLUDED_PREFERENCE_KEYS: Set<String> = setOf(
        // ── 设备运行时状态 ──
        // 电池优化弹窗"不再提醒"标记(AppPreferencesUtils.ignoreBatteryOptimizationsDialog):
        // 设备省电策略相关,换机后无意义
        "ignoreBatteryOptimizationsDialog",
        // ClientUtils 运行时时间戳(定义处 ClientUtils.ACTIVE_TIMESTAMP 为 private;
        // 同键名常量另见 HttpConstant.Param.ACTIVE_TIMESTAMP)
        "active_timestamp",                 // 每台设备各自计时
        // 上次请求时间戳(AppPreferencesUtils.userLikeLastRequestUnix):运行时状态,非用户偏好
        "userLikeLastRequestUnix",

        // ── 设备/安装标识(ClientUtils,服务端下发;定义处为 private 常量)──
        // 安装级标识:跨设备覆盖会混淆服务端统计
        "client_id",
        "sample_id",
        "baidu_id",

        // ── WebDAV 凭据四键(WebDavCredentialsConst)──
        // Keystore 密钥设备独立,A 设备的密文在 B 设备不可解;覆盖会把 B 上正常
        // 工作的凭据打坏成 NeedsPassword(03 号票 review 跨票设计提醒)
        WebDavCredentialsConst.KEY_SERVER_URL,
        WebDavCredentialsConst.KEY_USERNAME,
        WebDavCredentialsConst.KEY_PASSWORD_CIPHER,
        WebDavCredentialsConst.KEY_REMOTE_PATH,

        // ── 本机文件路径 ──
        // 半透明主题背景图(ThemeUtil.KEY_TRANSLUCENT_THEME_BACKGROUND_PATH):
        // 指向本机文件,其他设备上不存在
        ThemeUtil.KEY_TRANSLUCENT_THEME_BACKGROUND_PATH,
    )

    /**
     * 打包三部分为备份 JSON。数据由调用方收集(05 号票从数据库/DataStore 拉取);
     * 排除清单键不写入。
     *
     * @return 备份 JSON 与其中(过滤排除清单后的)设置键数——调用方统计上传结果
     *   无需再 parse 一遍整个文件
     */
    fun serialize(histories: List<History>, blocks: List<Block>, preferences: Preferences): SerializedBackup {
        // 条目格式即 HistoryRecordData/BlockRuleData 的 Gson 形态(本地备份与
        // WebDAV 备份同一份数据结构),直接构建 JsonArray,不经字符串序列化往返
        val gson = GsonUtil.getGson()
        val historyRecords = JsonArray().apply {
            histories.map { it.toHistoryRecordData() }.forEach { add(gson.toJsonTree(it)) }
        }
        val blockRules = JsonArray().apply {
            blocks.map { it.toBlockRuleData() }.forEach { add(gson.toJsonTree(it)) }
        }

        val preferencesJson = preferenceEntriesToJson(preferences)
        val json = JsonObject().apply {
            addProperty(FIELD_VERSION, CURRENT_VERSION)
            addProperty(FIELD_EXPORTED_AT, System.currentTimeMillis())
            add(FIELD_HISTORY, JsonObject().apply { add(FIELD_RECORDS, historyRecords) })
            add(FIELD_BLOCK_RULES, JsonObject().apply { add(FIELD_RULES, blockRules) })
            add(FIELD_PREFERENCES, preferencesJson)
        }.toString()
        return SerializedBackup(json, preferencesJson.size())
    }

    /** [serialize] 的结果:备份 JSON + 其中的设置键数 */
    class SerializedBackup(val json: String, val preferenceKeyCount: Int)

    /**
     * 解析备份 JSON。仅做结构级守卫:非 JSON / 非 JSON 对象 / 缺 version 字段
     * 均抛 [BackupFormatException]。
     */
    fun parse(json: String): ParsedBackup {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw BackupFormatException("backup file is not valid json", e)
        }
        if (!root.isJsonObject) {
            throw BackupFormatException("backup file is not a json object")
        }
        val obj = root.asJsonObject
        val version = (obj.get(FIELD_VERSION) as? JsonPrimitive)?.let {
            // 字符串数字抛 NumberFormatException、布尔原语抛 ClassCastException,都算非法 version
            try {
                // 守卫整数性:Gson 的 asInt 会把 1.5 截断为 1,比较原值拦截非整数,
                // 避免过渡格式被静默当作 v1 恢复
                val asInt = it.asInt
                if (it.asDouble != asInt.toDouble()) null else asInt
            } catch (_: RuntimeException) {
                null
            }
        } ?: throw BackupFormatException("backup file missing or invalid \"$FIELD_VERSION\" field")
        // 守卫下界:0/负数不是合法备份版本
        if (version <= 0) {
            throw BackupFormatException("backup file has invalid \"$FIELD_VERSION\" value: $version")
        }
        return ParsedBackup(version, obj)
    }

    class ParsedBackup internal constructor(
        val version: Int,
        private val root: JsonObject,
    ) {
        val hasHistory: Boolean get() = root.has(FIELD_HISTORY)
        val hasBlockRules: Boolean get() = root.has(FIELD_BLOCK_RULES)
        val hasPreferences: Boolean get() = root.has(FIELD_PREFERENCES)

        fun historyRecords(): List<HistoryRecordData> {
            val records = (root.get(FIELD_HISTORY) as? JsonObject)
                ?.get(FIELD_RECORDS) as? JsonArray
                ?: return emptyList()
            // 逐条防御:损坏条目跳过,不因单个坏记录中断整体恢复。
            // 两类坏条目:类型不符 Gson 反序列化抛 RuntimeException;缺/显式 null 的
            // title/data 不抛——HistoryRecordData 无无参构造,Gson 走 Unsafe 实例化,
            // 非空 Kotlin 字段被静默注成 null(绕过语言检查),不在此拦下就会拖到
            // 恢复入库构建 History 时才 NPE,恢复中途夭折留下部分写入。
            return records.mapNotNull { element ->
                try {
                    GsonUtil.getGson().fromJson(element, HistoryRecordData::class.java)?.takeIf {
                        it.title != null && it.data != null
                    }
                } catch (_: RuntimeException) {
                    null
                }
            }
        }

        fun blockRules(): List<BlockRuleData> {
            val rules = (root.get(FIELD_BLOCK_RULES) as? JsonObject)
                ?.get(FIELD_RULES) as? JsonArray
                ?: return emptyList()
            return rules.mapNotNull { element ->
                try {
                    GsonUtil.getGson().fromJson(element, BlockRuleData::class.java)
                } catch (_: RuntimeException) {
                    null
                }
            }
        }

        /**
         * 备份中的设置键值对(已按 [EXCLUDED_PREFERENCE_KEYS] 过滤),按类型标签
         * 还原为 Preferences.Key + 值。坏条目(类型标签未知、值类型不符、字段缺失)
         * 统一跳过——恢复侧逐条防御,绝不因单个坏条目抛未声明异常或中断整体恢复。
         */
        fun preferenceEntries(): List<Pair<Preferences.Key<*>, Any>> {
            val prefsObj = (root.get(FIELD_PREFERENCES) as? JsonObject) ?: return emptyList()
            val entries = mutableListOf<Pair<Preferences.Key<*>, Any>>()
            for ((name, element) in prefsObj.entrySet()) {
                if (name in EXCLUDED_PREFERENCE_KEYS) continue
                val entry = element as? JsonObject ?: continue
                val value = entry.get(FIELD_VALUE) ?: continue
                try {
                    when ((entry.get(FIELD_TYPE) as? JsonPrimitive)?.asString) {
                        TYPE_BOOLEAN -> entries.add(Pair(booleanPreferencesKey(name), value.asBoolean))
                        TYPE_INT -> entries.add(Pair(intPreferencesKey(name), value.asInt))
                        TYPE_LONG -> entries.add(Pair(longPreferencesKey(name), value.asLong))
                        TYPE_FLOAT -> entries.add(Pair(floatPreferencesKey(name), value.asFloat))
                        TYPE_STRING -> entries.add(Pair(stringPreferencesKey(name), value.asString))
                        TYPE_STRING_SET -> {
                            val array = value as? JsonArray
                            if (array != null) {
                                entries.add(Pair(stringSetPreferencesKey(name), array.map { it.asString }.toSet()))
                            }
                        }
                        // 未知类型标签:跳过(版本守卫已拦住新版本备份,此处为手构文件防御)
                        else -> {}
                    }
                } catch (_: RuntimeException) {
                    // 值与标签不符(如 t=i 但 v="abc" 抛 NumberFormatException、
                    // t=s 但 v=对象抛 IllegalStateException):跳过坏条目,不中断恢复
                }
            }
            return entries
        }
    }

    // ── preferences 序列化 ─────────────────────────────────────

    private fun preferenceEntriesToJson(preferences: Preferences): JsonObject {
        val obj = JsonObject()
        preferences.asMap().forEach { (key, value) ->
            if (key.name in EXCLUDED_PREFERENCE_KEYS) return@forEach
            when (value) {
                is Boolean -> obj.add(key.name, typedEntry(TYPE_BOOLEAN) { addProperty(FIELD_VALUE, value) })
                is Int -> obj.add(key.name, typedEntry(TYPE_INT) { addProperty(FIELD_VALUE, value) })
                is Long -> obj.add(key.name, typedEntry(TYPE_LONG) { addProperty(FIELD_VALUE, value) })
                is Float -> obj.add(key.name, typedEntry(TYPE_FLOAT) { addProperty(FIELD_VALUE, value) })
                is String -> obj.add(key.name, typedEntry(TYPE_STRING) { addProperty(FIELD_VALUE, value) })
                is Set<*> -> obj.add(
                    key.name,
                    typedEntry(TYPE_STRING_SET) {
                        add(
                            FIELD_VALUE,
                            JsonArray().apply { value.forEach { add(JsonPrimitive(it as String)) } }
                        )
                    },
                )
                // 未支持的值类型(如 ByteArray):跳过,不因单个键阻断整个备份
                else -> {}
            }
        }
        return obj
    }

    private inline fun typedEntry(type: String, value: JsonObject.() -> Unit): JsonObject =
        JsonObject().apply {
            addProperty(FIELD_TYPE, type)
            value()
        }
}

/** 备份文件损坏或不是备份文件(非 JSON / 非 JSON 对象 / 缺 version 字段) */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 备份版本高于本机支持版本:整体拒绝,禁止部分写入(spec:版本不兼容时不产生脏数据) */
class BackupVersionException(val backupVersion: Int, val supportedVersion: Int) :
    Exception("backup version $backupVersion is newer than supported version $supportedVersion")
