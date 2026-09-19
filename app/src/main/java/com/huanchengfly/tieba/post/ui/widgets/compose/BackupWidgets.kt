package com.huanchengfly.tieba.post.ui.widgets.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.utils.backup.BackupRestore

/**
 * 备份页公共小组件(02 号票从 WebDAV 页与本地备份页抽取,原两页逐字重复):
 * 卡片容器、按钮内转圈、恢复勾选行、恢复成功提示分段拼装。两种备份介质共用,保持体验一致。
 */

/** 圆角卡片容器:风格对齐项目卡片(ExtendedTheme.colors.card),备份页配置/操作区块共用 */
@Composable
fun ActionCard(content: @Composable () -> Unit) {
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

/** 按钮内转圈(进行中反馈:按钮内转圈 + 禁点) */
@Composable
fun ButtonProgressIndicator() {
    CircularProgressIndicator(
        color = LocalContentColor.current,
        strokeWidth = 2.dp,
        modifier = Modifier.size(18.dp),
    )
}

/** 恢复勾选对话框的单行复选 */
@Composable
fun RestoreCheckboxRow(
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

/**
 * 恢复成功提示的分段拼装(仅列勾选部分):WebDAV 页与本地备份页共用同一组
 * toast_restore_part_* 键与同一拼装,防止两介质提示漂移。
 */
fun buildRestoreSummaryParts(
    context: Context,
    result: BackupRestore.RestoreResult,
    historyChecked: Boolean,
    blockRulesChecked: Boolean,
    preferencesChecked: Boolean,
): List<String> = buildList {
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
