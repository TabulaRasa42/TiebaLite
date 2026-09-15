package com.huanchengfly.tieba.post.ui.page.settings.backup

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.huanchengfly.tieba.post.ui.widgets.compose.TextButton
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.DatabaseBlockStore
import com.huanchengfly.tieba.post.utils.DatabaseHistoryStore
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.huanchengfly.tieba.post.utils.webdav.BackupFormatException
import com.huanchengfly.tieba.post.utils.webdav.BackupVersionException
import com.huanchengfly.tieba.post.utils.webdav.CipherUnavailableException
import com.huanchengfly.tieba.post.utils.webdav.KeystorePasswordCipher
import com.huanchengfly.tieba.post.utils.webdav.OkHttpWebDavClient
import com.huanchengfly.tieba.post.utils.webdav.WebDavBackupRestore
import com.huanchengfly.tieba.post.utils.webdav.WebDavCredentialStore
import com.huanchengfly.tieba.post.utils.webdav.WebDavCredentialsState
import com.huanchengfly.tieba.post.utils.webdav.WebDavException
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * WebDAV 备份配置页(04 号票):上半部分配置卡片(服务器地址/用户名/密码/远程路径),
 * "测试连接"(经 WebDavClient mkcol,远程目录不存在自动创建)与"保存配置"(经凭据模块持久化,
 * 进页回填);下半部分"立即备份"/"从备份恢复"(05 号票接线):备份收集三部分数据经
 * [WebDavBackupRestore.upload] 上传固定文件名;恢复先弹三部分勾选(默认全选),
 * 确认后经 [WebDavBackupRestore.restore] 分发写入。
 *
 * 凭据一律取表单当前值([filledConfig]),表单完整才放行(测试连接/保存/备份/恢复四操作
 * 同一校验)——不做"表单缺项回退已保存凭据"的静默回退:那会让操作打到与界面显示不一致的
 * 旧服务器上。Keystore 解密进页回填在 IO 线程执行(load 与 loadFlow 的 flowOn(IO) 同理)。
 *
 * 操作进行中按钮内转圈 + 四个按钮互斥禁点(含弹窗确认入口的 restoring 守卫);上传/恢复
 * 包在 NonCancellable 中断页也不留半截写入(沿 HistoryPage 导入导出先例)。
 *
 * UI 层不设 seam(spec 决策),行为经真机人工验证。
 */
@OptIn(ExperimentalMaterialApi::class)
@Destination
@Composable
fun BackupSettingsPage(
    navigator: DestinationsNavigator,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialStore = remember { WebDavCredentialStore(context.dataStore, KeystorePasswordCipher()) }
    // 页级共享 OkHttp 客户端:OkHttpWebDavClient 内部会派生 client,共享底层连接池与线程池;
    // 每次操作 new 一个会让旧实例的空闲线程/连接只能等 GC 回收
    val okHttpClient = remember { OkHttpClient() }

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var remotePath by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    // 进页回填只执行一次:旋转屏等重组不重跑,否则已保存凭据会覆盖用户未保存的输入
    var backfilled by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }

    // 恢复勾选状态:弹窗每次打开重置为全选(spec:默认全选,可取消勾选)
    val restoreDialogState = rememberDialogState()
    var restoreHistory by rememberSaveable { mutableStateOf(true) }
    var restoreBlockRules by rememberSaveable { mutableStateOf(true) }
    var restorePreferences by rememberSaveable { mutableStateOf(true) }

    // 进页回填:已保存配置填回表单;密码不可解(降级态)填其余三项并提示重输密码。
    // Keystore 解密在 IO 线程执行,不占主帧(厂商 ROM 上解密可达数十毫秒)
    LaunchedEffect(Unit) {
        if (backfilled) return@LaunchedEffect
        backfilled = true
        val state = withContext(Dispatchers.IO) { credentialStore.load() }
        when (state) {
            is WebDavCredentialsState.Credentials -> {
                serverUrl = state.serverUrl
                username = state.username
                password = state.password
                remotePath = state.remotePath
            }
            is WebDavCredentialsState.NeedsPassword -> {
                serverUrl = state.serverUrl
                username = state.username
                remotePath = state.remotePath
                context.toastShort(R.string.toast_backup_needs_password)
            }
            WebDavCredentialsState.Empty -> Unit
        }
    }

    /**
     * 表单当前值快照;缺项提示引导并返回 null——四个操作(测试连接/保存/备份/恢复)
     * 共用同一校验,缺什么提示什么:三项任一缺失提示三字段清单,密码缺失单独提示
     * (requirePassword 且密码为空时,三项可能都已填好,通用提示会误导)。
     */
    fun filledConfig(requirePassword: Boolean): FormConfig? {
        val config = FormConfig(serverUrl.trim(), username.trim(), password, remotePath.trim())
        if (config.serverUrl.isEmpty() || config.username.isEmpty() || config.remotePath.isEmpty()) {
            context.toastShort(R.string.toast_backup_config_incomplete)
            return null
        }
        if (requirePassword && config.password.isEmpty()) {
            context.toastShort(R.string.toast_backup_password_required)
            return null
        }
        return config
    }

    fun onTestConnection() {
        val config = filledConfig(requirePassword = true) ?: return
        testing = true
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = OkHttpWebDavClient(
                        config.serverUrl, config.username, config.password, okHttpClient
                    )
                    // mkcol 幂等:远程目录已存在(405)视为成功,不存在则自动逐级创建
                    client.mkcol(config.remotePath)
                }
                context.toastShort(R.string.toast_backup_test_success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toastShort(
                    context.getString(R.string.toast_backup_test_failed, e.toUserMessage(context))
                )
            } finally {
                testing = false
            }
        }
    }

    fun onSaveConfig() {
        val config = filledConfig(requirePassword = false) ?: return
        saving = true
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    credentialStore.save(config.serverUrl, config.username, config.password, config.remotePath)
                }
                context.toastShort(R.string.toast_backup_config_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CipherUnavailableException) {
                // Keystore 系统级失败:明确提示密码未保存(ADR-0002 降级不崩溃)
                context.toastShort(R.string.toast_backup_cipher_unavailable)
            } catch (e: Exception) {
                context.toastShort(
                    context.getString(R.string.toast_backup_save_failed, e.message ?: "")
                )
            } finally {
                saving = false
            }
        }
    }

    fun onBackupNow() {
        val config = filledConfig(requirePassword = true) ?: return
        backingUp = true
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    val client = OkHttpWebDavClient(
                        config.serverUrl, config.username, config.password, okHttpClient
                    )
                    val backupRestore = WebDavBackupRestore(
                        client,
                        DatabaseHistoryStore,
                        DatabaseBlockStore,
                        context.dataStore,
                    )
                    // 数据收集(数据库 + DataStore)与上传同在 IO;NonCancellable:
                    // 上传中途离开本页仍要完成,避免远端留下半截备份 JSON
                    val preferences = context.dataStore.data.first()
                    backupRestore.upload(
                        config.remotePath,
                        HistoryUtil.getAllNoLimit(),
                        DatabaseUtil.getAllBlocks(),
                        preferences,
                    )
                }
                // 成功提示带各部分数量(与恢复提示对称)
                context.toastShort(
                    context.getString(
                        R.string.toast_backup_success,
                        result.historyCount,
                        result.blockRuleCount,
                        result.preferenceKeyCount,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toastShort(
                    context.getString(R.string.toast_backup_failed, e.toUserMessage(context))
                )
            } finally {
                backingUp = false
            }
        }
    }

    fun onRestoreConfirmed() {
        // 弹窗确认按钮不受页面按钮的 enabled 四态约束,双击可在 dismiss 生效前进入两次
        if (restoring) return
        val restoreHistoryChecked = restoreHistory
        val restoreBlockRulesChecked = restoreBlockRules
        val restorePreferencesChecked = restorePreferences
        // 零勾选的"恢复"只会白下载一次还弹残缺提示;直接不出弹窗内确认
        if (!restoreHistoryChecked && !restoreBlockRulesChecked && !restorePreferencesChecked) {
            context.toastShort(R.string.toast_restore_nothing_selected)
            return
        }
        restoring = true
        coroutineScope.launch {
            try {
                val config = filledConfig(requirePassword = true) ?: return@launch
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    val client = OkHttpWebDavClient(
                        config.serverUrl, config.username, config.password, okHttpClient
                    )
                    val backupRestore = WebDavBackupRestore(
                        client,
                        DatabaseHistoryStore,
                        DatabaseBlockStore,
                        context.dataStore,
                    )
                    // 下载→解析→写入一气呵成;NonCancellable:restore 先解析后写入,
                    // 中途取消会留下部分写入的脏状态(spec 恢复零写入/整体完成的语义)
                    val backup = backupRestore.download(config.remotePath)
                    backupRestore.restore(backup, restoreHistoryChecked, restoreBlockRulesChecked, restorePreferencesChecked)
                }
                // 成功提示含各部分数量:仅列勾选部分(spec:恢复成功提示含各部分数量)
                val parts = buildList {
                    if (restoreHistoryChecked && result.history != null) {
                        add(context.getString(R.string.toast_restore_part_history, result.history.added, result.history.skipped))
                    }
                    if (restoreBlockRulesChecked && result.blockRules != null) {
                        add(context.getString(R.string.toast_restore_part_rules, result.blockRules.added, result.blockRules.skipped))
                    }
                    if (restorePreferencesChecked && result.preferencesOverwritten != null) {
                        add(context.getString(R.string.toast_restore_part_prefs, result.preferencesOverwritten))
                    }
                }
                context.toastShort(context.getString(R.string.toast_restore_success, parts.joinToString("，")))
                if (restoreHistoryChecked) {
                    // 浏览记录恢复后刷新历史列表:mark 双 tab 信号,当前 tab 经全局事件
                    // 立即刷;两 tab 都不在组合中(本页发起恢复)时各自重组补刷
                    HistoryListRefreshSignal.mark()
                    emitGlobalEvent(HistoryListUiEvent.Imported)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toastShort(
                    context.getString(R.string.toast_restore_failed, e.toRestoreMessage(context))
                )
            } finally {
                restoring = false
            }
        }
    }

    MyScaffold(
        backgroundColor = Color.Transparent,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(
                        text = stringResource(id = R.string.title_backup_settings),
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
            SectionTitle(text = stringResource(id = R.string.backup_section_config))
            ConfigCard {
                ConfigTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = stringResource(id = R.string.backup_field_server_url),
                    hint = stringResource(id = R.string.backup_hint_server_url),
                    keyboardType = KeyboardType.Uri,
                )
                ConfigTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(id = R.string.backup_field_username),
                    keyboardType = KeyboardType.Email,
                )
                ConfigTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(id = R.string.backup_field_password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    imeAction = ImeAction.Done,
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff
                                else Icons.Rounded.Visibility,
                                contentDescription = stringResource(id = R.string.desc_backup_password_visible),
                                tint = ExtendedTheme.colors.textSecondary,
                            )
                        }
                    },
                )
                ConfigTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = stringResource(id = R.string.backup_field_remote_path),
                    hint = stringResource(id = R.string.backup_hint_remote_path),
                )
                Text(
                    text = stringResource(id = R.string.backup_hint_jianguoyun),
                    style = MaterialTheme.typography.caption,
                    color = ExtendedTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = ::onTestConnection,
                        // 四个操作互斥:任一进行中其余全部禁点
                        enabled = !testing && !saving && !backingUp && !restoring,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (testing) {
                            ButtonProgressIndicator()
                        } else {
                            Text(text = stringResource(id = R.string.btn_backup_test_connection))
                        }
                    }
                    Button(
                        onClick = ::onSaveConfig,
                        enabled = !testing && !saving && !backingUp && !restoring,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (saving) {
                            ButtonProgressIndicator()
                        } else {
                            Text(text = stringResource(id = R.string.btn_backup_save_config))
                        }
                    }
                }
            }

            SectionTitle(text = stringResource(id = R.string.backup_section_actions))
            ConfigCard {
                Button(
                    onClick = ::onBackupNow,
                    // 四个操作互斥:任一进行中其余全部禁点(防并发写 DataStore/数据库与重复上传)
                    enabled = !testing && !saving && !backingUp && !restoring,
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
                    enabled = !testing && !saving && !backingUp && !restoring,
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

    // 恢复勾选对话框(spec:先弹三部分勾选界面,默认全选,确认后开始恢复)
    ConfirmDialog(
        dialogState = restoreDialogState,
        onConfirm = ::onRestoreConfirmed,
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

/** 表单当前值快照(测试连接/保存/备份/恢复四操作共用的校验产物) */
private data class FormConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remotePath: String,
)

/**
 * WebDavException 四态 → 用户可读提示(spec:失败按错误类型提示);
 * 非法 URL 的 IllegalArgumentException 归一为地址格式提示,
 * 其余本地异常(数据库/DataStore IO 等)展示原始 message——它们与服务器地址无关,
 * 统一塞"地址无效"会误导排障。
 */
private fun Exception.toUserMessage(context: Context): String = when (this) {
    is WebDavException.Network -> context.getString(R.string.toast_backup_reason_network)
    is WebDavException.Auth -> context.getString(R.string.toast_backup_reason_auth)
    is WebDavException.Http -> context.getString(R.string.toast_backup_reason_http, code)
    is WebDavException.InvalidPath -> context.getString(R.string.toast_backup_reason_invalid_path)
    is IllegalArgumentException -> context.getString(R.string.toast_backup_reason_invalid_url)
    else -> message ?: context.getString(R.string.toast_backup_reason_unknown)
}

/**
 * 恢复失败的提示映射:在四态之外补备份侧异常——404 特判"还没有备份"
 * (远端固定文件名不存在是最常见的首次恢复场景),版本不兼容/文件损坏各自明确提示。
 */
private fun Exception.toRestoreMessage(context: Context): String = when (this) {
    is WebDavException.Http -> if (code == 404) {
        context.getString(R.string.toast_restore_reason_no_backup)
    } else {
        context.getString(R.string.toast_backup_reason_http, code)
    }
    is BackupVersionException -> context.getString(
        R.string.toast_restore_reason_version, backupVersion, supportedVersion
    )
    is BackupFormatException -> context.getString(R.string.toast_restore_reason_corrupted)
    else -> toUserMessage(context)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.subtitle1,
        fontWeight = FontWeight.Bold,
        color = ExtendedTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 配置卡片容器:圆角表面,风格对齐项目卡片(ExtendedTheme.colors.card) */
@Composable
private fun ConfigCard(content: @Composable () -> Unit) {
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

@Composable
private fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        placeholder = hint?.let { h -> { Text(text = h) } },
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        singleLine = true,
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            cursorColor = ExtendedTheme.colors.primary,
            focusedBorderColor = ExtendedTheme.colors.primary,
            focusedLabelColor = ExtendedTheme.colors.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 恢复勾选对话框的单行复选 */
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
