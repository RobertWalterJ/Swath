package app.wallcapture.core

import app.wallcapture.core.geometry.Mat4
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.Raster
import app.wallcapture.core.mosaic.ImageSource
import app.wallcapture.core.mosaic.MosaicRenderer
import app.wallcapture.core.mosaic.StillInput
import app.wallcapture.core.mosaic.WallOffset
import app.wallcapture.core.refinement.Refiner
import app.wallcapture.core.synthetic.Jitter
import app.wallcapture.core.synthetic.RenderOptions
import app.wallcapture.core.synthetic.SyntheticCamera
import app.wallcapture.core.synthetic.SyntheticWall
import app.wallcapture.core.synthetic.SyntheticWallFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object Fixture {
    val frame: WallFrame = SyntheticCamera.testFrame()
    val k = SyntheticCamera.intrinsics()

    fun stills(
        wall: SyntheticWall,
        truth: List<Mat4>,
        measured: List<Mat4> = truth,
        opts: (Int) -> RenderOptions = { RenderOptions(noiseSigma = 1f) },
    ): List<StillInput> = truth.indices.map { i ->
        val img = SyntheticCamera.render(wall, frame, truth[i], k, opts(i), 100L + i)
        StillInput("s%03d".format(i), k, measured[i], app.wallcapture.core.image.Sharpness.score(img), ImageSource { img })
    }

    fun xs(startMm: Double, endMm: Double, stepMm: Double) = generateSequence(startMm) { it + stepMm }.takeWhile { it <= endMm + 1e-6 }.toList()
}

class MosaicTest {
    /** Mean absolute grey-level error between the mosaic and the ground truth, over covered pixels away from edges. */
    private fun meanError(m: MosaicRenderer, tile: app.wallcapture.core.mosaic.MosaicTile, wall: SyntheticWall): Double {
        var sum = 0.0; var n = 0
        val margin = (60 * m.pxPerMm).toInt()
        for (j in margin until m.heightPx - margin) for (i in margin until m.widthPx - margin) {
            if (!tile.covered[j * m.widthPx + i]) continue
            val x = m.extent.minX + (i + 0.5) / m.pxPerMm
            val y = m.extent.maxY - (j + 0.5) / m.pxPerMm
            val out = FloatArray(3)
            if (!wall.image.sample(x * wall.pxPerMm - 0.5, (wall.heightMm - y) * wall.pxPerMm - 0.5, out)) continue
            val idx = tile.raster.idx(i, j)
            sum += (abs(tile.raster.data[idx] - out[0]) + abs(tile.raster.data[idx + 1] - out[1]) + abs(tile.raster.data[idx + 2] - out[2])) / 3.0
            n++
        }
        return sum / max(1, n)
    }

    @Test
    fun groundTruthPosesReproduceTheWall() {
        val wall = SyntheticWallFactory.create(2400.0, 900.0, pxPerMm = 1.0, seed = 5)
        val truth = SyntheticCamera.walk(Fixture.frame, Fixture.xs(700.0, 1700.0, 400.0), 450.0, 1.2)
        val stills = Fixture.stills(wall, truth)
        val r = MosaicRenderer(Fixture.frame, stills, emptyMap(), ProcessingConfig(pxPerMm = 1.0))
        val tile = r.renderAll()
        val err = meanError(r, tile, wall)
        assertTrue(err < 6.0, "mean abs error $err")
        // Position check: the mosaic must line up with the ground truth to better than 1 mm (1 px here).
        assertEquals(0, bestShift(r, tile, wall).first)
        assertEquals(0, bestShift(r, tile, wall).second)
    }

    private fun bestShift(m: MosaicRenderer, tile: app.wallcapture.core.mosaic.MosaicTile, wall: SyntheticWall): Pair<Int, Int> {
        var best = Double.MAX_VALUE; var bu = 0; var bv = 0
        val margin = 80
        for (dv in -3..3) for (du in -3..3) {
            var s = 0.0; var n = 0
            for (j in margin until m.heightPx - margin step 2) for (i in margin until m.widthPx - margin step 2) {
                val x = m.extent.minX + (i + 0.5 + du) / m.pxPerMm
                val y = m.extent.maxY - (j + 0.5 + dv) / m.pxPerMm
                val out = FloatArray(3)
                if (!wall.image.sample(x * wall.pxPerMm - 0.5, (wall.heightMm - y) * wall.pxPerMm - 0.5, out)) continue
                s += abs(tile.raster.data[tile.raster.idx(i, j)] - out[0]); n++
            }
            if (n > 0 && s / n < best) { best = s / n; bu = du; bv = dv }
        }
        return bu to bv
    }

    @Test
    fun glareIsRemovedWithoutDarkeningTheWhiteboard() {
        // Grey-white so the wall itself never saturates; the glare spot (+90) then pushes it to 255.
        val white = floatArrayOf(190f, 190f, 190f)
        val wall = SyntheticWallFactory.create(2000.0, 700.0, pxPerMm = 1.0, noteCount = 0, seed = 2, background = white)
        val truth = SyntheticCamera.walk(Fixture.frame, Fixture.xs(600.0, 1400.0, 200.0), 350.0, 1.2)
        // A bright spot at a fixed image position moves across the wall as the camera moves.
        val stills = Fixture.stills(wall, truth) { i -> RenderOptions(glare = Triple(doubleArrayOf(0.5, 0.5), 30.0, 90f)) }
        val r = MosaicRenderer(Fixture.frame, stills, emptyMap(), ProcessingConfig(pxPerMm = 1.0))
        val tile = r.renderAll()
        val clean = MosaicRenderer(
            Fixture.frame, Fixture.stills(wall, truth) { RenderOptions() }, emptyMap(), ProcessingConfig(pxPerMm = 1.0),
        ).renderAll()
        var maxLum = 0f; var cleanMax = 0f
        for (j in 60 until r.heightPx - 60) for (i in 150 until r.widthPx - 150) {
            if (tile.covered[j * r.widthPx + i]) maxLum = max(maxLum, tile.raster.luminance(i, j))
            cleanMax = max(cleanMax, clean.raster.luminance(i, j))
        }
        var meanTile = 0.0; var meanClean = 0.0; var n = 0
        for (j in 60 until r.heightPx - 60) for (i in 150 until r.widthPx - 150) {
            meanTile += tile.raster.luminance(i, j); meanClean += clean.raster.luminance(i, j); n++
        }
        assertTrue(maxLum < cleanMax + 12f, "blown out: $maxLum vs clean $cleanMax")
        assertTrue(abs(meanTile / n - meanClean / n) < 4.0, "mean ${meanTile / n} vs clean ${meanClean / n}")
        assertTrue(meanTile / n > meanClean / n - 4.0, "whiteboard darkened: ${meanTile / n}")
    }

    @Test
    fun renderingIsDeterministicAndTilesAgreeWithTheWhole() {
        val wall = SyntheticWallFactory.create(1800.0, 700.0, pxPerMm = 1.0, seed = 8)
        val truth = SyntheticCamera.walk(Fixture.frame, Fixture.xs(600.0, 1200.0, 300.0), 350.0, 1.2)
        val r = MosaicRenderer(Fixture.frame, Fixture.stills(wall, truth), emptyMap(), ProcessingConfig(pxPerMm = 1.0))
        val whole = r.renderAll().raster
        val part = r.render(200, 100, 300, 200).raster
        for (j in 0 until 200 step 17) for (i in 0 until 300 step 13) {
            assertEquals(whole.luminance(200 + i, 100 + j), part.luminance(i, j), 1e-3f)
        }
    }
}

class RefinementTest {
    @Test
    fun driftOfFiveCentimetresOverTenMetresIsCorrected() {
        val wall = SyntheticWallFactory.create(10000.0, 900.0, pxPerMm = 0.5, seed = 11, noteCount = 30)
        val xs = Fixture.xs(700.0, 9300.0, 480.0)
        val truth = SyntheticCamera.walk(Fixture.frame, xs, 450.0, 1.2, Jitter(0.01, 0.5, 0.5, 1.0, 3.0))
        val measured = SyntheticCamera.drifted(Fixture.frame, truth, 5.0) // 5 mm per metre = 50 mm over 10 m
        val stills = Fixture.stills(wall, truth, measured)
        val cfg = ProcessingConfig()
        val res = Refiner.refine(Fixture.frame, stills, cfg)
        assertTrue(res.pairsUsed >= stills.size - 1, "pairs used ${res.pairsUsed}, dropped ${res.pairsDropped}")
        val x0 = Fixture.frame.worldToWall(measured[0].translation()).x * 1000
        var worst = 0.0
        for ((i, s) in stills.withIndex()) {
            val drift = Fixture.frame.worldToWall(measured[i].translation()).x * 1000 - Fixture.frame.worldToWall(truth[i].translation()).x * 1000
            val drift0 = x0 - Fixture.frame.worldToWall(truth[0].translation()).x * 1000
            val expected = drift - drift0
            val got = res.offsets.getValue(s.id)
            worst = max(worst, abs(got.dx - expected))
        }
        assertTrue(worst < 5.0, "worst residual $worst mm")
    }

    @Test
    fun zeroDriftStaysNearZero() {
        val wall = SyntheticWallFactory.create(3000.0, 900.0, pxPerMm = 0.5, seed = 12)
        val truth = SyntheticCamera.walk(Fixture.frame, Fixture.xs(700.0, 2300.0, 480.0), 450.0, 1.2)
        val res = Refiner.refine(Fixture.frame, Fixture.stills(wall, truth), ProcessingConfig())
        for (o in res.offsets.values) assertTrue(abs(o.dx) < 4.0 && abs(o.dy) < 4.0, "$o")
        assertEquals(WallOffset.ZERO, res.offsets.getValue("s000"))
    }

    @Suppress("unused")
    private fun unused(r: Raster) = r
}
