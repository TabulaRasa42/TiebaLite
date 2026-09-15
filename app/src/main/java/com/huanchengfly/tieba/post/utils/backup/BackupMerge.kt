package com.huanchengfly.tieba.post.utils.backup

import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.toJson
import com.huanchengfly.tieba.post.utils.BlockRuleData
import com.huanchengfly.tieba.post.utils.HistoryRecordData

/**
 * 备份/导入共用的数据写入 seam:判重合并逻辑([mergeHistories]/[mergeBlockRules])
 * 经此接口读写存储,不依赖 Android 环境。生产实现绑定 DatabaseUtil/BlockManager,
 * JVM 测试注入内存 fake。
 */
interface HistoryStore {
    suspend fun all(): List<History>
    suspend fun insertRaw(history: History)
}

interface BlockStore {
    suspend fun all(): List<Block>
    suspend fun add(block: Block)
}

/** 导入判重合并的结果数量(added/skipped 语义与现有本地导入一致) */
data class MergeResult(val added: Int, val skipped: Int)

/**
 * 浏览记录 + 屏蔽规则"合并 + 判重"的单一来源:
 * WebDAV 备份恢复与本地文件导入(BlockRuleTransfer/HistoryTransfer.importFrom)
 * 共用同一份实现,保证判重行为字面一致(spec 决策)。
 */
object BackupMerge {

    /**
     * 浏览记录合并:按 (type, data) 判重跳过,新记录保留原始 timestamp/count 直插。
     * 可变快照内追加,文件内出现重复记录时后续条目同样被命中。
     */
    suspend fun mergeHistories(records: List<HistoryRecordData>, store: HistoryStore): MergeResult {
        // 去重键必须含 type:帖子 id 与数字吧名共用 data 字符串空间,
        // 只按 data 判重会把不同类型的合法记录误判为重复丢弃
        val existingKeys = store.all().map { it.type to it.data }.toHashSet()
        var added = 0
        var skipped = 0
        records.forEach { record ->
            val key = record.type to record.data
            if (key in existingKeys) {
                skipped++
            } else {
                // 直接插入原始记录,保留原 timestamp/count;不去重键复用 upsert
                // (upsert 会把 timestamp 重置为现在、count+1,会污染排序)
                store.insertRaw(
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
                existingKeys.add(key)
                added++
            }
        }
        return MergeResult(added, skipped)
    }

    /**
     * 屏蔽规则合并:五元组(category/type/isRegex/keywords/username/uid)全等判重,
     * 双方归一化(keywords null vs "[]"、username null vs "")后比较再入库。
     */
    suspend fun mergeBlockRules(rules: List<BlockRuleData>, store: BlockStore): MergeResult {
        val existing = store.all().toMutableList()
        var added = 0
        var skipped = 0
        rules.forEach { rule ->
            // 存储的 keywords 对用户类型规则为 null,导出序列化为 "[]";
            // 双方都归一化为 List<String> 再比较,避免 null 与 "[]" 恒不相等导致重复导入。
            // username 同理:存 null 但导入入库时 orEmpty() 为 "",双方归一化再比,
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
                store.add(block)
                // 可变快照内追加:文件内出现重复规则时,前一条插入后即可被后续判重命中
                existing.add(block)
                added++
            }
        }
        return MergeResult(added, skipped)
    }
}
