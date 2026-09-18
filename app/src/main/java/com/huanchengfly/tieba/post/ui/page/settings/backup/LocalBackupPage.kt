package com.huanchengfly.tieba.post.ui.page.settings.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListRefreshSignal
import com.huanchengfly.tieba.post.ui.page.history.list.HistoryListUiEvent
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Button
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.DatabaseBlockStore
import com.huanchengfly.tieba.post.utils.DatabaseHistoryStore
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.huanchengfly.tieba.post.utils.backup.BackupRestore
import com.huanchengfly.tieba.post.utils.backup.LocalBackupTransfer
import com.huanchengfly.tieba.post.utils.webdav.BackupFormatException
import com.huanchengfly.tieba.post.utils.webdav.BackupVersionException
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地备份页(01 号票):没配网盘也能完整备份。备份与 WebDAV 完全同构(同一份三段 JSON,
 * 经 [LocalBackupTransfer] 以流为入参导出/导入,SAF URI 在本层解开成流,核心层纯 JVM)。
 *
 * "立即备份":确认弹窗(提示覆盖备份中的软件设置,取消零动作)→ SAF CreateDocument
 * 选保存位置 → 导出三段 JSON,文件名 tblite_backup_<时间戳>.json。
 * "从备份恢复":三部分勾选弹窗(默认全选,零勾选拒绝)→ SAF OpenDocument 选文件 →
 * 按勾选恢复(判重合并 + 软件设置按键覆盖)→ 浏览记录列表刷新。
 *
 * 写入/恢复包在 NonCancellable 中断页也不留半截文件/半截写入(沿 HistoryPage 导入导出
 * 与 WebDAV 恢复先例)。UI 层不设 seam(spec 决策),行为经真机人工验证。
 */
@Destination
@Composable
fun LocalBackupPage(
    navigator: DestinationsNavigator,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }

    // 备份确认弹窗:确认后进 SAF 选位(spec:确认弹窗在前,选位在后)
    val backupConfirmDialogState = rememberDialogState()
    // 恢复勾选状态:弹窗每次打开重置为全选(spec:默认全选,可取消勾选)
    val restoreDialogState = rememberDialogState()
    var restoreHistory by rememberSaveable { mutableStateOf(true) }
    var restoreBlockRules by rememberSaveable { mutableStateOf(true) }
    var restorePreferences by rememberSaveable { mutableStateOf(true) }

    // 确认弹窗通过后启动的 SAF 选位:launcher 回调里无法可靠读取弹窗流程的瞬时状态,
    // 用 pendingBackup 标记"确认已通过、等待选位/落地"
    var pendingBackup by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && pendingBackup && !backingUp && !restoring) {
            pendingBackup = false
            backingUp = true
            coroutineScope.launch {
                exportBackup(context, uri)
                    .onSuccess { result ->
                        context.toastShort(
                            context.getString(
                                R.string.toast_local_backup_success,
                                result.historyCount,
                                result.blockRuleCount,
                                result.preferenceKeyCount,
                            )
                        )
                    }
                    .onFailure { e ->
                        context.toastShort(
                            context.getString(R.string.toast_local_backup_failed, e.toLocalBackupMessage(context))
                        )
                    }
                backingUp = false
            }
        } else {
            pendingBackup = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !backingUp && !restoring) {
            val historyChecked = restoreHistory
            val blockRulesChecked = restoreBlockRules
            val preferencesChecked = restorePreferences
            restoring = true
            coroutineScope.launch {
                importBackup(context, uri, historyChecked, blockRulesChecked, preferencesChecked)
                    .onSuccess { result ->
                        val parts = buildList {
                            if (historyChecked && result.history != null) {
                                add(context.getString(R.string.toast_restore_part_history, result.history.added, result.history.skipped))
                            }
                            if (blockRulesChecked && result.blockRules != null) {
                                add(context.getString(R.string.toast_restore_part_rules, result.blockRules.added, result.blockRules.skipped))
                            }
                            if (preferencesChecked && result.preferencesOverwritten != null) {
                                add(context.getString(R.string.toast_restore_part_prefs, result.preferencesOverwritten))
                            }
                        }
                        context.toastShort(context.getString(R.string.toast_local_restore_success, parts.joinToString("，")))
                        if (historyChecked) {
                            // 浏览记录恢复后刷新历史列表:mark 双 tab 信号,当前 tab 经全局事件
                            // 立即刷;两 tab 都不在组合中时各自重组补刷。
                            // NonCancellable 内执行:恢复期间离开本页 scope 已取消,
                            // emitGlobalEvent 的 launch 不再吞掉(与 HistoryPage 导入先例对齐)
                            withContext(NonCancellable) {
                                HistoryListRefreshSignal.mark()
                                emitGlobalEvent(HistoryListUiEvent.Imported)
                            }
                        }
                    }
                    .onFailure { e ->
                        context.toastShort(
                            context.getString(R.string.toast_local_restore_failed, e.toLocalRestoreMessage(context))
                        )
                    }
                restoring = false
            }
        }
    }

    fun onBackupNow() {
        // 弹窗确认按钮不受页面按钮的 enabled 互斥约束,双击可在 dismiss 生效前进入两次
        // (沿 WebDAV 页 onRestoreConfirmed 的 restoring 守卫先例)
        if (backingUp || restoring) return
        pendingBackup = true
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        exportLauncher.launch("tblite_backup_$timestamp.json")
    }

    MyScaffold(
        backgroundColor = Color.Transparent,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(
                        text = stringResource(id = R.string.title_local_backup),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                    )
                },
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                }
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionCard {
                Button(
                    onClick = { backupConfirmDialogState.show() },
                    // 两个操作互斥:任一进行中另一个禁点(防并发写 DataStore/数据库)
                    enabled = !backingUp && !restoring,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (backingUp) {
                        ButtonProgressIndicator()
                    } else {
                        Text(text = stringResource(id = R.string.btn_backup_now))
                    }
                }
                Button(
                    onClick = {
                        // 每次打开重置为全选(spec:勾选界面默认全选)
                        restoreHistory = true
                        restoreBlockRules = true
                        restorePreferences = true
                        restoreDialogState.show()
                    },
                    enabled = !backingUp && !restoring,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (restoring) {
                        ButtonProgressIndicator()
                    } else {
                        Text(text = stringResource(id = R.string.btn_backup_restore))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 备份确认弹窗(spec:先弹确认弹窗提示覆盖备份中的软件设置,确认才进 SAF 选位,取消零动作)
    ConfirmDialog(
        dialogState = backupConfirmDialogState,
        onConfirm = ::onBackupNow,
        title = { Text(text = stringResource(id = R.string.backup_confirm_dialog_title)) },
    ) {
        Text(text = stringResource(id = R.string.backup_confirm_dialog_message))
    }

    // 恢复勾选对话框(spec:先弹三部分勾选界面,默认全选,确认后进 SAF 选文件)
    ConfirmDialog(
        dialogState = restoreDialogState,
        onConfirm = {
            val historyChecked = restoreHistory
            val blockRulesChecked = restoreBlockRules
            val preferencesChecked = restorePreferences
            // 零勾选的"恢复"只会白选一次文件;直接不出弹窗内确认
            if (!historyChecked && !blockRulesChecked && !preferencesChecked) {
                context.toastShort(R.string.toast_restore_nothing_selected)
            } else {
                importLauncher.launch(arrayOf("application/json"))
            }
        },
        title = { Text(text = stringResource(id = R.string.restore_dialog_title)) },
    ) {
        Column {
            RestoreCheckboxRow(
                label = stringResource(id = R.string.restore_part_history),
                checked = restoreHistory,
                onCheckedChange = { restoreHistory = it },
            )
            RestoreCheckboxRow(
                label = stringResource(id = R.string.restore_part_block_rules),
                checked = restoreBlockRules,
                onCheckedChange = { restoreBlockRules = it },
            )
            RestoreCheckboxRow(
                label = stringResource(id = R.string.restore_part_preferences),
                checked = restorePreferences,
                onCheckedChange = { restorePreferences = it },
            )
        }
    }
}

/**
 * 导出三段备份 JSON 到 [uri]:SAF 流在本层解开,核心 [LocalBackupTransfer.exportTo]
 * 只见流。IO 线程执行(SAF openOutputStream 是阻塞 Binder IPC);NonCancellable:
 * 写文件中途离开本页(scope 取消)仍要完成写入,避免留下截断的半截 JSON
 * (沿 HistoryPage 导出先例)。
 */
private suspend fun exportBackup(context: Context, uri: Uri): Result<LocalBackupTransfer.ExportResult> =
    withContext(Dispatchers.IO + NonCancellable) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                // 数据收集(数据库 + DataStore)与写出同在流的生命周期内完成
                val preferences = context.dataStore.data.first()
                val histories = HistoryUtil.getAllNoLimit()
                val blocks = DatabaseUtil.getAllBlocks()
                LocalBackupTransfer.exportTo(stream, histories, blocks, preferences)
            } ?: throw IllegalStateException("cannot open output stream")
        }
    }

/**
 * 从 [uri] 读取备份 JSON 按勾选恢复:SAF 流解开成流后委托共享编排
 * (经 [LocalBackupTransfer.importFrom])。IO 线程执行;NonCancellable:恢复先解析
 * 后写入,中途取消会留下部分写入的脏状态(spec 恢复零写入/整体完成的语义)。
 */
private suspend fun importBackup(
    context: Context,
    uri: Uri,
    restoreHistory: Boolean,
    restoreBlockRules: Boolean,
    restorePreferences: Boolean,
): Result<BackupRestore.RestoreResult> =
    withContext(Dispatchers.IO + NonCancellable) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                LocalBackupTransfer.importFrom(
                    stream,
                    restoreHistory,
                    restoreBlockRules,
                    restorePreferences,
                    DatabaseHistoryStore,
                    DatabaseBlockStore,
                    context.dataStore,
                )
            } ?: throw IllegalStateException("cannot open input stream")
        }
    }

/** 本地备份失败提示映射:IO 与其余异常展示原始 message——版本守卫在导出侧不会触发(自产文件),仅防御性归一 */
private fun Throwable.toLocalBackupMessage(context: Context): String = when (this) {
    is IOException -> context.getString(R.string.toast_local_restore_reason_io)
    else -> message ?: context.getString(R.string.toast_backup_reason_unknown)
}

/** 本地恢复失败提示映射:版本不兼容/损坏文件/IO 各自明确提示(spec:损坏文件给专属提示) */
private fun Throwable.toLocalRestoreMessage(context: Context): String = when (this) {
    is BackupVersionException -> context.getString(
        R.string.toast_restore_reason_version, backupVersion, supportedVersion
    )
    is BackupFormatException -> context.getString(R.string.toast_local_restore_reason_corrupted)
    is IOException -> context.getString(R.string.toast_local_restore_reason_io)
    else -> message ?: context.getString(R.string.toast_backup_reason_unknown)
}

/** 操作卡片容器:圆角表面,风格对齐项目卡片(ExtendedTheme.colors.card) */
@Composable
private fun ActionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = ExtendedTheme.colors.card,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/** 恢复勾选对话框的单行复选(与 WebDAV 页同款式) */
@Composable
private fun RestoreCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.primary),
        )
        Text(text = label, style = MaterialTheme.typography.body1)
    }
}

/** 按钮内转圈(进行中反馈:按钮内转圈 + 禁点) */
@Composable
private fun ButtonProgressIndicator() {
    CircularProgressIndicator(
        color = LocalContentColor.current,
        strokeWidth = 2.dp,
        modifier = Modifier.size(18.dp),
    )
}
