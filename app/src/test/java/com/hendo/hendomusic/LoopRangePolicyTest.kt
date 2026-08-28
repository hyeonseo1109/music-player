package com.hendo.hendomusic

import com.hendo.hendomusic.domain.LoopRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopRangePolicyTest {
    @Test fun `previous loop snapshots fixed end`() {
        assertEquals(7000L to 10000L, LoopRangePolicy.previous(10_000, 3_000)?.let { it.startMs to it.endMs })
        assertEquals(5000L to 10000L, LoopRangePolicy.previous(10_000, 5_000)?.let { it.startMs to it.endMs })
        assertEquals(5000L to 15000L, LoopRangePolicy.previous(15_000, 10_000)?.let { it.startMs to it.endMs })
        assertEquals(0L to 2000L, LoopRangePolicy.previous(2_000, 10_000)?.let { it.startMs to it.endMs })
    }
    @Test fun `direct range needs at least 300 milliseconds`() {
        assertEquals(10000L to 15000L, LoopRangePolicy.create(10_000, 15_000)?.let { it.startMs to it.endMs })
        assertNull(LoopRangePolicy.create(15_000, 10_000))
        assertNull(LoopRangePolicy.create(10_000, 10_200))
    }
}
