package com.huanchengfly.tieba.post.utils

import android.net.Uri
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.fromJson
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.toJson
import com.huanchengfly.tieba.post.utils.backup.BackupMerge
import com.huanchengfly.tieba.post.utils.backup.BlockStore
import com.huanchengfly.tieba.post.utils.backup.HistoryStore
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

            // 判重合并逻辑与 WebDAV 备份恢复共用 BackupMerge(单一来源)
            val result = BackupMerge.mergeBlockRules(BlockRuleSerializer.deserialize(json), DatabaseBlockStore)
            ImportResult(result.added, result.skipped)
        }

    data class ImportResult(val added: Int, val skipped: Int)
}

/** 生产实现:屏蔽规则入库走 BlockManager(同步内存索引) */
object DatabaseBlockStore : BlockStore {
    override suspend fun all(): List<Block> = DatabaseUtil.getAllBlocks()

    override suspend fun add(block: Block) {
        BlockManager.addBlock(block)
    }

    /**
     * 06 号票:判重合并整体包进 Room 事务,并发写入方被串行化。
     *
     * BlockManager 内存索引一致性处置(票面两选一,选后者):addBlock 先插 DB 后更新
     * 内存 CopyOnWriteArrayList,若事务回滚,已加进内存的"幻影条目"不会随之回滚——
     * 接受该边缘不一致,依赖下次启动 BlockManager.init() 重读全表自愈:
     * - 回滚唯一来源是 merge 中途 DB 硬错误(两入口均 NonCancellable,无用户取消);
     *   此时数据完整性已不可保证,幻影条目只是把一条待插规则提前放进拦截列表,
     *   后果是"多拦不漏拦",且 UI 删除该规则可即时清掉;
     * - 反过来"事务内只插 DB、提交后再更新内存"需要全表重读替换内存列表,与并发
     *   单条 addBlock 之间又形成新的丢条窗口,复杂度不成比例。
     */
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        return DatabaseUtil.inDatabaseTransaction { block() }
    }
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

            // 判重合并逻辑与 WebDAV 备份恢复共用 BackupMerge(单一来源)
            val result = BackupMerge.mergeHistories(HistorySerializer.deserialize(json), DatabaseHistoryStore)
            HistoryTransfer.ImportResult(result.added, result.skipped)
        }

    data class ImportResult(val added: Int, val skipped: Int)
}

/** 生产实现:浏览记录入库走 DatabaseUtil(upsertHistoryRaw 保留原始 timestamp/count) */
object DatabaseHistoryStore : HistoryStore {
    override suspend fun all(): List<History> = DatabaseUtil.getAllHistoryNoLimit()

    override suspend fun insertRaw(history: History) {
        DatabaseUtil.upsertHistoryRaw(history)
    }

    /** 06 号票:判重合并整体包进 Room 事务(机制同 DatabaseBlockStore) */
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        return DatabaseUtil.inDatabaseTransaction { block() }
    }
}
