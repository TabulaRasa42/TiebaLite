package com.huanchengfly.tieba.post.ui.page.settings.backup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.ui.common.prefs.PrefsScreen
import com.huanchengfly.tieba.post.ui.common.prefs.widgets.TextPref
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.BackupSettingsPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.LocalBackupPageDestination
import com.huanchengfly.tieba.post.ui.page.settings.LeadingIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.AvatarIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * 备份与恢复中枢页(02 号票):设置第一层"备份与恢复"的总入口,下辖"本地备份"与
 * "WebDAV 备份"两个子入口,能力不丢、入口收敛到一处。
 *
 * 布局对齐 BlockSettingsPage 的二级设置页先例:PrefsScreen + TextPref 子入口
 * (LeadingIcon + AvatarIcon,Sizes.Small),图标为 material-icons-extended Outlined 系列
 * (本地 Save、WebDAV CloudUpload、中枢 Backup)。
 */
@OptIn(ExperimentalMaterialApi::class)
@Destination
@Composable
fun BackupHubPage(
    navigator: DestinationsNavigator,
) {
    ProvideNavigator(navigator = navigator) {
        Scaffold(
            backgroundColor = Color.Transparent,
            topBar = {
                TitleCentredToolbar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.title_backup_hub),
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                        )
                    },
                    navigationIcon = {
                        BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                    }
                )
            },
        ) {
            PrefsScreen(
                dataStore = LocalContext.current.dataStore,
                dividerThickness = 0.dp,
                modifier = Modifier
                    .padding(it)
                    .fillMaxSize(),
            ) {
                prefsItem {
                    TextPref(
                        title = stringResource(id = R.string.title_local_backup),
                        summary = stringResource(id = R.string.summary_local_backup),
                        leadingIcon = {
                            LeadingIcon {
                                AvatarIcon(
                                    icon = Icons.Outlined.Save,
                                    size = Sizes.Small,
                                    contentDescription = null,
                                )
                            }
                        },
                        darkenOnDisable = false,
                        onClick = { navigator.navigate(LocalBackupPageDestination) }
                    )
                }
                prefsItem {
                    TextPref(
                        title = stringResource(id = R.string.title_webdav_backup),
                        summary = stringResource(id = R.string.summary_webdav_backup),
                        leadingIcon = {
                            LeadingIcon {
                                AvatarIcon(
                                    icon = Icons.Outlined.CloudUpload,
                                    size = Sizes.Small,
                                    contentDescription = null,
                                )
                            }
                        },
                        darkenOnDisable = false,
                        onClick = { navigator.navigate(BackupSettingsPageDestination) }
                    )
                }
            }
        }
    }
}
