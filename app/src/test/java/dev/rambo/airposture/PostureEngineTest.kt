package dev.rambo.airposture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PostureEngineTest {
    private fun pose(time: Long, deg: Double): MotionSample {
        val r = Math.toRadians(deg)
        return MotionSample(time, sin(r), 0.0, cos(r))
    }

    @Test fun vibratesOnlyAfterDwellAndClearsAfterRecovery() {
        val e = PostureEngine(enterDeg = 12.0, exitDeg = 8.0, dwellMs = 4000, recoveryMs = 1000)
        e.captureNeutral(List(20) { pose(it.toLong() * 20, 0.0) })
        e.captureForwardReference(List(20) { pose(500 + it.toLong() * 20, 25.0) })

        var firstVibration = false
        var state = e.update(pose(1000, 15.0))
        for (t in 1020L..6500L step 20) {
            state = e.update(pose(t, 15.0))
            if (state.shouldVibrate) firstVibration = true
        }
        assertTrue(firstVibration)
        assertTrue(state.bad)

        for (t in 6520L..8000L step 20) state = e.update(pose(t, 4.0))
        assertFalse(state.bad)
    }
}
