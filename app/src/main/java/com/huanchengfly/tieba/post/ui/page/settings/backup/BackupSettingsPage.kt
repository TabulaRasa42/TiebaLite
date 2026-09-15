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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
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
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Button
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TextButton
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.utils.webdav.CipherUnavailableException
import com.huanchengfly.tieba.post.utils.webdav.KeystorePasswordCipher
import com.huanchengfly.tieba.post.utils.webdav.OkHttpWebDavClient
import com.huanchengfly.tieba.post.utils.webdav.WebDavCredentialStore
import com.huanchengfly.tieba.post.utils.webdav.WebDavCredentialsState
import com.huanchengfly.tieba.post.utils.webdav.WebDavException
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * WebDAV 备份配置页(04 号票):上半部分配置卡片(服务器地址/用户名/密码/远程路径),
 * "测试连接"(经 WebDavClient mkcol,远程目录不存在自动创建)与"保存配置"(经凭据模块持久化,
 * 进页回填);下半部分"立即备份"/"从备份恢复"按钮此票仅放置并禁用(05 号票接线)。
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

    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remotePath by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // 进页回填:已保存配置填回表单;密码不可解(降级态)填其余三项并提示重输密码
    LaunchedEffect(Unit) {
        when (val state = credentialStore.load()) {
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

    /** 表单当前值快照;缺项提示引导并返回 null(凭据未保存时的引导提示,不崩溃不静默) */
    fun filledConfig(requirePassword: Boolean): FormConfig? {
        val config = FormConfig(serverUrl.trim(), username.trim(), password, remotePath.trim())
        if (config.serverUrl.isEmpty() || config.username.isEmpty() || config.remotePath.isEmpty()
            || (requirePassword && config.password.isEmpty())
        ) {
            context.toastShort(R.string.toast_backup_config_incomplete)
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
                        config.serverUrl, config.username, config.password, OkHttpClient()
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
                        enabled = !testing && !saving,
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
                        enabled = !testing && !saving,
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
                // 05 号票接线:备份/恢复操作按钮,此票仅放置并禁用
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.btn_backup_now))
                }
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.btn_backup_restore))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 表单当前值快照(测试连接/保存用) */
private data class FormConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remotePath: String,
)

/**
 * WebDavException 四态 → 用户可读提示(spec:失败按错误类型提示);
 * 其余异常(非法 URL 的 IllegalArgumentException 等)归一为地址格式提示。
 */
private fun Exception.toUserMessage(context: Context): String = when (this) {
    is WebDavException.Network -> context.getString(R.string.toast_backup_reason_network)
    is WebDavException.Auth -> context.getString(R.string.toast_backup_reason_auth)
    is WebDavException.Http -> context.getString(R.string.toast_backup_reason_http, code)
    is WebDavException.InvalidPath -> context.getString(R.string.toast_backup_reason_invalid_path)
    else -> context.getString(R.string.toast_backup_reason_invalid_url)
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

/** 按钮内转圈(进行中反馈:按钮内转圈 + 禁点) */
@Composable
private fun ButtonProgressIndicator() {
    CircularProgressIndicator(
        color = LocalContentColor.current,
        strokeWidth = 2.dp,
        modifier = Modifier.size(18.dp),
    )
}
