package dev.rambo.airposture

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** Motion sample expected in g for acceleration and deg/s for gyro. */
data class MotionSample(
    val timeMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double = 0.0,
    val gy: Double = 0.0,
    val gz: Double = 0.0,
)

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val n = norm().coerceAtLeast(1e-9)
        return this * (1.0 / n)
    }
}

data class PostureState(
    val flexionDeg: Double,
    val totalTiltDeg: Double,
    val bad: Boolean,
    val shouldVibrate: Boolean,
)

/**
 * Back-mounted posture detector.
 *
 * Calibration:
 * 1. captureNeutral(): stand/sit in the posture you want to preserve.
 * 2. captureForwardReference(): lean forward deliberately ~20-30 degrees.
 *
 * The second pose learns "forward" independent of how the tracker is rotated
 * when taped/clipped to the back.
 */
class PostureEngine(
    private val enterDeg: Double = 12.0,
    private val exitDeg: Double = 8.0,
    private val dwellMs: Long = 4_000,
    private val recoveryMs: Long = 1_000,
    private val repeatVibrationMs: Long = 30_000,
    private val gravityTauMs: Double = 450.0,
) {
    private var neutral: Vec3? = null
    private var forwardAxis: Vec3? = null
    private var filteredGravity: Vec3? = null
    private var lastTimeMs: Long? = null

    private var aboveSince: Long? = null
    private var belowSince: Long? = null
    private var bad = false
    private var lastVibrationMs: Long? = null

    fun captureNeutral(samples: List<MotionSample>) {
        require(samples.isNotEmpty())
        neutral = averageGravity(samples).normalized()
        filteredGravity = neutral
        resetState()
    }

    fun captureForwardReference(samples: List<MotionSample>) {
        val n = requireNotNull(neutral) { "Capture neutral first" }
        val f = averageGravity(samples).normalized()
        val tangent = (f - n * f.dot(n))
        require(tangent.norm() > 0.08) { "Forward reference is too close to neutral" }
        forwardAxis = tangent.normalized()
        resetState()
    }

    fun isCalibrated() = neutral != null && forwardAxis != null

    fun update(sample: MotionSample): PostureState {
        val n = requireNotNull(neutral) { "Not calibrated: neutral missing" }
        val fwd = requireNotNull(forwardAxis) { "Not calibrated: forward reference missing" }
        val raw = Vec3(sample.ax, sample.ay, sample.az)

        // Reject strong translational acceleration; hold previous gravity estimate.
        val mag = raw.norm()
        val usable = mag in 0.78..1.22
        val prev = filteredGravity ?: raw.normalized()
        val dt = ((sample.timeMs - (lastTimeMs ?: sample.timeMs)).coerceIn(1, 500)).toDouble()
        lastTimeMs = sample.timeMs
        val alpha = gravityTauMs / (gravityTauMs + dt)
        val g = if (usable) (prev * alpha + raw.normalized() * (1.0 - alpha)).normalized() else prev
        filteredGravity = g

        val cosTilt = g.dot(n).coerceIn(-1.0, 1.0)
        val totalTilt = Math.toDegrees(acos(cosTilt))

        // Signed forward flexion: positive only in the learned forward direction.
        val forwardComponent = g.dot(fwd)
        val neutralComponent = g.dot(n)
        val flexion = Math.toDegrees(atan2(forwardComponent, neutralComponent))

        var vibrate = false
        if (!bad) {
            if (flexion >= enterDeg) {
                if (aboveSince == null) aboveSince = sample.timeMs
                if (sample.timeMs - aboveSince!! >= dwellMs) {
                    bad = true
                    lastVibrationMs = sample.timeMs
                    vibrate = true
                    belowSince = null
                }
            } else {
                aboveSince = null
            }
        } else {
            if (flexion <= exitDeg) {
                if (belowSince == null) belowSince = sample.timeMs
                if (sample.timeMs - belowSince!! >= recoveryMs) {
                    bad = false
                    aboveSince = null
                    belowSince = null
                }
            } else {
                belowSince = null
                val last = lastVibrationMs ?: sample.timeMs
                if (sample.timeMs - last >= repeatVibrationMs) {
                    lastVibrationMs = sample.timeMs
                    vibrate = true
                }
            }
        }

        return PostureState(flexion, totalTilt, bad, vibrate)
    }

    fun resetState() {
        aboveSince = null
        belowSince = null
        bad = false
        lastVibrationMs = null
        lastTimeMs = null
    }

    private fun averageGravity(samples: List<MotionSample>): Vec3 {
        var v = Vec3(0.0, 0.0, 0.0)
        samples.forEach { v = v + Vec3(it.ax, it.ay, it.az).normalized() }
        return v * (1.0 / samples.size)
    }
}
