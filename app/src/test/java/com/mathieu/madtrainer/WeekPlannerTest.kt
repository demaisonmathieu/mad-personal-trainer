package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.Sample
import com.mathieu.madtrainer.data.SessionRecord
import com.mathieu.madtrainer.data.WeekPlanner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class WeekPlannerTest {
    private fun date(y: Int, m: Int, d: Int, h: Int = 10) =
        Calendar.getInstance().apply { set(y, m - 1, d, h, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun weeksAndDays() {
        val created = date(2026, 11, 13) // vendredi
        assertEquals(date(2026, 11, 9, 0), WeekPlanner.mondayOf(created))
        assertEquals(1, WeekPlanner.currentWeek(created, date(2026, 11, 15), 4)) // dimanche même semaine
        assertEquals(2, WeekPlanner.currentWeek(created, date(2026, 11, 16), 4)) // lundi suivant
        assertEquals(4, WeekPlanner.currentWeek(created, date(2027, 1, 30), 4)) // borné
        assertEquals(0, WeekPlanner.dayIndex("Lundi"))
        assertEquals(2, WeekPlanner.dayIndex("mercredi matin"))
        assertEquals(-1, WeekPlanner.dayIndex("Jour 1"))
    }

    @Test
    fun zones() {
        val s = SessionRecord(startedAt = 0, durationSec = 180, samples = List(180) { Sample(it, hr = if (it < 120) 115 else 150) })
        val z = WeekPlanner.minutesPerZone(listOf(s), 180)
        assertEquals(2, z[1]) // 115 bpm = Z2
        assertEquals(1, z[3]) // 150 bpm = Z4
    }
}
