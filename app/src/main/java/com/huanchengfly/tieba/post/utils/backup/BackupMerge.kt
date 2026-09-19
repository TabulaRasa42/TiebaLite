package com.huanchengfly.tieba.post.utils.backup

import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.toJson

/**
 * 备份/导入共用的数据写入 seam:判重合并逻辑([mergeHistories]/[mergeBlockRules])
 * 经此接口读写存储,不依赖 Android 环境。生产实现绑定 DatabaseUtil/BlockManager,
 * JVM 测试注入内存 fake。
 *
 * [inTransaction] 是原子性 seam(06 号票):生产实现映射到 Room `withTransaction`,
 * 块内的读与写走同一数据库连接并被串行化——"读全表 → 逐条判重 → 逐条插入"整体
 * 原子,恢复/导入执行期间的并发写入方(如恢复某条记录的同时用户恰好浏览了同键
 * 帖子)在事务提交点排队,判重快照与写入一致,不再产生双方都查无、各插一行的重复。
 * JVM 测试实现直接执行 block 并可记录事务边界。
 */
interface HistoryStore {
    suspend fun all(): List<History>
    suspend fun insertRaw(history: History)

    /** 在单个数据库事务内执行 [block](06 号票:消除判重 check-then-act 竞态) */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

interface BlockStore {
    suspend fun all(): List<Block>
    suspend fun add(block: Block)

    /** 在单个数据库事务内执行 [block](06 号票:消除判重 check-then-act 竞态) */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** 导入判重合并的结果数量(added/skipped 语义与现有本地导入一致) */
data class MergeResult(val added: Int, val skipped: Int)

/**
 * 浏览记录 + 屏蔽规则"合并 + 判重"的单一来源:
 * WebDAV 备份恢复与本地备份恢复共用同一份实现,保证判重行为字面一致(spec 决策)。
 */
object BackupMerge {

    /**
     * 浏览记录合并:按 (type, data) 判重跳过,新记录保留原始 timestamp/count 直插。
     * 可变快照内追加,文件内出现重复记录时后续条目同样被命中。
     *
     * 读 + 判重 + 写整体包在 [HistoryStore.inTransaction] 内(06 号票):事务把并发
     * 写入方串行化到提交点之后,消除"双方判重都查无、各插一行"的 check-then-act 竞态。
     */
    suspend fun mergeHistories(records: List<HistoryRecordData>, store: HistoryStore): MergeResult =
        store.inTransaction {
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
                    store.insertRaw(record.toHistory())
                    existingKeys.add(key)
                    added++
                }
            }
            MergeResult(added, skipped)
        }

    /**
     * 屏蔽规则合并:五元组(category/type/isRegex/keywords/username/uid)全等判重,
     * 双方归一化(keywords null vs "[]"、username null vs "")后比较再入库。
     *
     * 读 + 判重 + 写整体包在 [BlockStore.inTransaction] 内(06 号票),竞态消除
     * 机制同 [mergeHistories]。
     */
    suspend fun mergeBlockRules(rules: List<BlockRuleData>, store: BlockStore): MergeResult =
        store.inTransaction {
            val existing = store.all().toMutableList()
            var added = 0
            var skipped = 0
            rules.forEach { rule ->
                // 存储的 keywords 对用户类型规则为 null,导出序列化为 "[]";
                // 双方都归一化为 List<String> 再比较,避免 null 与 "[]" 恒不相等导致重复导入。
                // username/uid 同理:存 null 但导入文件可能写 ""(手工编辑/其他工具导出),
                // 双方 orEmpty() 归一化再比,否则 null-uid 规则每次导入都会重复入库
                val duplicated = existing.any {
                    it.category == rule.category &&
                            it.type == rule.type &&
                            it.isRegex == rule.isRegex &&
                            it.getKeywords() == rule.keywords &&
                            it.username.orEmpty() == rule.username.orEmpty() &&
                            it.uid.orEmpty() == rule.uid.orEmpty()
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
            MergeResult(added, skipped)
        }
}
