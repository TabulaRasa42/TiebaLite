package com.huanchengfly.tieba.post.ui.page.history.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.onEvent
import com.huanchengfly.tieba.post.arch.onGlobalEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.fromJson
import com.huanchengfly.tieba.post.models.ThreadHistoryInfoBean
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ForumPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.ThreadPageDestination
import com.huanchengfly.tieba.post.ui.page.thread.ThreadPageFrom
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Chip
import com.huanchengfly.tieba.post.ui.widgets.compose.EmptyPlaceholder
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.LoadMoreLayout
import com.huanchengfly.tieba.post.ui.widgets.compose.LocalSnackbarHostState
import com.huanchengfly.tieba.post.ui.widgets.compose.LongClickMenu
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.UserHeader
import com.huanchengfly.tieba.post.ui.widgets.compose.picker.WheelDatePickerDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberMenuState
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.HistoryUtil
import java.util.Calendar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryListPage(
    type: Int,
    // 是否为 pager 当前可见 tab：只有当前 tab 注册筛选按钮转发并写 bus，
    // 防止中止翻页手势时邻居页覆盖注册/写回旧筛选日（#1）
    isCurrentTab: Boolean = true,
    viewModel: HistoryListViewModel = if (type == HistoryUtil.TYPE_THREAD) pageViewModel<ThreadHistoryListViewModel>() else pageViewModel<ForumHistoryListViewModel>()
) {
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(HistoryListUiIntent.Refresh)
        viewModel.initialized = true
    }
    // 按天筛选状态（一次收集，顶栏标题高亮与页面内列表共用）
    val filteredDayStart by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::filteredDayStart,
        initial = null
    )
    LaunchedEffect(Unit) {
        if (HistoryListRefreshSignal.consume(type)) {
            val filterDay = filteredDayStart
            if (filterDay != null) {
                viewModel.send(HistoryListUiIntent.SelectDay(filterDay))
            } else {
                viewModel.send(HistoryListUiIntent.Refresh)
            }
        }
    }
    onGlobalEvent<HistoryListUiEvent.DeleteAll> {
        viewModel.send(HistoryListUiIntent.DeleteAll)
    }
    onGlobalEvent<HistoryListUiEvent.Imported> {
        // 筛选生效时按同日重查，让导入的当天记录立即可见
        val filterDay = filteredDayStart
        if (filterDay != null) {
            viewModel.send(HistoryListUiIntent.SelectDay(filterDay))
        } else {
            viewModel.send(HistoryListUiIntent.Imported)
        }
    }
    val isLoadingMore by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::isLoadingMore,
        initial = false
    )
    val hasMore by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::hasMore,
        initial = true
    )
    val currentPage by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::currentPage,
        initial = 0
    )
    val todayHistoryData by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::todayHistoryData,
        initial = emptyList()
    )
    val beforeHistoryData by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::beforeHistoryData,
        initial = emptyList()
    )
    val dayHistoryData by viewModel.uiState.collectPartialAsState(
        prop1 = HistoryListUiState::dayHistoryData,
        initial = emptyList()
    )
    val isFiltering = filteredDayStart != null

    // 顶栏「按天筛选」按钮经 HistoryFilterBus 转发到当前 tab 的对话框；
    // 只有组合中的页面会注册，语义即"当前 tab"。
    // isCurrentTab 为 false 的邻居页（中止翻页手势时被组合过又滑回）不注册也不写 bus，
    // 避免覆盖当前 tab 的 listener / 筛选日
    val datePickerState = rememberDialogState()
    DisposableEffect(isCurrentTab) {
        if (!isCurrentTab) return@DisposableEffect onDispose { }
        val pickDay: () -> Unit = { datePickerState.show() }
        HistoryFilterBus.register(pickDay)
        // 不在 onDispose 里清 bus 值：同一帧内新页 effects 先于旧页 onDispose 执行，
        // 无条件写 null 会覆盖新 tab 刚写入的筛选状态（两 tab 都筛选过时顶栏显示错乱）。
        // 离开本页后 bus 值虽残留，但重新进入时下方 SideEffect 会立即写入当前真实值
        onDispose { HistoryFilterBus.unregister(pickDay) }
    }
    // 筛选日写回 bus，供顶栏图标高亮/标题展示（切 tab 时最新注册者覆盖）。
    // 仅当前 tab 写：邻居页组合时不得覆盖（SideEffect：组合期直接写快照状态属 backwards
    // write，移到提交后执行）
    SideEffect {
        if (isCurrentTab) {
            HistoryFilterBus.updateFilterDayStart(filteredDayStart)
        }
    }

    // 历史最早记录年份用于限定滚轮可选范围（异步查询一次，完成前回退当前年）
    var earliestYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    LaunchedEffect(Unit) {
        val earliest = HistoryUtil.getEarliestTimestamp() ?: return@LaunchedEffect
        val year = DateTimeUtils.toCalendarFields(earliest).first
        if (year < earliestYear) earliestYear = year
    }
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val snackbarHostState = LocalSnackbarHostState.current
    viewModel.onEvent<HistoryListUiEvent.Delete.Failure> {
        snackbarHostState.showSnackbar(
            context.getString(
                R.string.delete_history_failure,
                it.errorMsg
            )
        )
    }
    viewModel.onEvent<HistoryListUiEvent.Delete.Success> {
        snackbarHostState.showSnackbar(context.getString(R.string.delete_history_success))
    }
    // 分组视图与筛选平铺视图是两个不同的 LazyColumn（前者带 stickyHeader 分页），
    // 各自持有独立的滚动位置；共用一个 LazyListState 会在切换瞬间把 A 视图的
    // firstVisibleItemIndex 钳到 B 视图上造成跳变
    val groupListState = rememberLazyListState()
    val flatListState = rememberLazyListState()
    val historyClicked: (History) -> Unit = { info ->
        when (info.type) {
            HistoryUtil.TYPE_FORUM -> {
                navigator.navigate(ForumPageDestination(info.data))
            }

            HistoryUtil.TYPE_THREAD -> {
                val extra =
                    if (info.extras != null) info.extras.fromJson<ThreadHistoryInfoBean>() else null
                navigator.navigate(
                    ThreadPageDestination(
                        info.data.toLong(),
                        postId = extra?.pid?.toLongOrNull() ?: 0L,
                        seeLz = extra?.isSeeLz ?: false,
                        from = ThreadPageFrom.FROM_HISTORY
                    )
                )
            }
        }
    }
    val historyDeleted: (History) -> Unit = { info ->
        viewModel.send(HistoryListUiIntent.Delete(info.id))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (isFiltering) {
            // 按天筛选：平铺模式，单日记录量有限，无分页；当天无记录时空态占位
            if (dayHistoryData.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyPlaceholder()
                }
            } else {
                HistoryFlatList(
                    histories = dayHistoryData,
                    lazyListState = flatListState,
                    onItemClicked = historyClicked,
                    onItemDeleted = historyDeleted
                )
            }
        } else {            LoadMoreLayout(
                isLoading = isLoadingMore,
                onLoadMore = {
                    viewModel.send(
                        HistoryListUiIntent.LoadMore(
                            page = currentPage + 1,
                            startGeneration = viewModel.uiState.value.generation
                        )
                    )
                },
                loadEnd = !hasMore,
                lazyListState = groupListState,
                isEmpty = todayHistoryData.isEmpty() && beforeHistoryData.isEmpty()
            ) {
                MyLazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    state = groupListState
                ) {
                    if (todayHistoryData.isNotEmpty()) {
                        stickyHeader(key = "TodayHistoryHeader") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ExtendedTheme.colors.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Chip(
                                    text = stringResource(id = R.string.title_history_today),
                                    invertColor = true
                                )
                            }
                        }
                        items(
                            items = todayHistoryData,
                            key = { it.id }
                        ) { info ->
                            HistoryItem(
                                info,
                                onDelete = historyDeleted,
                                onClick = historyClicked
                            )
                        }
                    }
                    if (beforeHistoryData.isNotEmpty()) {
                        stickyHeader(key = "BeforeHistoryHeader") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ExtendedTheme.colors.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Chip(text = stringResource(id = R.string.title_history_before))
                            }
                        }
                        items(
                            items = beforeHistoryData,
                            key = { it.id }
                        ) { info ->
                            HistoryItem(
                                info,
                                onDelete = historyDeleted,
                                onClick = historyClicked
                            )
                        }
                    }
                }
            }
        }
    }

    // 按天筛选对话框：确认后发 SelectDay；筛选中再选同一天视为取消筛选（清除）
    WheelDatePickerDialog(
        dialogState = datePickerState,
        title = stringResource(id = R.string.title_filter_by_day),
        initialYear = filteredDayStart?.let { DateTimeUtils.toCalendarFields(it).first }
            ?: Calendar.getInstance().get(Calendar.YEAR),
        initialMonth = (filteredDayStart?.let { DateTimeUtils.toCalendarFields(it).second }
            ?: Calendar.getInstance().get(Calendar.MONTH)) + 1,
        initialDayOfMonth = filteredDayStart?.let { DateTimeUtils.toCalendarFields(it).third }
            ?: Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
        minYear = earliestYear,
        maxYear = Calendar.getInstance().get(Calendar.YEAR),
        onConfirm = { year, month, dayOfMonth ->
            val dayStart = DateTimeUtils.dayStart(year, month - 1, dayOfMonth)
            if (dayStart == filteredDayStart) {
                viewModel.send(HistoryListUiIntent.ClearDayFilter)
            } else {
                viewModel.send(HistoryListUiIntent.SelectDay(dayStart))
            }
        }
    )
}

// 按天筛选下的平铺列表：无分组头、无分页
@Composable
private fun HistoryFlatList(
    histories: List<History>,
    lazyListState: LazyListState,
    onItemClicked: (History) -> Unit,
    onItemDeleted: (History) -> Unit,
) {
    MyLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState
    ) {
        items(
            items = histories,
            key = { it.id }
        ) { info ->
            HistoryItem(
                info,
                onDelete = onItemDeleted,
                onClick = onItemClicked
            )
        }
    }
}

@Composable
private fun HistoryItem(
    info: History,
    modifier: Modifier = Modifier,
    onClick: (History) -> Unit = {},
    onDelete: (History) -> Unit = {},
) {
    val menuState = rememberMenuState()
    LongClickMenu(
        menuContent = {
            DropdownMenuItem(onClick = {
                onDelete(info)
                menuState.expanded = false
            }) {
                Text(text = stringResource(id = R.string.title_delete))
            }
        },
        menuState = menuState,
        onClick = { onClick(info) }
    ) {
        Column(
            modifier = modifier
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserHeader(
                avatar = {
                    Avatar(
                        data = info.avatar,
                        size = Sizes.Small,
                        contentDescription = null
                    )
                },
                name = {
                    Text(
                        text = (if (info.type == HistoryUtil.TYPE_THREAD) info.username else info.title)
                            ?: ""
                    )
                },
            ) {
                Text(
                    text = DateTimeUtils.getRelativeTimeString(
                        LocalContext.current,
                        info.timestamp
                    ),
                    fontSize = 15.sp,
                    color = ExtendedTheme.colors.text,
                )
            }
            if (info.type == HistoryUtil.TYPE_THREAD) {
                Text(
                    text = info.title,
                    fontSize = 15.sp,
                    color = ExtendedTheme.colors.text,
                )
            }
        }
    }
}
