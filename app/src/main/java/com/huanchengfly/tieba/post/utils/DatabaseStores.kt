package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.backup.BlockStore
import com.huanchengfly.tieba.post.utils.backup.HistoryStore

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
     * - 回滚唯一来源是 merge 中途 DB 硬错误(协程取消已被 [DatabaseUtil.inDatabaseTransaction]
     *   内置的 NonCancellable 挡在事务外,不依赖调用方约定);此时数据完整性已不可保证,
     *   幻影条目只是把一条待插规则提前放进拦截列表,后果是"多拦不漏拦",且 UI 删除
     *   该规则可即时清掉;
     * - 反过来"事务内只插 DB、提交后再更新内存"需要全表重读替换内存列表,与并发
     *   单条 addBlock 之间又形成新的丢条窗口,复杂度不成比例。
     */
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        return DatabaseUtil.inDatabaseTransaction { block() }
    }
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
