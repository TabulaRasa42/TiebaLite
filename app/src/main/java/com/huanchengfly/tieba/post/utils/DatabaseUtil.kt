package com.huanchengfly.tieba.post.utils

import androidx.room.withTransaction
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.database.AppDatabase
import com.huanchengfly.tieba.post.database.AppDatabaseEntryPoint
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Draft
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.SearchHistory
import com.huanchengfly.tieba.post.models.database.SearchPostHistory
import com.huanchengfly.tieba.post.models.database.TopForum
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

object DatabaseUtil {
    private val appDatabase: AppDatabase by lazy {
        EntryPointAccessors.fromApplication(
            App.INSTANCE,
            AppDatabaseEntryPoint::class.java,
        ).appDatabase()
    }

    /**
     * 在 Room 事务内执行 [block](06 号票):块内所有 DAO 挂起调用自动加入同一事务,
     * 与其他事务串行化,消除"读全表 → 判重 → 插入"的 check-then-act 竞态窗口。
     * room-ktx 的 withTransaction;事务内抛异常整体回滚并向调用方透传原异常。
     *
     * NonCancellable:调用方协程在事务中途被取消会触发回滚,而 BlockManager.addBlock
     * 的内存索引不随 DB 回滚,留下幻影条目(见 DatabaseBlockStore.inTransaction 注释)。
     * 把不可取消结构性下沉到此处,事务块内的调用点无需各自记得包 NonCancellable。
     */
    suspend fun <T> inDatabaseTransaction(block: suspend () -> T): T =
        withContext(NonCancellable) {
            appDatabase.withTransaction { block() }
        }

    // ── Account ─────────────────────────────────────────────────
    suspend fun getAllAccounts(): List<Account> = appDatabase.accountDao().getAll()

    suspend fun getAccountById(id: Int): Account? = appDatabase.accountDao().getById(id)

    suspend fun getAccountByUid(uid: String): Account? = appDatabase.accountDao().getByUid(uid)

    suspend fun getAccountByBduss(bduss: String): Account? = appDatabase.accountDao().getByBduss(bduss)

    suspend fun upsertAccountByUid(account: Account) = appDatabase.accountDao().upsertByUid(account)

    suspend fun updateAccount(account: Account) = appDatabase.accountDao().update(account)

    suspend fun deleteAccount(account: Account) = appDatabase.accountDao().delete(account)

    // ── History ─────────────────────────────────────────────────
    suspend fun getAllHistory(): List<History> = appDatabase.historyDao().getAll()

    suspend fun getAllHistoryNoLimit(): List<History> = appDatabase.historyDao().getAllNoLimit()

    suspend fun getHistoryByType(type: Int, pageSize: Int = 100, offset: Int = 0): List<History> =
        appDatabase.historyDao().getByType(type, pageSize, offset)

    suspend fun getHistoryByTypeAndDay(type: Int, dayStart: Long, dayEnd: Long): List<History> =
        appDatabase.historyDao().getByTypeAndDay(type, dayStart, dayEnd)

    suspend fun getEarliestHistoryTimestamp(): Long? =
        appDatabase.historyDao().getEarliestTimestamp()

    fun getHistoryFlowByType(type: Int, pageSize: Int = 100, offset: Int = 0): Flow<List<History>> =
        appDatabase.historyDao().getFlowByType(type, pageSize, offset)

    suspend fun upsertHistory(history: History) = appDatabase.historyDao().upsert(history)

    // 直接插入记录，保留原始 timestamp/count，不触发 upsert 的去重更新逻辑（导入用）
    suspend fun upsertHistoryRaw(history: History) = appDatabase.historyDao().insert(history)

    suspend fun deleteHistoryById(id: Long) = appDatabase.historyDao().deleteById(id)

    suspend fun deleteAllHistory() = appDatabase.historyDao().deleteAll()

    // ── Block ───────────────────────────────────────────────────
    suspend fun getAllBlocks(): List<Block> = appDatabase.blockDao().getAll()

    fun getAllBlocksFlow(): Flow<List<Block>> = appDatabase.blockDao().getAllFlow()

    suspend fun insertBlock(block: Block): Long = appDatabase.blockDao().insert(block)

    suspend fun deleteBlockById(id: Long) = appDatabase.blockDao().deleteById(id)

    suspend fun deleteBlocksByCategory(category: Int) =
        appDatabase.blockDao().deleteByCategory(category)

    // ── Draft ───────────────────────────────────────────────────
    suspend fun getDraft(hash: String): Draft? = appDatabase.draftDao().getByHash(hash)

    suspend fun saveDraft(hash: String, content: String) {
        appDatabase.draftDao().upsert(Draft(hash = hash, content = content))
    }

    suspend fun deleteDraft(hash: String) = appDatabase.draftDao().deleteByHash(hash)

    // ── TopForum ────────────────────────────────────────────────
    suspend fun getTopForums(): List<TopForum> = appDatabase.topForumDao().getAll()

    suspend fun addTopForum(forumId: String) {
        appDatabase.topForumDao().insertOrReplace(TopForum(forumId = forumId))
    }

    suspend fun deleteTopForum(forumId: String) = appDatabase.topForumDao().deleteByForumId(forumId)

    // ── SearchHistory ───────────────────────────────────────────
    suspend fun getAllSearchHistories(): List<SearchHistory> = appDatabase.searchHistoryDao().getAll()

    suspend fun saveSearchHistory(content: String) {
        appDatabase.searchHistoryDao().upsert(SearchHistory(content = content))
    }

    suspend fun deleteSearchHistory(id: Long) = appDatabase.searchHistoryDao().deleteById(id)

    suspend fun clearSearchHistory() = appDatabase.searchHistoryDao().deleteAll()

    // ── SearchPostHistory ───────────────────────────────────────
    suspend fun getAllSearchPostHistories(): List<SearchPostHistory> = appDatabase.searchPostHistoryDao().getAll()

    suspend fun saveSearchPostHistory(content: String, forumName: String) {
        appDatabase.searchPostHistoryDao().upsert(
            SearchPostHistory(content = content, forumName = forumName)
        )
    }

    suspend fun deleteSearchPostHistory(id: Long) = appDatabase.searchPostHistoryDao().deleteById(id)

    suspend fun clearSearchPostHistory() = appDatabase.searchPostHistoryDao().deleteAll()
}
