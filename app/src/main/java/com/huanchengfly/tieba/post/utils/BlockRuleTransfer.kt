package com.huanchengfly.tieba.post.utils

import android.net.Uri
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.fromJson
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BlockRuleData(
    val category: Int = Block.CATEGORY_BLACK_LIST,
    val type: Int = Block.TYPE_KEYWORD,
    val keywords: List<String> = emptyList(),
    val username: String? = null,
    val uid: String? = null,
    val isRegex: Boolean = false,
)

data class BlockRulesFile(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<BlockRuleData> = emptyList(),
)

object BlockRuleSerializer {
    fun serialize(blocks: List<Block>): String {
        val file = BlockRulesFile(
            rules = blocks.map {
                BlockRuleData(
                    category = it.category,
                    type = it.type,
                    keywords = it.getKeywords(),
                    username = it.username,
                    uid = it.uid,
                    isRegex = it.isRegex
                )
            }
        )
        return file.toJson()
    }

    fun deserialize(json: String): List<BlockRuleData> {
        val file = json.fromJson<BlockRulesFile>() ?: throw IllegalArgumentException("invalid json")
        return file.rules
    }
}

object BlockRuleTransfer {
    suspend fun exportTo(context: android.content.Context, uri: Uri, blocks: List<Block>): Int =
        withContext(Dispatchers.IO) {
            val json = BlockRuleSerializer.serialize(blocks)
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("cannot open output stream")
            blocks.size
        }

    suspend fun importFrom(context: android.content.Context, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("cannot open input stream")

            val rules = BlockRuleSerializer.deserialize(json)
            // 可变快照：文件内出现重复规则时，前一条插入后即可被后续判重命中
            val existing = DatabaseUtil.getAllBlocks().toMutableList()
            var added = 0
            var skipped = 0
            rules.forEach { rule ->
                // 存储的 keywords 对用户类型规则为 null，导出序列化为 "[]"；
                // 双方都归一化为 List<String> 再比较，避免 null 与 "[]" 恒不相等导致重复导入。
                // username 同理：存 null 但导入入库时 orEmpty() 为 ""，双方归一化再比，
                // 否则 null-username 规则每次导入都会重复入库
                val duplicated = existing.any {
                    it.category == rule.category &&
                            it.type == rule.type &&
                            it.isRegex == rule.isRegex &&
                            it.getKeywords() == rule.keywords &&
                            it.username.orEmpty() == rule.username.orEmpty() &&
                            it.uid == rule.uid
                }
                if (duplicated) {
                    skipped++
                } else {
                    val block = Block(
                        category = rule.category,
                        type = rule.type,
                        keywords = if (rule.keywords.isEmpty()) null else rule.keywords.toJson(),
                        username = rule.username.orEmpty(),
                        uid = rule.uid,
                        isRegex = rule.isRegex
                    )
                    existing.add(BlockManager.addBlock(block))
                    added++
                }
            }
            ImportResult(added, skipped)
        }

    data class ImportResult(val added: Int, val skipped: Int)
}

data class HistoryRecordData(
    val title: String,
    val data: String,
    val type: Int,
    val timestamp: Long,
    val count: Int,
    val extras: String? = null,
    val avatar: String? = null,
    val username: String? = null,
)

data class HistoryFile(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val records: List<HistoryRecordData> = emptyList(),
)

object HistorySerializer {
    fun serialize(histories: List<History>): String {
        val file = HistoryFile(
            records = histories.map {
                HistoryRecordData(
                    title = it.title,
                    data = it.data,
                    type = it.type,
                    timestamp = it.timestamp,
                    count = it.count,
                    extras = it.extras,
                    avatar = it.avatar,
                    username = it.username
                )
            }
        )
        return file.toJson()
    }

    fun deserialize(json: String): List<HistoryRecordData> {
        val file = json.fromJson<HistoryFile>() ?: throw IllegalArgumentException("invalid json")
        return file.records
    }
}

object HistoryTransfer {
    suspend fun exportTo(context: android.content.Context, uri: Uri, histories: List<History>): Int =
        withContext(Dispatchers.IO) {
            val json = HistorySerializer.serialize(histories)
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("cannot open output stream")
            histories.size
        }

    suspend fun importFrom(context: android.content.Context, uri: Uri): HistoryTransfer.ImportResult =
        withContext(Dispatchers.IO) {
            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("cannot open input stream")

            val records = HistorySerializer.deserialize(json)
            // 去重键必须含 type：帖子 id 与数字吧名共用 data 字符串空间，
            // 只按 data 判重会把不同类型的合法记录误判为重复丢弃
            val existingDataKeys = DatabaseUtil.getAllHistoryNoLimit()
                .map { it.type to it.data }
                .toHashSet()
            var added = 0
            var skipped = 0
            records.forEach { record ->
                val key = record.type to record.data
                if (key in existingDataKeys) {
                    skipped++
                } else {
                    // 直接插入原始记录，保留原 timestamp/count；不去重键复用 upsert
                    // （upsert 会把 timestamp 重置为现在、count+1，会污染排序）
                    DatabaseUtil.upsertHistoryRaw(
                        History(
                            title = record.title,
                            data = record.data,
                            type = record.type,
                            timestamp = record.timestamp,
                            count = record.count,
                            extras = record.extras,
                            avatar = record.avatar,
                            username = record.username
                        )
                    )
                    existingDataKeys.add(key)
                    added++
                }
            }
            HistoryTransfer.ImportResult(added, skipped)
        }

    data class ImportResult(val added: Int, val skipped: Int)
}
