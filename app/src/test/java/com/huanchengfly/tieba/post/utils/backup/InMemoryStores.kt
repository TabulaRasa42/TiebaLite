package com.huanchengfly.tieba.post.utils.backup

import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History

/**
 * BackupMerge 相关 JVM 测试共享的内存 store 夹具。
 *
 * 基础形态(rows 列表 + all/insertRaw/add)与 02 号票 WebDavBackupRestoreTest 的
 * InMemory store 行为一致;06 号票新增可选的事务边界记录:传 `recordOps = true`
 * 时,每个操作按 "tx:all"/"tx:insert" 等格式记录(是否发生在 inTransaction 块内),
 * 供事务边界测试断言;默认 false 时零开销。
 *
 * JVM fake 无真事务:inTransaction 直接执行块(生产映射 Room withTransaction,
 * 事务的串行化/回滚语义依赖 Room,本侧只锁"merge 全程在事务块内执行"的调用契约)。
 */
open class InMemoryHistoryStore(
    private val recordOps: Boolean = false,
) : HistoryStore {
    val rows = mutableListOf<History>()
    val ops = mutableListOf<String>()
    private var txDepth = 0
    var failOnInsert = false

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        txDepth++
        try {
            return block()
        } finally {
            txDepth--
        }
    }

    override suspend fun all(): List<History> {
        if (recordOps) ops += if (txDepth > 0) "tx:all" else "all"
        return rows.toList()
    }

    override suspend fun insertRaw(history: History) {
        if (recordOps) ops += if (txDepth > 0) "tx:insert" else "insert"
        if (failOnInsert) throw IllegalStateException("db error")
        rows += history
    }
}

/** 屏蔽规则侧共享夹具,语义同 [InMemoryHistoryStore] */
open class InMemoryBlockStore(
    private val recordOps: Boolean = false,
) : BlockStore {
    val rows = mutableListOf<Block>()
    val ops = mutableListOf<String>()
    private var txDepth = 0

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        txDepth++
        try {
            return block()
        } finally {
            txDepth--
        }
    }

    override suspend fun all(): List<Block> {
        if (recordOps) ops += if (txDepth > 0) "tx:all" else "all"
        return rows.toList()
    }

    override suspend fun add(block: Block) {
        if (recordOps) ops += if (txDepth > 0) "tx:add" else "add"
        rows += block
    }
}
