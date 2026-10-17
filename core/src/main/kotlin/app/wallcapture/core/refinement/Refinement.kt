package app.wallcapture.core.refinement

import app.wallcapture.core.ProcessingConfig
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.geometry.Polygon
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.Gray
import app.wallcapture.core.mosaic.StillInput
import app.wallcapture.core.mosaic.WallOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Measured relative shift between two stills, in wall mm: B(x + s) matches A(x). */
data class PairShift(val i: Int, val j: Int, val dxMm: Double, val dyMm: Double, val score: Double)

private class Warped(val data: FloatArray, val valid: BooleanArray, val w: Int, val h: Int)

object PairMatcher {
    private fun sampleGray(g: Gray, u: Double, v: Double): Float? {
        if (u < 0 || v < 0 || u > g.width - 1 || v > g.height - 1) return null
        val x0 = u.toInt(); val y0 = v.toInt()
        val x1 = min(x0 + 1, g.width - 1); val y1 = min(y0 + 1, g.height - 1)
        val fx = (u - x0).toFloat(); val fy = (v - y0).toFloat()
        val top = g[x0, y0] * (1 - fx) + g[x1, y0] * fx
        val bot = g[x0, y1] * (1 - fx) + g[x1, y1] * fx
        return top * (1 - fy) + bot * fy
    }

    private fun warp(h: PlaneHomography, g: Gray, left: Double, top: Double, w: Int, hh: Int, s: Double): Warped {
        val data = FloatArray(w * hh)
        val valid = BooleanArray(w * hh)
        val uv = DoubleArray(2)
        val margin = 0.02 * min(g.width, g.height)
        val sx = g.width.toDouble() / h.k.width // the gray image may be smaller than the full-size still
        val sy = g.height.toDouble() / h.k.height
        for (j in 0 until hh) {
            val y = top - (j + 0.5) / s
            for (i in 0 until w) {
                val x = left + (i + 0.5) / s
                if (!h.wallToPixel(x, y, uv)) continue
                val gu = (uv[0] + 0.5) * sx - 0.5
                val gv = (uv[1] + 0.5) * sy - 0.5
                if (gu < margin || gv < margin || gu > g.width - 1 - margin || gv > g.height - 1 - margin) continue
                val v = sampleGray(g, gu, gv) ?: continue
                data[j * w + i] = v
                valid[j * w + i] = true
            }
        }
        return Warped(data, valid, w, hh)
    }

    /** Normalised cross-correlation of A(i, j) against B(i + du, j + dv). */
    private fun ncc(a: Warped, b: Warped, du: Int, dv: Int, stride: Int): Double {
        var n = 0
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        var j = max(0, -dv)
        val jEnd = min(a.h, b.h - dv)
        val iStart = max(0, -du)
        val iEnd = min(a.w, b.w - du)
        while (j < jEnd) {
            var i = iStart
            while (i < iEnd) {
                val ia = j * a.w + i
                val ib = (j + dv) * b.w + (i + du)
                if (a.valid[ia] && b.valid[ib]) {
                    val x = a.data[ia].toDouble(); val y = b.data[ib].toDouble()
                    sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; n++
                }
                i += stride
            }
            j += stride
        }
        if (n < 150) return -1.0
        val va = n * saa - sa * sa
        val vb = n * sbb - sb * sb
        if (va < 1e-6 || vb < 1e-6) return -1.0
        return (n * sab - sa * sb) / sqrt(va * vb)
    }

    /**
     * Returns the shift of B relative to A, or null if the overlap is too small, too featureless,
     * or the correction exceeds [ProcessingConfig.refineMaxShiftMm].
     */
    fun match(
        ia: Int, ib: Int,
        ha: PlaneHomography, ga: Gray,
        hb: PlaneHomography, gb: Gray,
        cfg: ProcessingConfig,
    ): PairShift? {
        val inter = Polygon.clipConvex(ha.footprint(), hb.footprint())
        if (inter.size < 3) return null
        val bb = Polygon.bounds(inter)
        val pad = cfg.refineSearchMm
        val left = bb[0] - pad; val top = bb[3] + pad
        val wMm = bb[2] - bb[0] + 2 * pad; val hMm = bb[3] - bb[1] + 2 * pad

        val fine = cfg.refineScalePxPerMm
        val coarseF = 4
        val coarse = fine / coarseF

        fun warps(s: Double): Pair<Warped, Warped> {
            val w = ceil(wMm * s).toInt(); val h = ceil(hMm * s).toInt()
            return warp(ha, ga, left, top, w, h, s) to warp(hb, gb, left, top, w, h, s)
        }

        // Coarse search over the whole window.
        val (ca, cb) = warps(coarse)
        val range = ceil(pad * coarse).toInt()
        var bestScore = -2.0; var bu = 0; var bv = 0
        for (dv in -range..range) for (du in -range..range) {
            val sc = ncc(ca, cb, du, dv, 1)
            if (sc > bestScore) { bestScore = sc; bu = du; bv = dv }
        }
        if (bestScore < cfg.refineMinScore) return null

        // Fine search around the coarse optimum, then sub-pixel parabola.
        val (fa, fb) = warps(fine)
        val cu = bu * coarseF; val cv = bv * coarseF
        val r = coarseF + 1
        val grid = Array(2 * r + 1) { DoubleArray(2 * r + 1) }
        var fBest = -2.0; var fu = 0; var fv = 0
        for (dv in -r..r) for (du in -r..r) {
            val sc = ncc(fa, fb, cu + du, cv + dv, 2)
            grid[dv + r][du + r] = sc
            if (sc > fBest) { fBest = sc; fu = du; fv = dv }
        }
        if (fBest < cfg.refineMinScore) return null
        fun parabola(m: Double, c: Double, p: Double): Double {
            val d = m - 2 * c + p
            return if (abs(d) < 1e-12) 0.0 else (0.5 * (m - p) / d).coerceIn(-0.5, 0.5)
        }
        var subU = 0.0; var subV = 0.0
        if (fu > -r && fu < r) subU = parabola(grid[fv + r][fu - 1 + r], fBest, grid[fv + r][fu + 1 + r])
        if (fv > -r && fv < r) subV = parabola(grid[fv - 1 + r][fu + r], fBest, grid[fv + 1 + r][fu + r])
        val du = cu + fu + subU
        val dv = cv + fv + subV
        val dx = du / fine
        val dy = -dv / fine
        if (hypot(dx, dy) > cfg.refineMaxShiftMm) return null
        return PairShift(ia, ib, dx, dy, fBest)
    }
}

/** Weighted least squares over per-still offsets: o_j - o_i = s_ij, with still 0 anchored at zero. */
object PoseGraph {
    fun solve(n: Int, shifts: List<PairShift>): List<WallOffset> {
        if (n <= 1 || shifts.isEmpty()) return List(n) { WallOffset.ZERO }
        val ox = solveAxis(n, shifts) { it.dxMm }
        val oy = solveAxis(n, shifts) { it.dyMm }
        return List(n) { WallOffset(ox[it], oy[it]) }
    }

    private fun solveAxis(n: Int, shifts: List<PairShift>, value: (PairShift) -> Double): DoubleArray {
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (s in shifts) {
            val w = max(0.05, s.score)
            val v = value(s)
            a[s.i][s.i] += w; a[s.j][s.j] += w
            a[s.i][s.j] -= w; a[s.j][s.i] -= w
            b[s.i] -= w * v
            b[s.j] += w * v
        }
        // Anchor still 0 and add a tiny ridge so unconnected stills stay at zero.
        for (k in 0 until n) a[k][k] += 1e-6
        for (k in 0 until n) { a[0][k] = 0.0; a[k][0] = 0.0 }
        a[0][0] = 1.0; b[0] = 0.0
        return gauss(a, b)
    }

    private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val t = a[c]; a[c] = a[p]; a[p] = t
            val tb = b[c]; b[c] = b[p]; b[p] = tb
            val d = a[c][c]
            if (abs(d) < 1e-12) continue
            for (r in c + 1 until n) {
                val f = a[r][c] / d
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else s / a[r][r]
        }
        return x
    }
}

data class RefinementResult(
    val offsets: Map<String, WallOffset>,
    val pairsUsed: Int,
    val pairsDropped: Int,
)

object Refiner {
    fun refine(frame: WallFrame, stills: List<StillInput>, cfg: ProcessingConfig): RefinementResult {
        val hs = stills.map { PlaneHomography(frame, it.pose, it.intrinsics) }
        val grays = arrayOfNulls<Gray>(stills.size)
        fun gray(i: Int): Gray = grays[i] ?: stills[i].source.load().toGray().also { grays[i] = it }
        val shifts = ArrayList<PairShift>()
        var dropped = 0
        for (i in stills.indices) for (j in i + 1 until stills.size) {
            val fa = hs[i].footprint()
            if (Polygon.overlapFraction(fa, hs[j].footprint()) < cfg.refineMinOverlap) continue
            val s = PairMatcher.match(i, j, hs[i], gray(i), hs[j], gray(j), cfg)
            if (s == null) dropped++ else shifts.add(s)
        }
        val solved = PoseGraph.solve(stills.size, shifts)
        return RefinementResult(
            stills.indices.associate { stills[it].id to solved[it] },
            shifts.size,
            dropped,
        )
    }
}
