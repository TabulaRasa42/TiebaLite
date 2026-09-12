package com.huanchengfly.tieba.post.ui.widgets.compose.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.ContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ui.widgets.compose.Dialog
import com.huanchengfly.tieba.post.ui.widgets.compose.DialogNegativeButton
import com.huanchengfly.tieba.post.ui.widgets.compose.DialogPositiveButton
import com.huanchengfly.tieba.post.ui.widgets.compose.DialogState
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState

/**
 * 年/月/日 三列滚轮日期选择器，与项目 TimePicker 同风格。
 *
 * 日列以 key(year, month) 重建：切换年月时日列重新定位到钳制后的选中日
 * （如 1月31日 切到 2月 自动落到 2月28日），避免无限循环列表取模错位。
 *
 * @param year 选中年
 * @param month 选中月（1-based）
 * @param dayOfMonth 选中日（1-based，超出当月天数时显示为钳制值）
 * @param minYear 可选最小年（通常取库中最早记录年份）
 * @param maxYear 可选最大年（通常取当前年）
 * @param onDateChanged 任一列变化时回调，参数为 (year, month, dayOfMonth)
 */
@Composable
fun WheelDatePicker(
    year: Int,
    month: Int,
    dayOfMonth: Int,
    minYear: Int,
    maxYear: Int,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 32.dp,
    divider: NumberPickerDivider = NumberPickerDivider(),
    onDateChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
) {
    val years = (minYear..maxYear).toList()
    val months = (1..12).toList()
    val days = (1..daysInMonth(year, month)).toList()

    val itemStyles = ItemStyles(
        defaultTextStyle = MaterialTheme.typography.body1.copy(
            color = MaterialTheme.colors.onBackground.copy(alpha = ContentAlpha.medium),
            fontWeight = FontWeight.Normal
        ),
        selectedTextStyle = MaterialTheme.typography.h5.copy(
            color = MaterialTheme.colors.onBackground.copy(alpha = ContentAlpha.high),
            fontWeight = FontWeight.Medium
        )
    )

    Row(horizontalArrangement = Arrangement.Center, modifier = modifier.fillMaxWidth()) {
        WheelPicker(
            items = years,
            selectedItem = year,
            itemHeight = itemHeight,
            divider = divider,
            itemStyles = itemStyles,
            itemToString = { "$it" },
            modifier = Modifier.weight(1.2f),
            onItemChanged = { onDateChanged(it, month, dayOfMonth) }
        )
        WheelPicker(
            items = months,
            selectedItem = month,
            itemHeight = itemHeight,
            divider = divider,
            itemStyles = itemStyles,
            itemToString = { "$it 月" },
            modifier = Modifier.weight(1f),
            onItemChanged = { onDateChanged(year, it, dayOfMonth) }
        )
        // 年/月变化时重建日列，让选中日重新钳制定位（跨月溢出如 31 日 → 28 日）
        key(year, month) {
            WheelPicker(
                items = days,
                selectedItem = dayOfMonth.coerceAtMost(days.size),
                itemHeight = itemHeight,
                divider = divider,
                itemStyles = itemStyles,
                itemToString = { "$it 日" },
                modifier = Modifier.weight(1f),
                onItemChanged = { onDateChanged(year, month, it) }
            )
        }
    }
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    else -> 30
}

@Composable
fun WheelDatePickerDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDayOfMonth: Int,
    minYear: Int,
    maxYear: Int,
    title: String,
    modifier: Modifier = Modifier,
    dialogState: DialogState = rememberDialogState(),
    onConfirm: (Int, Int, Int) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var selectedYear by remember { mutableStateOf(initialYear) }
    var selectedMonth by remember { mutableStateOf(initialMonth) }
    var selectedDay by remember { mutableStateOf(initialDayOfMonth) }

    // 每次对话框打开（show 变 true）都把滚轮重置为 initial 值：
    // remember 无 key 的状态会跨打开残留，第二次打开时停在已过时的日期
    LaunchedEffect(dialogState.show) {
        if (dialogState.show) {
            selectedYear = initialYear
            selectedMonth = initialMonth
            selectedDay = initialDayOfMonth
        }
    }

    Dialog(
        modifier = modifier,
        dialogState = dialogState,
        onDismiss = onCancel,
        title = { Text(text = title) },
        buttons = {
            // 确定时钳制日值：状态中的日可能因切月暂时超出当月天数
            DialogPositiveButton(text = stringResource(id = R.string.button_sure_default)) {
                val maxDay = daysInMonth(selectedYear, selectedMonth)
                onConfirm(
                    selectedYear,
                    selectedMonth,
                    selectedDay.coerceAtMost(maxDay)
                )
            }
            DialogNegativeButton(text = stringResource(id = R.string.button_cancel), onClick = onCancel)
        },
    ) {
        WheelDatePicker(
            year = selectedYear,
            month = selectedMonth,
            dayOfMonth = selectedDay,
            minYear = minYear,
            maxYear = maxYear,
            modifier = Modifier.height(150.dp),
            onDateChanged = { y, m, d ->
                selectedYear = y
                selectedMonth = m
                selectedDay = d
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
