package app.wallcapture.core

import app.wallcapture.core.capture.BurstSelector
import app.wallcapture.core.capture.CaptureTrigger
import app.wallcapture.core.capture.CellState
import app.wallcapture.core.capture.CoverageGrid
import app.wallcapture.core.capture.FrameState
import app.wallcapture.core.capture.Hint
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.geometry.Vec3
import app.wallcapture.core.image.Sharpness
import app.wallcapture.core.synthetic.SyntheticCamera
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {
    private val frame = SyntheticCamera.testFrame()
    private val k = SyntheticCamera.intrinsics(1600, 1200, 1400.0)

    /** Independent pinhole projection straight from world space, with no homography involved. */
    private fun direct(pose: app.wallcapture.core.geometry.Mat4, xMm: Double, yMm: Double): DoubleArray {
        val world = frame.wallToWorld(xMm / 1000.0, yMm / 1000.0, 0.0)
        val pc = pose.inverseRigid().transformPoint(world)
        return doubleArrayOf(k.fx * pc.x / -pc.z + k.cx, k.cy - k.fy * pc.y / -pc.z)
    }

    @Test
    fun homographyMatchesDirectProjectionWithin_half_a_pixel() {
        val pose = SyntheticCamera.pose(frame, 2500.0, 900.0, 1.3, yawDeg = 12.0, pitchDeg = -7.0, rollDeg = 18.0)
        val h = PlaneHomography(frame, pose, k)
        val out = DoubleArray(2)
        for (x in listOf(1900.0, 2300.0, 2500.0, 2800.0, 3100.0)) for (y in listOf(500.0, 900.0, 1200.0)) {
            assertTrue(h.wallToPixel(x, y, out))
            val d = direct(pose, x, y)
            assertTrue(abs(out[0] - d[0]) < 0.5 && abs(out[1] - d[1]) < 0.5, "($x,$y): ${out.toList()} vs ${d.toList()}")
        }
    }

    @Test
    fun pixelToWallInvertsWallToPixel() {
        val pose = SyntheticCamera.pose(frame, 1000.0, 700.0, 1.1, 5.0, 3.0, -10.0)
        val h = PlaneHomography(frame, pose, k)
        val px = DoubleArray(2)
        h.wallToPixel(1234.0, 567.0, px)
        val back = h.pixelToWall(px[0], px[1])
        assertEquals(1234.0, back[0], 1e-6)
        assertEquals(567.0, back[1], 1e-6)
    }

    @Test
    fun distanceAndAngleAreReported() {
        val h = PlaneHomography(frame, SyntheticCamera.pose(frame, 0.0, 0.0, 1.5, yawDeg = 20.0), k)
        assertEquals(1.5, h.distanceM, 1e-9)
        assertEquals(20.0, h.angleDeg, 0.01)
    }

    @Test
    fun frameFromPlaneIsOrthonormalAndFacesTheCamera() {
        val f = app.wallcapture.core.geometry.WallFrame.fromPlane(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 2.0))
        assertEquals(1.0, f.normal.z, 1e-9) // flipped towards the camera
        assertEquals(0.0, f.xAxis dot f.yAxis, 1e-9)
        assertEquals(1.0, f.yAxis.y, 1e-9)
    }
}

class SharpnessTest {
    @Test
    fun blurredFramesScoreBelowSharpOnes() {
        val wall = app.wallcapture.core.synthetic.SyntheticWallFactory.create(1500.0, 800.0, seed = 3)
        val frame = SyntheticCamera.testFrame()
        val k = SyntheticCamera.intrinsics()
        val pose = SyntheticCamera.pose(frame, 750.0, 400.0, 1.2)
        val sharp = SyntheticCamera.render(wall, frame, pose, k)
        val blurred = SyntheticCamera.render(wall, frame, pose, k, app.wallcapture.core.synthetic.RenderOptions(motionBlurPx = 6))
        assertTrue(Sharpness.score(sharp) > 3 * Sharpness.score(blurred))
        val pick = BurstSelector.pick(listOf(blurred, sharp, blurred), CaptureConfig(minSharpness = 0.0))
        assertEquals(1, pick.index)
    }
}

class CaptureTriggerTest {
    private val frame = SyntheticCamera.testFrame()
    private val k = SyntheticCamera.intrinsics(1600, 1200, 1400.0)
    private val cfg = CaptureConfig()

    private fun walk(speedMS: Double, lengthM: Double, hz: Double = 30.0, dist: Double = 1.2, stopAtM: Double? = null, stopS: Double = 0.0): Int {
        val trigger = CaptureTrigger(cfg, frame, k)
        var kept = 0
        var t = 0.0
        var x = 0.0
        var stoppedFor = 0.0
        while (x <= lengthM) {
            val moving = !(stopAtM != null && x >= stopAtM && stoppedFor < stopS)
            if (!moving) stoppedFor += 1 / hz else x += speedMS / hz
            val s = FrameState(t, SyntheticCamera.pose(frame, x * 1000, 800.0, dist), 0.05, true)
            if (trigger.onFrame(s).capture) { trigger.markKept(s); kept++ }
            t += 1 / hz
        }
        return kept
    }

    @Test
    fun calmWalkKeepsAboutOneStillPerFortyPercentOfFootprint() {
        val footprintM = 1.2 * 1600 / 1400
        val expected = ceil(6.0 / (footprintM * 0.4)).toInt() + 1
        val kept = walk(0.3, 6.0)
        assertTrue(abs(kept - expected) <= 2, "kept $kept expected about $expected")
    }

    @Test
    fun slowAndFastWalksCoverTheSameWallWithTheSameStillCount() {
        assertTrue(abs(walk(0.15, 6.0) - walk(0.45, 6.0)) <= 2)
    }

    @Test
    fun tooFastNeverCaptures() {
        val trigger = CaptureTrigger(cfg, frame, k)
        var kept = 0
        var lastHint = Hint.None
        for (i in 0 until 90) {
            val s = FrameState(i / 30.0, SyntheticCamera.pose(frame, i * 30.0, 800.0, 1.2), 0.1, true)
            val d = trigger.onFrame(s)
            lastHint = d.hint
            if (d.capture) { trigger.markKept(s); kept++ }
        }
        assertEquals(1, kept) // only the first frame, which has no speed estimate yet
        assertEquals(Hint.SlowDown, lastHint)
    }

    @Test
    fun stopStartWalkDoesNotDuplicateStills() {
        val straight = walk(0.3, 6.0)
        val stopStart = walk(0.3, 6.0, stopAtM = 3.0, stopS = 4.0)
        assertTrue(abs(straight - stopStart) <= 1)
    }

    @Test
    fun hintsForDistanceAndTilt() {
        // A fresh trigger per case: jumping the camera between cases would itself read as "too fast".
        fun first(distM: Double, yaw: Double = 0.0, tracking: Boolean = true) =
            CaptureTrigger(cfg, frame, k).onFrame(FrameState(0.0, SyntheticCamera.pose(frame, 0.0, 800.0, distM, yawDeg = yaw), 0.0, tracking))
        assertEquals(Hint.MoveBack, first(0.4).hint)
        assertEquals(Hint.MoveCloser, first(2.6).hint)
        val tilted = first(1.2, yaw = 55.0)
        assertTrue(!tilted.capture && tilted.hint == Hint.FaceWall)
        val mildlyTilted = first(1.2, yaw = 35.0)
        assertTrue(mildlyTilted.capture && mildlyTilted.hint == Hint.FaceWall) // hint, but still kept
        assertEquals(Hint.TrackingLost, first(1.2, tracking = false).hint)
    }
}

class CoverageGridTest {
    private val frame = SyntheticCamera.testFrame()
    private val k = SyntheticCamera.intrinsics(1600, 1200, 1400.0)
    // The synthetic camera is low resolution, so lower the "good" resolution bar to what it can deliver at the edges.
    private val cfg = CaptureConfig(goodMinPxPerMm = 0.5)

    private fun h(x: Double) = PlaneHomography(frame, SyntheticCamera.pose(frame, x, 800.0, 1.2), k)

    @Test
    fun aSkippedStillLeavesAHoleInsideTheCapturedBounds() {
        val grid = CoverageGrid(cfg)
        for (x in listOf(1000.0, 1500.0, 3500.0, 4000.0)) grid.addStill(h(x), sharp = true)
        assertEquals(CellState.Good, grid.stateAt(1000.0, 800.0))
        assertEquals(CellState.NotCaptured, grid.stateAt(2600.0, 800.0))
        assertTrue(grid.gapCellsInsideBounds() > 0)
    }

    @Test
    fun noGapsWhenOverlapIsContinuous() {
        val grid = CoverageGrid(cfg)
        for (x in listOf(1000.0, 1500.0, 2000.0, 2500.0)) grid.addStill(h(x), sharp = true)
        val b = grid.capturedBounds()!!
        // Every cell in the central band must be covered; ragged top/bottom edges are not gaps of interest.
        for (ix in b[0] + 1 until b[2]) assertEquals(CellState.Good, grid.stateOf(ix, (800.0 / cfg.cellMm).toInt()))
    }

    @Test
    fun blurryStillsAreWeakAndGlareIsFlagged() {
        val grid = CoverageGrid(cfg)
        grid.addStill(h(1000.0), sharp = false)
        assertEquals(CellState.Weak, grid.stateAt(1000.0, 800.0))
        val g2 = CoverageGrid(cfg)
        g2.addStill(h(1000.0), sharp = true) { _, _ -> true }
        assertEquals(CellState.Glare, g2.stateAt(1000.0, 800.0))
        assertNull(CoverageGrid(cfg).capturedBounds())
    }

    @Test
    fun uncoveredFractionDropsAsTheWallFillsIn() {
        val grid = CoverageGrid(cfg)
        val fp = h(1000.0).footprint()
        assertEquals(1.0, grid.uncoveredFraction(fp), 1e-9)
        grid.addStill(h(1000.0), true)
        assertEquals(0.0, grid.uncoveredFraction(fp), 0.02)
        assertTrue(sqrt(grid.uncoveredFraction(h(1400.0).footprint())) > 0.1)
    }
}
