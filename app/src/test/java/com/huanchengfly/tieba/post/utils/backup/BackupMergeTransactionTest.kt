package com.huanchengfly.tieba.post.utils.backup

import com.huanchengfly.tieba.post.models.database.Block
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 06 号票:判重合并的"读 + 判重 + 写"原子化测试。
 *
 * 生产实现里 [HistoryStore.inTransaction]/[BlockStore.inTransaction] 映射到 Room
 * `withTransaction`(事务内并发写入方被串行化,竞态窗口消除);JVM 侧无法起真
 * Room,用 [InMemoryStores] 共享夹具记录事务边界与调用序列,锁定:
 * 1. merge 全程(读 + 判重 + 写)在单个事务块内执行;
 * 2. 事务内抛异常向外透传(由 Room withTransaction 整体回滚,调用方收到原异常);
 * 3. inTransaction 返回值透传 merge 结果(泛型 seam 的编译契约)。
 *
 * 不在此处覆盖的:判重合并的既有语义(全量在 WebDavBackupRestoreTest)。
 * 真事务的串行化/回滚行为依赖 Room,超出 JVM 测试能力,留真机验证。
 */
class BackupMergeTransactionTest {

    private fun historyRecord(data: String) = HistoryRecordData(
        title = "t$data", data = data, type = 0, timestamp = 1L, count = 1
    )

    private fun blockRule(keywords: List<String>) = BlockRuleData(
        category = Block.CATEGORY_BLACK_LIST, type = Block.TYPE_KEYWORD, keywords = keywords
    )

    // ── 事务边界 ──────────────────────────────────────────────

    @Test
    fun `浏览记录合并全程在单个事务块内`() = runBlocking {
        val store = InMemoryHistoryStore(recordOps = true)

        BackupMerge.mergeHistories(listOf(historyRecord("1"), historyRecord("2")), store)

        // 读 + 每条写入都发生在事务内,无任何事务外操作
        assertTrue(store.ops.isNotEmpty())
        assertTrue(store.ops.all { it.startsWith("tx:") })
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `屏蔽规则合并全程在单个事务块内`() = runBlocking {
        val store = InMemoryBlockStore(recordOps = true)

        BackupMerge.mergeBlockRules(listOf(blockRule(listOf("a")), blockRule(listOf("b"))), store)

        assertTrue(store.ops.isNotEmpty())
        assertTrue(store.ops.all { it.startsWith("tx:") })
        assertEquals(2, store.rows.size)
    }

    @Test
    fun `文件内重复记录靠批内快照判重命中`() = runBlocking {
        // mergeHistories 全程只读一次全表,文件内重复条目靠本地 existingKeys
        // HashSet 追加命中(与事务无关的批内语义,此处一并锁定防回归)
        val store = InMemoryHistoryStore()

        val result = BackupMerge.mergeHistories(
            listOf(historyRecord("1"), historyRecord("1")), store
        )

        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
        assertEquals(1, store.rows.size)
    }

    // ── 异常透传(整体回滚由 Room 保证,此处锁调用契约)────────

    @Test
    fun `事务内写失败时异常向外透传且无事务外操作`() = runBlocking {
        val store = InMemoryHistoryStore(recordOps = true)
        store.failOnInsert = true

        val e = runCatching {
            BackupMerge.mergeHistories(listOf(historyRecord("1")), store)
        }.exceptionOrNull()

        assertTrue(e is IllegalStateException)
        assertTrue(store.ops.none { !it.startsWith("tx:") }) // 无事务外操作
    }

    @Test
    fun `inTransaction的返回值透传merge结果`() = runBlocking {
        val store = InMemoryHistoryStore()

        val result = BackupMerge.mergeHistories(listOf(historyRecord("1")), store)

        assertEquals(MergeResult(1, 0), result)
    }
}
