package io.github.iamth3doctor.searchlight.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UtilTest {
    @Test fun dayProgress() {
        assertEquals(DayProgress(1, 365), DayProgress.of(LocalDate.of(2026, 1, 1)))
        assertEquals(100, DayProgress.of(LocalDate.of(2024, 12, 31)).percent)
        assertEquals(DayProgress(293, 365), DayProgress.of(LocalDate.of(2026, 10, 20)))
        assertEquals(80, DayProgress.of(LocalDate.of(2026, 10, 20)).percent)
    }

    @Test fun snippet() {
        assertEquals("short text", Snippet.around("short text", 0, 5))
        val long = "a".repeat(100) + " dinner at eight " + "b".repeat(100)
        val s = Snippet.around(long, 101, 6, max = 40)
        assertTrue(s, s.contains("dinner"))
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertTrue(s.length <= 42)
    }
}
