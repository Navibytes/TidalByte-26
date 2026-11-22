package com.tidalbyte.stridesync.ui

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * FAKE profile and walk history for the UI. The app doesn't store sessions yet (milestone 6 was skipped), so this
 * mirrors Eleanor V. in the portal's mock data (portal/src/data/mock-data.json): the same 15 walks over 3 weeks.
 * Dates count back from today, so "today's walk" stays today on demo day. Every stat on Profile/History is derived
 * from [walks], so the screens agree with each other.
 */
object DemoProfile {
    const val firstName = "Eleanor"
    const val fullName = "Eleanor V."
    const val initials = "EV"

    data class Walk(
        val daysAgo: Int,
        val minuteOfDay: Int,
        val naturalSpm: Double,
        val withBeatSpm: Double,
        val peakSpm: Double,
        val inStep: Double,
        val durationSec: Int,
        val rampSteps: Int,
        val strideCv: Double,
        val steps: Int,
    ) {
        val date: LocalDate get() = LocalDate.now().minusDays(daysAgo.toLong())
        val time: LocalTime get() = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    }

    /** Oldest first. */
    val walks = listOf(
        Walk(20, 930, 88.0, 88.7, 89.7, 0.74, 795, 10, 0.053, 1175),
        Walk(19, 1155, 86.6, 88.4, 89.0, 0.78, 595, 7, 0.058, 874),
        Walk(18, 1125, 85.2, 88.6, 89.2, 0.76, 835, 9, 0.064, 1227),
        Walk(17, 1110, 88.0, 90.7, 91.5, 0.82, 820, 10, 0.051, 1233),
        Walk(16, 1200, 88.3, 90.6, 91.0, 0.84, 790, 11, 0.056, 1182),
        Walk(15, 975, 88.7, 90.9, 91.5, 0.76, 590, 7, 0.066, 891),
        Walk(14, 1095, 86.4, 88.1, 88.8, 0.80, 570, 7, 0.063, 834),
        Walk(13, 1140, 91.3, 93.9, 95.2, 0.80, 590, 7, 0.049, 922),
        Walk(11, 1170, 87.6, 90.2, 91.2, 0.87, 675, 9, 0.046, 1015),
        Walk(6, 1125, 91.9, 93.0, 94.1, 0.90, 755, 9, 0.055, 1169),
        Walk(5, 1305, 90.4, 93.1, 94.1, 0.87, 785, 10, 0.044, 1208),
        Walk(4, 1050, 90.6, 92.5, 93.7, 0.90, 605, 8, 0.046, 933),
        Walk(2, 1020, 90.6, 94.6, 95.2, 0.81, 575, 8, 0.044, 894),
        Walk(1, 1245, 93.0, 96.3, 97.1, 0.84, 635, 8, 0.038, 1018),
        Walk(0, 1210, 92.9, 94.1, 95.3, 0.86, 555, 7, 0.040, 870),
    )

    val startDate: LocalDate get() = walks.first().date
    val naturalPace: Double get() = walks.last().naturalSpm
    val naturalPaceChange: Double get() = naturalPace - walks.first().naturalSpm
    val weeks: Int get() = ceil((walks.first().daysAgo + 1) / 7.0).toInt()
    val avgPace: Double get() = walks.map { it.withBeatSpm }.average()
    val totalSteps: Int get() = walks.sumOf { it.steps }
    val bestWithBeat: Double get() = walks.maxOf { it.peakSpm }
    val avgInStep: Double get() = walks.map { it.inStep }.average()
    val totalSec: Int get() = walks.sumOf { it.durationSec }
    val longestSec: Int get() = walks.maxOf { it.durationSec }

    private val shortDate = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val dayDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun shortDate(d: LocalDate): String = d.format(shortDate)
    fun clock(t: LocalTime): String = t.format(clock)

    fun dayLabel(w: Walk): String = when (w.daysAgo) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> w.date.format(dayDate)
    }

    fun hoursMinutes(sec: Int): String {
        val m = sec / 60
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun shareText(): String =
        "My StrideSync progress: natural pace %.1f steps/min, up %.1f since %s. %d walks, %d%% in step on average."
            .format(naturalPace, naturalPaceChange, shortDate(startDate), walks.size, Math.round(avgInStep * 100))
}
