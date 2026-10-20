package io.github.iamth3doctor.searchlight.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {
    private fun calc(s: String) = Calculator.evaluate(s)

    @Test fun basics() {
        assertEquals("756", calc("42 x 18"))
        assertEquals("756", calc("42x18"))
        assertEquals("756", calc("42 × 18"))
        assertEquals("756", calc("42*18"))
        assertEquals("7", calc("1 + 2 * 3"))
        assertEquals("9", calc("(1 + 2) * 3"))
        assertEquals("2.5", calc("5 / 2"))
        assertEquals("2.5", calc("5 ÷ 2"))
        assertEquals("0.3", calc("0.1 + 0.2"))
        assertEquals("1,024", calc("2^10"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("-3", calc("-5 + 2"))
        assertEquals("1,000,000", calc("1,000 x 1,000"))
    }

    @Test fun percentAndPi() {
        assertEquals("20", calc("200 x 10%"))
        assertEquals("6.28318530718", calc("2 x pi"))
        assertEquals("6.28318530718", calc("2π".replace("π", " × π")))
    }

    @Test fun unfinishedParenWhileTyping() {
        assertEquals("9", calc("(1 + 2) * (3"))
    }

    @Test fun notMath() {
        assertNull(calc("42"))
        assertNull(calc("-5"))
        assertNull(calc("maps"))
        assertNull(calc("x"))
        assertNull(calc("42 x"))
        assertNull(calc("1 / 0"))
        assertNull(calc("spotify 2"))
        assertNull(calc("2 + + "))
    }

    @Test fun formatting() {
        assertEquals("0", Calculator.format(-0.0))
        assertEquals("1.5e20", Calculator.format(1.5e20))
        assertEquals("33,333.3333333", calc("100000 / 3"))
    }
}
