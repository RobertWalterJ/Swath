package app.wallcapture.core.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/** Pinhole intrinsics. Pixel centres sit at integer coordinates. */
data class Intrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
)

/**
 * The wall frame: origin at the tap point, X horizontal along the wall, Y up, Z out of the wall
 * towards the user. World units are metres, so [wallToWorld] and [worldToWall] work in metres;
 * everything else (homography, mosaic, coverage, notes) uses millimetres.
 */
class WallFrame(val origin: Vec3, val xAxis: Vec3, val yAxis: Vec3, val normal: Vec3) {
    private val toWorld = Mat3.fromColumns(xAxis, yAxis, normal)
    private val toWall = toWorld.transpose()

    fun wallToWorld(x: Double, y: Double, z: Double = 0.0): Vec3 = origin + toWorld * Vec3(x, y, z)
    fun worldToWall(p: Vec3): Vec3 = toWall * (p - origin)
    fun dirToWall(d: Vec3): Vec3 = toWall * d
    fun dirToWorld(d: Vec3): Vec3 = toWorld * d

    companion object {
        /**
         * Builds a frame from a plane hit. [planeNormal] is flipped if needed so it faces the camera.
         * [worldUp] is gravity-up in world coordinates.
         */
        fun fromPlane(hit: Vec3, planeNormal: Vec3, cameraPosition: Vec3, worldUp: Vec3 = Vec3(0.0, 1.0, 0.0)): WallFrame {
            var n = planeNormal.normalized()
            if ((cameraPosition - hit) dot n < 0) n = -n
            val y = (worldUp - n * (worldUp dot n)).normalized()
            val x = (y cross n).normalized()
            return WallFrame(hit, x, y, n)
        }
    }
}

/**
 * Maps wall coordinates (mm) to image pixels for one still.
 * Pose is camera-to-world in ARCore's camera convention (+X right, +Y up, camera looks along -Z).
 *
 *   H = K * [R c1, R c2, R o + t]   with the ARCore-to-CV axis flip applied
 *
 * which is the plane-induced homography H = K (R - t n^T / d) K^-1 written for a plane given by a frame.
 */
class PlaneHomography(val frame: WallFrame, val pose: Mat4, val k: Intrinsics) {
    val h: Mat3
    val hInv: Mat3
    val cameraInWall: Vec3 = frame.worldToWall(pose.translation())
    val distanceM: Double = abs(cameraInWall.z)
    val angleDeg: Double
    val rollDeg: Double

    init {
        val cw = pose.inverseRigid()
        val r = cw.rotation()
        val t = cw.translation()
        fun flip(v: Vec3) = Vec3(v.x, -v.y, -v.z)
        val c1 = flip(r * frame.xAxis) * 0.001
        val c2 = flip(r * frame.yAxis) * 0.001
        val c3 = flip(r * frame.origin + t)
        val kk = Mat3(doubleArrayOf(k.fx, 0.0, k.cx, 0.0, k.fy, k.cy, 0.0, 0.0, 1.0))
        h = kk * Mat3.fromColumns(c1, c2, c3)
        hInv = h.inverse()

        val axisWall = frame.dirToWall(pose.transformDir(Vec3(0.0, 0.0, -1.0)))
        angleDeg = Math.toDegrees(acos(abs(axisWall.z).coerceIn(0.0, 1.0)))
        val upWall = frame.dirToWall(pose.transformDir(Vec3(0.0, 1.0, 0.0)))
        rollDeg = Math.toDegrees(atan2(upWall.x, upWall.y))
    }

    /** Writes pixel (u, v) for wall point (xMm, yMm) into [out]; false if the point is behind the camera. */
    fun wallToPixel(xMm: Double, yMm: Double, out: DoubleArray): Boolean {
        val m = h.m
        val w = m[6] * xMm + m[7] * yMm + m[8]
        if (w <= 1e-9) return false
        out[0] = (m[0] * xMm + m[1] * yMm + m[2]) / w
        out[1] = (m[3] * xMm + m[4] * yMm + m[5]) / w
        return true
    }

    fun pixelToWall(u: Double, v: Double): DoubleArray {
        val m = hInv.m
        val w = m[6] * u + m[7] * v + m[8]
        return doubleArrayOf((m[0] * u + m[1] * v + m[2]) / w, (m[3] * u + m[4] * v + m[5]) / w)
    }

    /** Image corners projected onto the wall, in wall mm. Counter-clockwise. */
    fun footprint(): List<DoubleArray> {
        val w = (k.width - 1).toDouble()
        val hh = (k.height - 1).toDouble()
        val pts = listOf(pixelToWall(0.0, 0.0), pixelToWall(w, 0.0), pixelToWall(w, hh), pixelToWall(0.0, hh))
        return Polygon.ensureCcw(pts)
    }

    /** Approximate source resolution (pixels per mm) at the point (xMm, yMm) on the wall. */
    fun pixelsPerMmAt(xMm: Double, yMm: Double): Double {
        val dx = xMm - cameraInWall.x * 1000.0
        val dy = yMm - cameraInWall.y * 1000.0
        val dz = cameraInWall.z * 1000.0
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val cos = abs(dz) / dist
        return k.fx * cos / dist
    }
}

object Polygon {
    fun area(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a[0] * b[1] - b[0] * a[1]
        }
        return s / 2.0
    }

    fun ensureCcw(p: List<DoubleArray>): List<DoubleArray> = if (area(p) < 0) p.reversed() else p

    /** Clips [subject] by the convex counter-clockwise polygon [clip] (Sutherland-Hodgman). */
    fun clipConvex(subject: List<DoubleArray>, clip: List<DoubleArray>): List<DoubleArray> {
        var out = subject
        for (i in clip.indices) {
            if (out.isEmpty()) return out
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val input = out
            out = ArrayList()
            fun inside(p: DoubleArray) = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0
            fun intersect(p: DoubleArray, q: DoubleArray): DoubleArray {
                val a1 = q[1] - p[1]; val b1 = p[0] - q[0]; val c1 = a1 * p[0] + b1 * p[1]
                val a2 = b[1] - a[1]; val b2 = a[0] - b[0]; val c2 = a2 * a[0] + b2 * a[1]
                val det = a1 * b2 - a2 * b1
                return doubleArrayOf((b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det)
            }
            for (j in input.indices) {
                val cur = input[j]
                val prev = input[(j + input.size - 1) % input.size]
                if (inside(cur)) {
                    if (!inside(prev)) out.add(intersect(prev, cur))
                    out.add(cur)
                } else if (inside(prev)) {
                    out.add(intersect(prev, cur))
                }
            }
        }
        return out
    }

    /** Fraction of [a]'s area that also lies inside [b]. Both convex and counter-clockwise. */
    fun overlapFraction(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        val aa = abs(area(a))
        if (aa < 1e-9) return 0.0
        val inter = clipConvex(a, b)
        if (inter.size < 3) return 0.0
        return abs(area(inter)) / aa
    }

    fun containsConvex(p: List<DoubleArray>, x: Double, y: Double): Boolean {
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            if ((b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) < 0) return false
        }
        return true
    }

    fun bounds(p: List<DoubleArray>): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (q in p) {
            if (q[0] < x0) x0 = q[0]; if (q[0] > x1) x1 = q[0]
            if (q[1] < y0) y0 = q[1]; if (q[1] > y1) y1 = q[1]
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }
}
