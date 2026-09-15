package com.huanchengfly.tieba.post.ui.page.history.list

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.HistoryUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

// 跨 tab 刷新信号：Imported/DeleteAll 全局事件只被当前可见的 pager 页收到
// （HorizontalPager 仅组合当前页，另一 tab 的 onGlobalEvent 收集器不在组合中），
// 未组合的 tab 重新组合时通过此标记补一次刷新。
// pending 按 tab（type）各挂一个：信号可能来自 HistoryPage 自身（导入/清空，此时两 tab
// 都组合着或一可见一隐藏），也可能来自外部页面（如备份恢复，此时两 tab 都不在组合中）——
// 单个布尔会被返回重组的当前 tab 抢先 consume，隐藏 tab 永远刷不到
object HistoryListRefreshSignal {
    // key = tab 的 type（HistoryUtil.TYPE_THREAD / TYPE_FORUM）
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicBoolean>()

    private fun flag(type: Int): java.util.concurrent.atomic.AtomicBoolean =
        pending.computeIfAbsent(type) { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun mark() {
        flag(HistoryUtil.TYPE_THREAD).set(true)
        flag(HistoryUtil.TYPE_FORUM).set(true)
    }

    fun consume(type: Int): Boolean {
        val f = flag(type)
        return f.getAndSet(false)
    }
}

// 日期筛选协调器：顶栏按钮在 HistoryPage、对话框与筛选状态在各 tab 的 HistoryListPage，
// 两者 pager 页索引不同无法直接引用，通过此单例桥接（模式同 HistoryListRefreshSignal）。
// listener 只在组合中的页面注册（语义即"当前 tab"）；
// 筛选日用 mutableStateOf 持有，读侧（HistoryPage 顶栏）自动订阅重组
object HistoryFilterBus {
    // 当前 tab 的筛选日（null=未筛选），供顶栏图标高亮与标题展示
    var currentFilterDayStart: Long? by mutableStateOf(null)
        private set

    fun updateFilterDayStart(dayStart: Long?) {
        currentFilterDayStart = dayStart
    }

    private var listener: (() -> Unit)? = null

    fun register(listener: () -> Unit) {
        this.listener = listener
    }

    // 按身份注销：pager 滑动时新 tab 先注册、旧 tab 后销毁，
    // 无条件置空会把新 tab 的注册误清掉
    fun unregister(listener: () -> Unit) {
        if (this.listener === listener) {
            this.listener = null
        }
    }

    fun requestPickDay() {
        listener?.invoke()
    }
}

abstract class HistoryListViewModel :
    BaseViewModel<HistoryListUiIntent, HistoryListPartialChange, HistoryListUiState, HistoryListUiEvent>() {
    override fun createInitialState(): HistoryListUiState = HistoryListUiState()

    override fun dispatchEvent(partialChange: HistoryListPartialChange): UiEvent? {
        return when (partialChange) {
            is HistoryListPartialChange.Delete.Success -> HistoryListUiEvent.Delete.Success
            is HistoryListPartialChange.Delete.Failure -> HistoryListUiEvent.Delete.Failure(
                partialChange.error.getErrorMessage()
            )

            else -> null
        }
    }
}

@Stable
@HiltViewModel
class ThreadHistoryListViewModel @Inject constructor() : HistoryListViewModel() {
    override fun createPartialChangeProducer(): PartialChangeProducer<HistoryListUiIntent, HistoryListPartialChange, HistoryListUiState> =
        HistoryListPartialChangeProducer(HistoryUtil.TYPE_THREAD)
}

@Stable
@HiltViewModel
class ForumHistoryListViewModel @Inject constructor() : HistoryListViewModel() {
    override fun createPartialChangeProducer(): PartialChangeProducer<HistoryListUiIntent, HistoryListPartialChange, HistoryListUiState> =
        HistoryListPartialChangeProducer(HistoryUtil.TYPE_FORUM)
}

private class HistoryListPartialChangeProducer(val type: Int) :
    PartialChangeProducer<HistoryListUiIntent, HistoryListPartialChange, HistoryListUiState> {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun toPartialChangeFlow(intentFlow: Flow<HistoryListUiIntent>): Flow<HistoryListPartialChange> =
        merge(
            intentFlow.filterIsInstance<HistoryListUiIntent.Refresh>()
                .flatMapConcat { produceRefreshPartialChange() },
            intentFlow.filterIsInstance<HistoryListUiIntent.LoadMore>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<HistoryListUiIntent.Delete>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<HistoryListUiIntent.DeleteAll>()
                .flatMapConcat { produceDeleteAllPartialChange() },
            // 导入完成后重新加载列表（复用 Refresh 逻辑）
            intentFlow.filterIsInstance<HistoryListUiIntent.Imported>()
                .flatMapConcat { produceRefreshPartialChange() },
            intentFlow.filterIsInstance<HistoryListUiIntent.SelectDay>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<HistoryListUiIntent.ClearDayFilter>()
                .flatMapConcat { produceClearDayFilterPartialChange() },
        )

    private fun produceDeleteAllPartialChange() = flowOf(HistoryListPartialChange.DeleteAll)

    private suspend fun loadFirstPage(): HistoryListPartialChange.Refresh.Success {
        val histories = HistoryUtil.get(type, 0)
        return HistoryListPartialChange.Refresh.Success(
            todayHistoryData = histories.filter { DateTimeUtils.isToday(it.timestamp) },
            beforeHistoryData = histories.filterNot { DateTimeUtils.isToday(it.timestamp) },
            hasMore = histories.size == HistoryUtil.PAGE_SIZE,
        )
    }

    private fun produceRefreshPartialChange(): Flow<HistoryListPartialChange.Refresh> =
        flow<HistoryListPartialChange.Refresh> {
            emit(loadFirstPage())
        }
            .flowOn(Dispatchers.IO)
            .catch { emit(HistoryListPartialChange.Refresh.Failure(it)) }

    private fun HistoryListUiIntent.SelectDay.producePartialChange(): Flow<HistoryListPartialChange.SelectDay> =
        flow<HistoryListPartialChange.SelectDay> {
            val (year, month, dayOfMonth) = DateTimeUtils.toCalendarFields(dayStart)
            val histories =
                HistoryUtil.getByDay(type, dayStart, DateTimeUtils.dayEnd(year, month, dayOfMonth))
            emit(HistoryListPartialChange.SelectDay.Success(dayStart, histories))
        }
            .flowOn(Dispatchers.IO)
            .catch { emit(HistoryListPartialChange.SelectDay.Failure(it)) }

    private fun produceClearDayFilterPartialChange(): Flow<HistoryListPartialChange> =
        flow<HistoryListPartialChange> {
            emit(HistoryListPartialChange.ClearDayFilter.Success)
            emit(loadFirstPage())
        }
            .flowOn(Dispatchers.IO)
            .catch { emit(HistoryListPartialChange.Refresh.Failure(it)) }

    private fun HistoryListUiIntent.LoadMore.producePartialChange(): Flow<HistoryListPartialChange.LoadMore> =
        flow<HistoryListPartialChange.LoadMore> {
            val histories = HistoryUtil.get(type, page)
            emit(
                HistoryListPartialChange.LoadMore.Success(
                    startGeneration = startGeneration,
                    todayHistoryData = histories.filter { DateTimeUtils.isToday(it.timestamp) },
                    beforeHistoryData = histories.filterNot { DateTimeUtils.isToday(it.timestamp) },
                    hasMore = histories.size == HistoryUtil.PAGE_SIZE,
                    currentPage = page
                )
            )
        }
            .onStart {
                emit(HistoryListPartialChange.LoadMore.Start)
            }
            .flowOn(Dispatchers.IO)
            .catch {
                emit(HistoryListPartialChange.LoadMore.Failure(startGeneration, it))
            }

    private fun HistoryListUiIntent.Delete.producePartialChange() =
        flow<HistoryListPartialChange.Delete> {
            DatabaseUtil.deleteHistoryById(id)
            emit(HistoryListPartialChange.Delete.Success(id))
        }
            .flowOn(Dispatchers.IO)
            .catch { emit(HistoryListPartialChange.Delete.Failure(it)) }
}

sealed interface HistoryListUiIntent : UiIntent {
    object Refresh : HistoryListUiIntent

    // 发起分页加载时记录当时的列表代数，结果回来后用于过期校验
    data class LoadMore(
        val page: Int,
        val startGeneration: Long
    ) : HistoryListUiIntent

    data class Delete(val id: Long) : HistoryListUiIntent

    object DeleteAll : HistoryListUiIntent

    // 导入浏览记录完成后触发列表重新加载
    object Imported : HistoryListUiIntent

    // 按天筛选：dayStart 为该日 00:00:00 的本地时间戳
    data class SelectDay(val dayStart: Long) : HistoryListUiIntent

    // 清除按天筛选，回到「今天/更早」分组视图并刷新
    object ClearDayFilter : HistoryListUiIntent
}

sealed interface HistoryListPartialChange : PartialChange<HistoryListUiState> {
    object DeleteAll : HistoryListPartialChange {
        override fun reduce(oldState: HistoryListUiState): HistoryListUiState = oldState.copy(
            todayHistoryData = emptyList(),
            beforeHistoryData = emptyList(),
            dayHistoryData = emptyList(),
            // 筛选日保留：清空后停在筛选模式显示空列表，与其他路径语义一致；
            // 用户可点日期图标改选或清除
            currentPage = 0,
            hasMore = false,
            isLoadingMore = false,
            isRefreshing = false,
            // 重置型变更：使在途 LoadMore 结果过期
            generation = oldState.generation + 1
        )
    }

    sealed class Refresh : HistoryListPartialChange {
        override fun reduce(oldState: HistoryListUiState): HistoryListUiState = when (this) {
            is Failure -> oldState
            is Success -> oldState.copy(
                todayHistoryData = todayHistoryData,
                beforeHistoryData = beforeHistoryData,
                currentPage = 0,
                hasMore = hasMore,
                // 重置型变更：使在途 LoadMore 结果过期
                generation = oldState.generation + 1
            )
        }

        data class Success(
            val todayHistoryData: List<History>,
            val beforeHistoryData: List<History>,
            val hasMore: Boolean
        ) : Refresh()

        data class Failure(
            val error: Throwable
        ) : Refresh()
    }

    sealed class SelectDay : HistoryListPartialChange {
        override fun reduce(oldState: HistoryListUiState): HistoryListUiState = when (this) {
            is Failure -> oldState
            is Success -> oldState.copy(
                filteredDayStart = dayStart,
                dayHistoryData = histories,
                // 筛选模式下无分页，重置型变更使在途 LoadMore 结果过期
                generation = oldState.generation + 1
            )
        }

        data class Success(
            val dayStart: Long,
            val histories: List<History>
        ) : SelectDay()

        data class Failure(
            val error: Throwable
        ) : SelectDay()
    }

    sealed class ClearDayFilter : HistoryListPartialChange {
        override fun reduce(oldState: HistoryListUiState): HistoryListUiState = when (this) {
            // 分组数据由紧随其后的 Refresh.Success 填充，这里只切回分组模式
            Success -> oldState.copy(
                filteredDayStart = null,
                dayHistoryData = emptyList(),
                // 重置型变更：使在途 LoadMore 结果过期（Refresh.Success 会再次 +1）
                generation = oldState.generation + 1
            )
        }

        object Success : ClearDayFilter()
    }

    sealed class LoadMore : HistoryListPartialChange {
        override fun reduce(oldState: HistoryListUiState): HistoryListUiState = when (this) {
            is Failure -> oldState.copy(isLoadingMore = false)
            Start -> oldState.copy(isLoadingMore = true)
            is Success -> {
                // 版本护栏：发起 LoadMore 后若发生 DeleteAll/Refresh/SelectDay/ClearDayFilter
                // 等重置型变更，本页结果已过期（如把旧记录追加回刚清空的列表、恢复已被
                // 重置的 currentPage/hasMore），按发起时记录的代数丢弃
                if (startGeneration != oldState.generation) {
                    oldState.copy(isLoadingMore = false)
                } else {
                    // OFFSET 分页在表被 upsert 重排（访问帖子/吧会刷新 timestamp）后，
                    // 下一页窗口可能返回已加载过的行；追加前按 id 去重，
                    // 避免 LazyColumn items(key = it.id) 因重复键抛异常崩溃
                    val existingIds =
                        (oldState.todayHistoryData + oldState.beforeHistoryData).mapTo(mutableSetOf()) { it.id }
                    oldState.copy(
                        isLoadingMore = false,
                        todayHistoryData = oldState.todayHistoryData + todayHistoryData.filterNot { it.id in existingIds },
                        beforeHistoryData = oldState.beforeHistoryData + beforeHistoryData.filterNot { it.id in existingIds },
                        currentPage = currentPage,
                        hasMore = hasMore
                    )
                }
            }
        }

        // 发起这次 LoadMore 时列表所处的代数，由 intent 携带；
        // Start 不参与过期校验，代数值无意义，置 -1 哨兵
        abstract val startGeneration: Long

        data object Start : LoadMore() {
            override val startGeneration: Long = -1L
        }

        data class Success(
            override val startGeneration: Long,
            val todayHistoryData: List<History>,
            val beforeHistoryData: List<History>,
            val hasMore: Boolean,
            val currentPage: Int
        ) : LoadMore()

        data class Failure(
            override val startGeneration: Long,
            val error: Throwable
        ) : LoadMore()
    }

    sealed class Delete : HistoryListPartialChange {
        override fun reduce(oldState: HistoryListUiState): HistoryListUiState = when (this) {
            is Failure -> oldState
            is Success -> oldState.copy(
                todayHistoryData = oldState.todayHistoryData.filterNot { it.id == id },
                beforeHistoryData = oldState.beforeHistoryData.filterNot { it.id == id },
                dayHistoryData = oldState.dayHistoryData.filterNot { it.id == id })
        }

        data class Success(
            val id: Long
        ) : Delete()

        data class Failure(
            val error: Throwable
        ) : Delete()
    }
}

data class HistoryListUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val todayHistoryData: List<History> = emptyList(),
    val beforeHistoryData: List<History> = emptyList(),
    // 按天筛选：非空时列表切换为平铺模式，只展示 dayHistoryData
    val filteredDayStart: Long? = null,
    val dayHistoryData: List<History> = emptyList(),
    // 重置型变更（DeleteAll/Refresh.Success/SelectDay.Success/ClearDayFilter.Success）递增，
    // 在途 LoadMore 结果按发起时代数判过期丢弃
    val generation: Long = 0,
) : UiState

sealed interface HistoryListUiEvent : UiEvent {
    sealed interface Delete : HistoryListUiEvent {
        object Success : Delete

        data class Failure(
            val errorMsg: String
        ) : Delete
    }

    object DeleteAll : HistoryListUiEvent

    // 广播导入完成，各子列表页收到后按当前筛选模式刷新
    object Imported : HistoryListUiEvent
}
