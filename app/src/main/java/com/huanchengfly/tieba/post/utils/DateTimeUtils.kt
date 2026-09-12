package com.huanchengfly.tieba.post.utils

import android.content.Context
import com.huanchengfly.tieba.post.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

object DateTimeUtils {
    @JvmStatic
    fun getRelativeTimeString(
        context: Context,
        timestampString: String
    ): String {
        return getRelativeTimeString(context, fixTimestamp(timestampString))
    }

    @JvmStatic
    fun getRelativeTimeString(
        context: Context,
        timestamp: Long
    ): String {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = fixTimestamp(timestamp)
        }
        val currentCalendar = Calendar.getInstance()
        return if (currentCalendar.after(calendar)) {
            if (calendar.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR)) {
                if (calendar.get(Calendar.DAY_OF_MONTH) == currentCalendar.get(Calendar.DAY_OF_MONTH) &&
                    calendar.get(Calendar.MONTH) == currentCalendar.get(Calendar.MONTH)
                ) {
                    if (calendar.get(Calendar.HOUR_OF_DAY) == currentCalendar.get(Calendar.HOUR_OF_DAY)) {
                        if (calendar.get(Calendar.MINUTE) == currentCalendar.get(Calendar.MINUTE)) {
                            if (calendar.get(Calendar.SECOND) == currentCalendar.get(Calendar.SECOND)) {
                                calendar.format(context.getString(R.string.relative_date_after))
                            } else {
                                context.getString(
                                    R.string.relative_date_second,
                                    currentCalendar.get(Calendar.SECOND) - calendar.get(Calendar.SECOND)
                                )
                            }
                        } else {
                            context.getString(
                                R.string.relative_date_minute,
                                currentCalendar.get(Calendar.MINUTE) - calendar.get(Calendar.MINUTE)
                            )
                        }
                    } else {
                        context.getString(
                            R.string.relative_date_today,
                            calendar.format("HH:mm")
                        )
                    }
                } else {
                    calendar.format("MM-dd HH:mm")
                }
            } else {
                calendar.format("yyyy-MM-dd HH:mm")
            }
        } else {
            calendar.format(context.getString(R.string.relative_date_after))
        }
    }

    private fun Calendar.clearTime() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }

    /**
     * 某天的起始时间戳（当日 00:00:00 本地时区）
     */
    @JvmStatic
    fun dayStart(year: Int, month: Int, dayOfMonth: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month, dayOfMonth, 0, 0, 0)
        return calendar.timeInMillis
    }

    /**
     * 某天的结束时间戳（当日 23:59:59.999 本地时区）
     */
    @JvmStatic
    fun dayEnd(year: Int, month: Int, dayOfMonth: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month, dayOfMonth, 23, 59, 59)
        return calendar.timeInMillis + 999
    }

    /**
     * 时间戳对应日历的年/月(0-based)/日
     */
    @JvmStatic
    fun toCalendarFields(timestamp: Long): Triple<Int, Int, Int> {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return Triple(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    @JvmStatic
    fun isToday(timestamp: Long): Boolean {
        val date = DateFormat.getDateInstance().format(timestamp)
        val todayDate = DateFormat.getDateInstance().format(System.currentTimeMillis())
        return date == todayDate
    }

    private fun Calendar.format(pattern: String): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timeInMillis))
    }

    private fun fixTimestamp(timestamp: Long): Long {
        return fixTimestamp(timestamp.toString())
    }

    private fun fixTimestamp(timestampString: String): Long {
        val timestampStrBuilder: StringBuilder = StringBuilder(timestampString)
        while (timestampStrBuilder.length < 13) {
            timestampStrBuilder.append("0")
        }
        return timestampStrBuilder.toString().toLong()
    }
}