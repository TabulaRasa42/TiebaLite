package com.huanchengfly.tieba.post.ui.page.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryFilterBus
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListPage
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListRefreshSignal
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListUiEvent
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ClickMenu
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.PagerTabIndicator
import com.huanchengfly.tieba.post.ui.widgets.compose.TabRow
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberMenuState
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.HistoryTransfer
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.ramcosta.composedestinations.annotation.DeepLink
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                // NonCancellable：写文件中途离开本页（scope 取消）时仍要完成写入，
                // 避免留下截断的半截 JSON
                withContext(NonCancellable) {
                    runCatching {
                        val histories = HistoryUtil.getAllNoLimit()
                        HistoryTransfer.exportTo(context, uri, histories)
                    }.onSuccess { count ->
                        context.toastShort(context.getString(R.string.toast_export_history_success, "$count 条"))
                    }.onFailure {
                        context.toastShort(context.getString(R.string.toast_export_history_failed, it.message ?: ""))
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                // NonCancellable：导入中途离开本页时仍要完成写入与提示，
                // 避免留下只导了一半的浏览记录且无任何提示
                withContext(NonCancellable) {
                    runCatching {
                        HistoryTransfer.importFrom(context, uri)
                    }.onSuccess { result ->
                        context.toastShort(
                            context.getString(R.string.toast_import_history_success, result.added, result.skipped)
                        )
                        HistoryListRefreshSignal.mark()
                        emitGlobalEvent(HistoryListUiEvent.Imported)
                    }.onFailure {
                        context.toastShort(
                            if (it is IllegalArgumentException) context.getString(R.string.toast_import_history_invalid_file)
                            else context.getString(R.string.toast_import_history_failed, it.message ?: "")
                        )
                    }
                }
            }
        }
    }

    // 按天筛选：对话框与筛选状态在各 tab 的 HistoryListPage（各自 VM 各自状态），
    // 顶栏按钮/标题/图标高亮经 HistoryFilterBus 桥接（见 HistoryListPage 的注册逻辑）。
    // bus 的筛选日是 mutableStateOf，读它即订阅重组
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
                        text = if (filterDayStart != null) {
                            val (y, m, d) = DateTimeUtils.toCalendarFields(filterDayStart)
                            stringResource(id = R.string.title_history_filtered, "$y 年 ${m + 1} 月 $d 日")
                        } else {
                            stringResource(id = R.string.title_history)
                        },
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                    )
                },
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                },
                actions = {
                    val menuState = rememberMenuState()
                    ClickMenu(
                        menuContent = {
                            DropdownMenuItem(onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                    .format(Date())
                                exportLauncher.launch("tblite_history_$timestamp.json")
                                dismiss()
                            }) {
                                Text(text = stringResource(id = R.string.title_export_history))
                            }
                            DropdownMenuItem(onClick = {
                                importLauncher.launch(arrayOf("application/json"))
                                dismiss()
                            }) {
                                Text(text = stringResource(id = R.string.title_import_history))
                            }
                        },
                        menuState = menuState,
                    ) {
                        IconButton(onClick = { menuState.show() }) {
                            Icon(
                                imageVector = Icons.Outlined.SwapVert,
                                contentDescription = stringResource(id = R.string.btn_more),
                                tint = ExtendedTheme.colors.onTopBar
                            )
                        }
                    }
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
