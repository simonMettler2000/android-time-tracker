package com.simonmettler.timetracker.data

import com.simonmettler.timetracker.model.TimeRules

internal sealed interface CalendarItem {
    data class Entry(val entry: CalendarEntry) : CalendarItem
    data class Gap(val startAtMs: Long, val endAtMs: Long) : CalendarItem
}

/** Entries arrive in start order, with at most one neighbor on either side of the day. */
internal fun calendarTimeline(entries: List<CalendarEntry>, from: Long, to: Long, now: Long): List<CalendarItem> = buildList {
    var previousEnd: Long? = null
    for (entry in entries) {
        val interval = entry.interval
        val start = interval.startAtMs
        val end = interval.endAtMs
        if (end == start) continue
        previousEnd?.let {
            val gapStart = maxOf(it, from)
            val gapEnd = minOf(start, to, now)
            if (gapStart < gapEnd) add(CalendarItem.Gap(gapStart, gapEnd))
        }
        if (TimeRules.clippedDuration(start, end, from, to, now) > 0 ||
            (end == null && start < to && now >= start && now >= from)
        ) {
            add(CalendarItem.Entry(entry))
        }
        previousEnd = end
    }
}
