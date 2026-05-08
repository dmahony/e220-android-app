package com.dmahony.e220chat

import java.util.concurrent.TimeUnit

object TimestampFormatter {

    fun relativeTimestamp(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val elapsedMs = nowMs - epochMs
        if (elapsedMs < 0) return "Just now"

        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
        val days = TimeUnit.MILLISECONDS.toDays(elapsedMs)

        return when {
            seconds < 10 -> "Just now"
            seconds < 60 -> "${seconds}s ago"
            minutes == 1L -> "1m ago"
            minutes < 60 -> "${minutes}m ago"
            hours == 1L -> "1h ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> formatDate(epochMs)
        }
    }

    fun shouldShowDateSeparator(currentMsgMs: Long, previousMsgMs: Long?): Boolean {
        if (previousMsgMs == null) return true
        val dayDiff = daysBetween(previousMsgMs, currentMsgMs)
        return dayDiff >= 1
    }

    fun dateSeparator(epochMs: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - epochMs)
        return when (days) {
            0L -> "Today"
            1L -> "Yesterday"
            else -> formatDate(epochMs)
        }
    }

    private fun formatDate(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    private fun daysBetween(fromMs: Long, toMs: Long): Long {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = fromMs }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = toMs }
        // Reset time fields to compare only dates
        listOf(cal1, cal2).forEach { cal ->
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
        }
        return TimeUnit.MILLISECONDS.toDays(cal2.timeInMillis - cal1.timeInMillis)
    }
}
