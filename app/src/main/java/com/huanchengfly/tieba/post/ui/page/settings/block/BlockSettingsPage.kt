package com.huanchengfly.tieba.post.ui.page.settings.block

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.ui.common.prefs.PrefsScreen
import com.huanchengfly.tieba.post.ui.common.prefs.widgets.SwitchPref
import com.huanchengfly.tieba.post.ui.common.prefs.widgets.TextPref
import com.huanchengfly.tieba.post.ui.page.destinations.BlockListPageDestination
import com.huanchengfly.tieba.post.ui.page.settings.LeadingIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.AvatarIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.utils.BlockRuleTransfer
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterialApi::class)
@Destination
@Composable
fun BlockSettingsPage(
    navigator: DestinationsNavigator
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                // NonCancellable：写文件中途离开本页（scope 取消）时仍要完成写入，
                // 避免留下截断的半截 JSON
                withContext(NonCancellable) {
                    runCatching {
                        val blocks = DatabaseUtil.getAllBlocks()
                        BlockRuleTransfer.exportTo(context, uri, blocks)
                    }.onSuccess { count ->
                        context.toastShort(context.getString(R.string.toast_export_block_success, "$count 条"))
                    }.onFailure {
                        context.toastShort(context.getString(R.string.toast_export_block_failed, it.message ?: ""))
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
                // NonCancellable：导入中途离开本页时仍要完成写入与提示
                withContext(NonCancellable) {
                    runCatching {
                        BlockRuleTransfer.importFrom(context, uri)
                    }.onSuccess { result ->
                        context.toastShort(
                            context.getString(R.string.toast_import_block_success, result.added, result.skipped)
                        )
                    }.onFailure {
                        context.toastShort(
                            if (it is IllegalArgumentException) context.getString(R.string.toast_import_block_invalid_file)
                            else context.getString(R.string.toast_import_block_failed, it.message ?: "")
                        )
                    }
                }
            }
        }
    }

    MyScaffold(
        backgroundColor = Color.Transparent,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(
                        text = stringResource(id = R.string.title_block_settings),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                    )
                },
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                }
            )
        },
    ) { paddingValues ->
        PrefsScreen(
            dataStore = context.dataStore,
            dividerThickness = 0.dp,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_block_list),
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.Block,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = { navigator.navigate(BlockListPageDestination) }
                )
            }
            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_export_block_rules),
                    summary = stringResource(id = R.string.summary_export_block_rules),
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.FileUpload,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(Date())
                        exportLauncher.launch("tblite_block_rules_$timestamp.json")
                    }
                )
            }
            prefsItem {
                TextPref(
                    title = stringResource(id = R.string.title_import_block_rules),
                    summary = stringResource(id = R.string.summary_import_block_rules),
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.FileDownload,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    }
                )
            }
            prefsItem {
                SwitchPref(
                    key = "hideBlockedContent",
                    title = stringResource(id = R.string.settings_hide_blocked_content),
                    defaultChecked = false
                ) {
                    LeadingIcon {
                        AvatarIcon(
                            icon = Icons.Outlined.HideSource,
                            size = Sizes.Small,
                            contentDescription = null,
                        )
                    }
                }
            }
            prefsItem {
                SwitchPref(
                    key = "blockVideo",
                    title = stringResource(id = R.string.settings_block_video),
                    summary = stringResource(id = R.string.settings_block_video_summary),
                    defaultChecked = false,
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.VideocamOff,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    }
                )
            }

            prefsItem {
                SwitchPref(
                    key = "showFollowedOnly",
                    title = stringResource(id = R.string.settings_show_followed_only),
                    summary = stringResource(id = R.string.settings_show_followed_only_summary),
                    defaultChecked = false,
                    leadingIcon = {
                        LeadingIcon {
                            AvatarIcon(
                                icon = Icons.Outlined.Block,
                                size = Sizes.Small,
                                contentDescription = null,
                            )
                        }
                    }
                )
            }
        }
    }
}