package app.wallcapture.core.capture

import app.wallcapture.core.CaptureConfig
import app.wallcapture.core.geometry.Intrinsics
import app.wallcapture.core.geometry.Mat4
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.geometry.Polygon
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.Raster
import app.wallcapture.core.image.Sharpness
import kotlin.math.abs

enum class Hint { None, SlowDown, MoveCloser, MoveBack, FaceWall, LevelPhone, TrackingLost }

data class FrameState(
    val timeS: Double,
    val pose: Mat4,
    val angularVelRadS: Double,
    val tracking: Boolean,
)

data class TriggerDecision(val capture: Boolean, val hint: Hint)

/**
 * Decides when to capture. Triggering is by footprint overlap, never by time, so uneven walking
 * speed does not matter. Everything here is pure maths so it runs in unit tests and the replay CLI.
 */
class CaptureTrigger(
    private val cfg: CaptureConfig,
    private val frame: WallFrame,
    private val intrinsics: Intrinsics,
    private val coverage: CoverageGrid? = null,
) {
    private var lastKeptFootprint: List<DoubleArray>? = null
    private var prevPos: app.wallcapture.core.geometry.Vec3? = null
    private var prevTime = 0.0

    fun onFrame(s: FrameState): TriggerDecision {
        if (!s.tracking) {
            prevPos = null
            return TriggerDecision(false, Hint.TrackingLost)
        }
        val h = PlaneHomography(frame, s.pose, intrinsics)
        val pos = s.pose.translation()
        val speed = prevPos?.let { p ->
            val dt = s.timeS - prevTime
            if (dt > 1e-6) (pos - p).norm() / dt else 0.0
        } ?: 0.0
        prevPos = pos
        prevTime = s.timeS

        if (abs(s.angularVelRadS) > cfg.maxAngularVelRadS || speed > cfg.maxLinearSpeedMS) {
            return TriggerDecision(false, Hint.SlowDown)
        }
        if (h.distanceM < cfg.minDistanceM) return TriggerDecision(false, Hint.MoveBack)
        if (h.distanceM > cfg.maxDistanceM) return TriggerDecision(false, Hint.MoveCloser)
        if (h.angleDeg > cfg.rejectTiltDeg) return TriggerDecision(false, Hint.FaceWall)

        val hint = when {
            h.angleDeg > cfg.hintTiltDeg -> Hint.FaceWall
            abs(h.rollDeg) > cfg.levelHintRollDeg -> Hint.LevelPhone
            else -> Hint.None
        }

        val footprint = h.footprint()
        val last = lastKeptFootprint
        val capture = if (last == null) {
            true
        } else {
            val overlap = Polygon.overlapFraction(footprint, last)
            overlap < cfg.overlapTrigger ||
                (coverage != null && coverage.uncoveredFraction(footprint) >= cfg.newAreaFraction)
        }
        return TriggerDecision(capture, hint)
    }

    /** Call after a still has been kept so the next overlap is measured from it. */
    fun markKept(s: FrameState) {
        lastKeptFootprint = PlaneHomography(frame, s.pose, intrinsics).footprint()
    }
}

/** Best-of-burst selection on a sharpness score. */
object BurstSelector {
    data class Pick(val index: Int, val sharpness: Double, val accepted: Boolean)

    fun pick(frames: List<Raster>, cfg: CaptureConfig): Pick {
        require(frames.isNotEmpty())
        var best = -1
        var bestScore = -1.0
        for ((i, f) in frames.withIndex()) {
            val sc = Sharpness.score(f)
            if (sc > bestScore) { bestScore = sc; best = i }
        }
        return Pick(best, bestScore, bestScore >= cfg.minSharpness)
    }
}
