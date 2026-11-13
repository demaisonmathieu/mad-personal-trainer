package com.mathieu.madtrainer.coach

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonRepairTest {
    @Test
    fun cutsAfterLastCompleteElement() {
        val raw = """{"advice":"ok","workouts":[{"name":"A","steps":[1,2]},{"name":"B","steps":[3"""
        val fixed = JsonRepair.repairTruncated(raw)
        assertEquals("""{"advice":"ok","workouts":[{"name":"A","steps":[1,2]}]}""", fixed?.replace(" ", ""))
    }
}
