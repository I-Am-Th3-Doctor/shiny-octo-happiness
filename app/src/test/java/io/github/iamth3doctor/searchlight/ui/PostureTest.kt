package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PostureTest {
    @Test fun coverScreenIsFoldedEvenSideways() {
        assertEquals(Posture.FOLDED, Posture.of(411, 914))
        assertEquals(Posture.FOLDED, Posture.of(914, 411))
    }

    @Test fun innerScreenIsUnfolded() {
        assertEquals(Posture.UNFOLDED, Posture.of(700, 841))
        assertEquals(Posture.UNFOLDED, Posture.of(841, 700))
    }
}
