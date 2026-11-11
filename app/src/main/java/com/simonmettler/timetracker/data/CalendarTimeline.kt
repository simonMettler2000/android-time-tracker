package com.simonmettler.timetracker.data

import com.simonmettler.timetracker.model.TimeRules

internal sealed interface CalendarItem {
    data class Entry(
        val entry: CalendarEntry,
        val combinePrevious: TimeInterval?,
        val combineNext: TimeInterval?
    ) : CalendarItem
    data class Gap(val startAtMs: Long, val endAtMs: Long) : CalendarItem
}

internal fun TimeInterval.canCombineWith(next: TimeInterval): Boolean {
    val end = endAtMs ?: return false
    return end > startAtMs && end == next.startAtMs && activityId == next.activityId &&
        next.endAtMs != next.startAtMs
}

/** Entries arrive in start order, with at most one neighbor on either side of the day. */
internal fun calendarTimeline(entries: List<CalendarEntry>, from: Long, to: Long, now: Long): List<CalendarItem> = buildList {
    val occupied = entries.filter { it.interval.endAtMs != it.interval.startAtMs }
    for ((index, entry) in occupied.withIndex()) {
        val interval = entry.interval
        val start = interval.startAtMs
        val end = interval.endAtMs
        val previous = occupied.getOrNull(index - 1)?.interval
        previous?.endAtMs?.let {
            val gapStart = maxOf(it, from)
            val gapEnd = minOf(start, to, now)
            if (gapStart < gapEnd) add(CalendarItem.Gap(gapStart, gapEnd))
        }
        if (TimeRules.clippedDuration(start, end, from, to, now) > 0 ||
            (end == null && start < to && now >= start && now >= from)
        ) {
            val next = occupied.getOrNull(index + 1)?.interval
            add(CalendarItem.Entry(
                entry,
                previous?.takeIf { it.canCombineWith(interval) },
                next?.takeIf { interval.canCombineWith(it) }
            ))
        }
    }
}
