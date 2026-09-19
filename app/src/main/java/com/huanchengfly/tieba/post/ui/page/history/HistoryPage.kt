package com.huanchengfly.tieba.post.ui.page.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryFilterBus
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListPage
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListRefreshSignal
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListUiEvent
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.PagerTabIndicator
import com.huanchengfly.tieba.post.ui.widgets.compose.TabRow
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.ramcosta.composedestinations.annotation.DeepLink
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Destination(
    deepLinks = [
        DeepLink(uriPattern = "tblite://history")
    ]
)
@Composable
fun HistoryPage(
    navigator: DestinationsNavigator
) {
    val pagerState = rememberPagerState { 2 }
    val coroutineScope = rememberCoroutineScope()
    val scaffoldState = rememberScaffoldState()

    val context = LocalContext.current

    // 按天筛选：对话框与筛选状态在各 tab 的 HistoryListPage（各自 VM 各自状态），
    // 顶栏筛选按钮图标高亮经 HistoryFilterBus 桥接（见 HistoryListPage 的注册逻辑）。
    // bus 的筛选日是 mutableStateOf，读它即订阅重组；筛选日期的展示在列表内吸顶 Chip（见 HistoryListPage）
    val filterDayStart = HistoryFilterBus.currentFilterDayStart

    // 清空浏览记录确认弹窗
    val clearDialogState = rememberDialogState()
    ConfirmDialog(
        dialogState = clearDialogState,
        onConfirm = {
            coroutineScope.launch {
                runCatching {
                    HistoryUtil.deleteAll()
                }.onSuccess {
                    HistoryListRefreshSignal.mark()
                    emitGlobalEvent(HistoryListUiEvent.DeleteAll)
                    launch {
                        scaffoldState.snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.toast_clear_success
                            )
                        )
                    }
                }.onFailure {
                    launch {
                        scaffoldState.snackbarHostState.showSnackbar(
                            context.getString(R.string.toast_clear_failure, it.message ?: "")
                        )
                    }
                }
            }
        },
        title = {
            Text(text = stringResource(id = R.string.title_history_delete))
        }
    ) {
        Text(text = stringResource(id = R.string.tip_clear_history))
    }

    MyScaffold(
        backgroundColor = Color.Transparent,
        scaffoldState = scaffoldState,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(
                        text = stringResource(id = R.string.title_history),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                    )
                },
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                },
                actions = {
                    // 按天筛选按钮：点击转发给当前 tab 的 HistoryListPage 打开日期选择器；
                    // 筛选中图标高亮 primary 色
                    IconButton(onClick = { HistoryFilterBus.requestPickDay() }) {
                        Icon(
                            imageVector = Icons.Outlined.Event,
                            contentDescription = stringResource(id = R.string.title_filter_by_day),
                            tint = if (filterDayStart != null) ExtendedTheme.colors.primary
                            else ExtendedTheme.colors.onTopBar
                        )
                    }
                    IconButton(onClick = {
                        clearDialogState.show()
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(id = R.string.title_history_delete),
                            tint = ExtendedTheme.colors.onTopBar
                        )
                    }
                },
                content = {
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        indicator = { tabPositions ->
                            PagerTabIndicator(
                                pagerState = pagerState,
                                tabPositions = tabPositions
                            )
                        },
                        divider = {},
                        backgroundColor = Color.Transparent,
                        contentColor = ExtendedTheme.colors.primary,
                        modifier = Modifier
                            .width(100.dp * 2)
                            .align(Alignment.CenterHorizontally)
                    ) {
                        Tab(
                            text = {
                                Text(
                                    text = stringResource(id = R.string.title_history_thread),
                                    fontSize = 13.sp
                                )
                            },
                            selected = pagerState.currentPage == 0,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                            selectedContentColor = ExtendedTheme.colors.onTopBar,
                            unselectedContentColor = ExtendedTheme.colors.onTopBarSecondary
                        )
                        Tab(
                            text = {
                                Text(
                                    text = stringResource(id = R.string.title_history_forum),
                                    fontSize = 13.sp
                                )
                            },
                            selected = pagerState.currentPage == 1,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            },
                            selectedContentColor = ExtendedTheme.colors.onTopBar,
                            unselectedContentColor = ExtendedTheme.colors.onTopBarSecondary
                        )
                    }
                }
            )
        }
    ) {
        ProvideNavigator(navigator = navigator) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it },
                verticalAlignment = Alignment.Top,
                userScrollEnabled = true,
            ) {
                if (it == 0) {
                    HistoryListPage(
                        type = HistoryUtil.TYPE_THREAD,
                        isCurrentTab = pagerState.currentPage == 0
                    )
                } else {
                    HistoryListPage(
                        type = HistoryUtil.TYPE_FORUM,
                        isCurrentTab = pagerState.currentPage == 1
                    )
                }
            }
        }
    }
}
