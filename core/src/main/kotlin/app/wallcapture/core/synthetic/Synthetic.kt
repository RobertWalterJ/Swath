package app.wallcapture.core.synthetic

import app.wallcapture.core.geometry.Intrinsics
import app.wallcapture.core.geometry.Mat3
import app.wallcapture.core.geometry.Mat4
import app.wallcapture.core.geometry.Vec3
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.Raster
import app.wallcapture.core.model.BoxMm
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A ground-truth note placed on the synthetic wall. */
data class GroundTruthNote(val box: BoxMm, val colour: String)

/**
 * A synthetic wall with known contents. Wall coordinates have the origin at the bottom-left,
 * and the wall frame used for rendering has the same origin, so frame mm == wall mm.
 */
class SyntheticWall(
    val image: Raster,
    val pxPerMm: Double,
    val widthMm: Double,
    val heightMm: Double,
    val notes: List<GroundTruthNote>,
)

object SyntheticWallFactory {
    private val PALETTE = linkedMapOf(
        "yellow" to floatArrayOf(255f, 235f, 59f),
        "pink" to floatArrayOf(255f, 128f, 171f),
        "orange" to floatArrayOf(255f, 152f, 0f),
        "green" to floatArrayOf(139f, 195f, 74f),
        "blue" to floatArrayOf(79f, 195f, 247f),
        "purple" to floatArrayOf(186f, 104f, 200f),
        "white" to floatArrayOf(250f, 250f, 250f),
    )

    fun create(
        widthMm: Double,
        heightMm: Double,
        pxPerMm: Double = 0.5,
        noteCount: Int = 14,
        seed: Long = 1,
        background: FloatArray = floatArrayOf(196f, 164f, 120f),
        noteSizeMm: Double = 80.0,
    ): SyntheticWall {
        val rnd = Random(seed)
        val w = (widthMm * pxPerMm).toInt()
        val h = (heightMm * pxPerMm).toInt()
        val img = Raster.filled(w, h, background[0], background[1], background[2])

        // Low-frequency blotches and fine speckle give the registration something to lock onto.
        val blobs = (widthMm * heightMm / 40000).toInt().coerceAtLeast(20)
        repeat(blobs) {
            val cx = rnd.nextDouble() * widthMm; val cy = rnd.nextDouble() * heightMm
            val rad = 25 + rnd.nextDouble() * 70
            val amp = (rnd.nextDouble() - 0.5) * 50
            stampBlob(img, pxPerMm, heightMm, cx, cy, rad, amp.toFloat())
        }
        for (i in 0 until w * h) {
            val n = (rnd.nextDouble() - 0.5).toFloat() * 10f
            img.data[i * 3] += n; img.data[i * 3 + 1] += n; img.data[i * 3 + 2] += n
        }
        // Dark scribbles across the background.
        repeat((widthMm * heightMm / 25000).toInt().coerceAtLeast(10)) {
            var x = rnd.nextDouble() * widthMm; var y = rnd.nextDouble() * heightMm
            var a = rnd.nextDouble() * 6.28
            repeat(14) {
                a += (rnd.nextDouble() - 0.5) * 1.2
                x += cos(a) * 8; y += sin(a) * 8
                disc(img, pxPerMm, heightMm, x, y, 2.2, floatArrayOf(55f, 45f, 40f))
            }
        }

        // Notes on a jittered grid so they never overlap.
        val spacing = noteSizeMm * 1.9
        val cols = max(1, ((widthMm - spacing) / spacing).toInt()); val rows = max(1, ((heightMm - spacing) / spacing).toInt())
        val slots = ArrayList<IntArray>()
        for (r in 0 until rows) for (c in 0 until cols) slots.add(intArrayOf(r, c))
        slots.shuffle(rnd)
        val names = PALETTE.keys.toList()
        val notes = ArrayList<GroundTruthNote>()
        for ((k, slot) in slots.take(noteCount).withIndex()) {
            val cx = spacing + slot[1] * spacing + (rnd.nextDouble() - 0.5) * noteSizeMm * 0.3
            val cy = spacing + slot[0] * spacing + (rnd.nextDouble() - 0.5) * noteSizeMm * 0.3
            val ang = Math.toRadians((rnd.nextDouble() - 0.5) * 10)
            val size = noteSizeMm * (0.9 + rnd.nextDouble() * 0.2)
            val name = names[k % names.size]
            drawNote(img, pxPerMm, heightMm, cx, cy, size, ang, PALETTE.getValue(name), rnd)
            notes.add(GroundTruthNote(BoxMm(cx - size / 2, cy - size / 2, size, size, Math.toDegrees(ang)), name))
        }
        for (i in img.data.indices) img.data[i] = img.data[i].coerceIn(0f, 255f)
        return SyntheticWall(img, pxPerMm, widthMm, heightMm, notes)
    }

    private fun toPx(mm: Double, s: Double) = mm * s - 0.5

    private fun stampBlob(img: Raster, s: Double, hMm: Double, cx: Double, cy: Double, rad: Double, amp: Float) {
        val x0 = max(0, toPx(cx - rad * 2.5, s).toInt()); val x1 = min(img.width - 1, toPx(cx + rad * 2.5, s).toInt() + 1)
        val y0 = max(0, toPx(hMm - cy - rad * 2.5, s).toInt()); val y1 = min(img.height - 1, toPx(hMm - cy + rad * 2.5, s).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val xm = (x + 0.5) / s; val ym = hMm - (y + 0.5) / s
            val d2 = (xm - cx) * (xm - cx) + (ym - cy) * (ym - cy)
            val a = (amp * exp(-d2 / (2 * rad * rad))).toFloat()
            val i = img.idx(x, y)
            img.data[i] += a; img.data[i + 1] += a; img.data[i + 2] += a
        }
    }

    private fun disc(img: Raster, s: Double, hMm: Double, cx: Double, cy: Double, rad: Double, col: FloatArray) {
        val x0 = max(0, toPx(cx - rad, s).toInt()); val x1 = min(img.width - 1, toPx(cx + rad, s).toInt() + 1)
        val y0 = max(0, toPx(hMm - cy - rad, s).toInt()); val y1 = min(img.height - 1, toPx(hMm - cy + rad, s).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val xm = (x + 0.5) / s; val ym = hMm - (y + 0.5) / s
            if ((xm - cx) * (xm - cx) + (ym - cy) * (ym - cy) <= rad * rad) img.set(x, y, col[0], col[1], col[2])
        }
    }

    private fun drawNote(img: Raster, s: Double, hMm: Double, cx: Double, cy: Double, size: Double, ang: Double, col: FloatArray, rnd: Random) {
        val half = size / 2
        val reach = half * 1.5
        val x0 = max(0, toPx(cx - reach, s).toInt()); val x1 = min(img.width - 1, toPx(cx + reach, s).toInt() + 1)
        val y0 = max(0, toPx(hMm - cy - reach, s).toInt()); val y1 = min(img.height - 1, toPx(hMm - cy + reach, s).toInt() + 1)
        val c = cos(ang); val sn = sin(ang)
        for (y in y0..y1) for (x in x0..x1) {
            val xm = (x + 0.5) / s - cx; val ym = hMm - (y + 0.5) / s - cy
            val u = xm * c + ym * sn; val v = -xm * sn + ym * c
            if (abs(u) <= half && abs(v) <= half) img.set(x, y, col[0], col[1], col[2])
        }
        // Handwriting-like strokes in dark ink.
        repeat(4) {
            var u = -half * 0.7; val v0 = half * (0.6 - it * 0.4)
            var step = 0
            while (u < half * 0.7 && step < 20) {
                val vv = v0 + (rnd.nextDouble() - 0.5) * 4
                val xm = u * c - vv * sn + cx; val ym = u * sn + vv * c + cy
                disc(img, s, hMm, xm, ym, 1.6, floatArrayOf(25f, 25f, 40f))
                u += 4.5 + rnd.nextDouble() * 3; step++
            }
        }
    }
}

data class Jitter(
    val distanceSigmaM: Double = 0.0,
    val yawSigmaDeg: Double = 0.0,
    val pitchSigmaDeg: Double = 0.0,
    val rollSigmaDeg: Double = 0.0,
    val heightSigmaMm: Double = 0.0,
)

data class RenderOptions(
    val motionBlurPx: Int = 0,
    val noiseSigma: Float = 0f,
    /** Glare blob centre in image fractions, radius in px, added brightness. */
    val glare: Triple<DoubleArray, Double, Float>? = null,
)

object SyntheticCamera {
    /** A wall frame that is not axis-aligned in world space, so the maths is exercised in general. */
    fun testFrame(): WallFrame {
        val yaw = Math.toRadians(30.0)
        val n = Vec3(sin(yaw), 0.0, cos(yaw))
        return WallFrame.fromPlane(Vec3(1.0, 0.4, 2.0), n, Vec3(1.0, 0.4, 2.0) + n)
    }

    /** Camera-to-world pose looking at the wall from [xMm], [yMm] on the wall at [distM], with perturbation angles. */
    fun pose(frame: WallFrame, xMm: Double, yMm: Double, distM: Double, yawDeg: Double = 0.0, pitchDeg: Double = 0.0, rollDeg: Double = 0.0): Mat4 {
        // Columns are camera right, up, back expressed in wall axes (camera looks along -z, i.e. into the wall).
        val base = Mat3.identity()
        val rp = Mat3.rotZ(Math.toRadians(rollDeg)) * Mat3.rotX(Math.toRadians(pitchDeg)) * Mat3.rotY(Math.toRadians(yawDeg))
        val rWall = rp * base
        val toWorld = Mat3.fromColumns(frame.xAxis, frame.yAxis, frame.normal)
        val rWorld = toWorld * rWall
        val posM = frame.wallToWorld(xMm / 1000.0, yMm / 1000.0, distM)
        return Mat4.fromRt(rWorld, posM)
    }

    fun intrinsics(width: Int = 480, height: Int = 360, fxPx: Double = 420.0) =
        Intrinsics(fxPx, fxPx, (width - 1) / 2.0, (height - 1) / 2.0, width, height)

    fun walk(
        frame: WallFrame,
        xs: List<Double>,
        yMm: Double,
        distM: Double,
        jitter: Jitter = Jitter(),
        seed: Long = 7,
    ): List<Mat4> {
        val rnd = Random(seed)
        return xs.map { x ->
            pose(
                frame,
                x,
                yMm + rnd.nextGaussian() * jitter.heightSigmaMm,
                distM + rnd.nextGaussian() * jitter.distanceSigmaM,
                rnd.nextGaussian() * jitter.yawSigmaDeg,
                rnd.nextGaussian() * jitter.pitchSigmaDeg,
                rnd.nextGaussian() * jitter.rollSigmaDeg,
            )
        }
    }

    /** Adds linear drift along the wall X axis to measured poses: [mmPerM] of travel. */
    fun drifted(frame: WallFrame, poses: List<Mat4>, mmPerM: Double): List<Mat4> {
        if (poses.isEmpty()) return poses
        val x0 = frame.worldToWall(poses[0].translation()).x
        return poses.map { p ->
            val x = frame.worldToWall(p.translation()).x
            val dMm = (x - x0) * mmPerM
            Mat4.fromRt(p.rotation(), p.translation() + frame.xAxis * (dMm / 1000.0))
        }
    }

    /** Renders what the camera sees of [wall] from [pose]. */
    fun render(wall: SyntheticWall, frame: WallFrame, pose: Mat4, k: Intrinsics, opts: RenderOptions = RenderOptions(), seed: Long = 1): Raster {
        val out = Raster(k.width, k.height)
        val camWall = frame.worldToWall(pose.translation()) // metres
        val rot = pose.rotation()
        val tmp = FloatArray(3)
        val s = wall.pxPerMm
        for (v in 0 until k.height) for (u in 0 until k.width) {
            // Camera ray in ARCore convention: x right, y up, looking along -z.
            val dirCam = Vec3((u - k.cx) / k.fx, -(v - k.cy) / k.fy, -1.0)
            val dw = frame.dirToWall(rot * dirCam)
            val t = -camWall.z / dw.z
            val xm = (camWall.x + t * dw.x) * 1000.0
            val ym = (camWall.y + t * dw.y) * 1000.0
            val gx = xm * s - 0.5
            val gy = (wall.heightMm - ym) * s - 0.5
            if (t > 0 && wall.image.sample(gx, gy, tmp)) out.set(u, v, tmp[0], tmp[1], tmp[2]) else out.set(u, v, 20f, 20f, 20f)
        }
        var r = out
        if (opts.motionBlurPx > 0) r = horizontalBlur(r, opts.motionBlurPx)
        if (opts.glare != null) {
            val (centre, radius, strength) = opts.glare
            val gx = centre[0] * k.width; val gy = centre[1] * k.height
            for (v in 0 until k.height) for (u in 0 until k.width) {
                val d2 = (u - gx) * (u - gx) + (v - gy) * (v - gy)
                val a = strength * exp(-d2 / (2 * radius * radius)).toFloat()
                val i = r.idx(u, v)
                for (c in 0 until 3) r.data[i + c] = min(255f, r.data[i + c] + a)
            }
        }
        if (opts.noiseSigma > 0f) {
            val rnd = Random(seed)
            for (i in r.data.indices) r.data[i] = (r.data[i] + rnd.nextGaussian().toFloat() * opts.noiseSigma).coerceIn(0f, 255f)
        }
        return r
    }

    private fun horizontalBlur(r: Raster, radius: Int): Raster {
        val out = Raster(r.width, r.height)
        for (y in 0 until r.height) for (x in 0 until r.width) for (c in 0 until 3) {
            var sum = 0f; var n = 0
            for (dx in -radius..radius) {
                val xx = x + dx
                if (xx in 0 until r.width) { sum += r.data[r.idx(xx, y) + c]; n++ }
            }
            out.data[out.idx(x, y) + c] = sum / n
        }
        return out
    }
}
