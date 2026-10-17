package app.wallcapture.core.capture

import app.wallcapture.core.CaptureConfig
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.geometry.Polygon
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class CellState { NotCaptured, Good, Weak, Glare }

/** Live coverage map: a sparse grid of 2 cm cells in wall coordinates that grows as the user moves. */
class CoverageGrid(private val cfg: CaptureConfig = CaptureConfig()) {
    private class Cell(var good: Boolean = false, var allGlare: Boolean = true)

    private val cells = HashMap<Long, Cell>()

    private fun key(ix: Int, iy: Int) = (ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)
    private fun cellIndex(mm: Double) = floor(mm / cfg.cellMm).toInt()

    /**
     * Records a kept still. [sharp] says whether it passed the sharpness gate. [glareAt] optionally flags
     * wall points that are saturated in this still.
     */
    fun addStill(h: PlaneHomography, sharp: Boolean, glareAt: ((Double, Double) -> Boolean)? = null) {
        val fp = h.footprint()
        val b = Polygon.bounds(fp)
        val stillGood = sharp && h.angleDeg < cfg.goodMaxAngleDeg
        for (iy in cellIndex(b[1])..cellIndex(b[3])) for (ix in cellIndex(b[0])..cellIndex(b[2])) {
            val cx = (ix + 0.5) * cfg.cellMm
            val cy = (iy + 0.5) * cfg.cellMm
            if (!Polygon.containsConvex(fp, cx, cy)) continue
            val cell = cells.getOrPut(key(ix, iy)) { Cell() }
            val good = stillGood && h.pixelsPerMmAt(cx, cy) >= cfg.goodMinPxPerMm
            if (good) cell.good = true
            val glare = glareAt?.invoke(cx, cy) ?: false
            if (!glare) cell.allGlare = false
        }
    }

    fun stateOf(ix: Int, iy: Int): CellState {
        val c = cells[key(ix, iy)] ?: return CellState.NotCaptured
        return when {
            c.allGlare -> CellState.Glare
            c.good -> CellState.Good
            else -> CellState.Weak
        }
    }

    fun stateAt(xMm: Double, yMm: Double) = stateOf(cellIndex(xMm), cellIndex(yMm))

    /** Fraction of the footprint's cells that no kept still covers yet. */
    fun uncoveredFraction(footprint: List<DoubleArray>): Double {
        val b = Polygon.bounds(footprint)
        var total = 0
        var uncovered = 0
        for (iy in cellIndex(b[1])..cellIndex(b[3])) for (ix in cellIndex(b[0])..cellIndex(b[2])) {
            if (!Polygon.containsConvex(footprint, (ix + 0.5) * cfg.cellMm, (iy + 0.5) * cfg.cellMm)) continue
            total++
            if (!cells.containsKey(key(ix, iy))) uncovered++
        }
        return if (total == 0) 0.0 else uncovered.toDouble() / total
    }

    /** Cell-index bounds [minX, minY, maxX, maxY] of everything captured, or null when empty. */
    fun capturedBounds(): IntArray? {
        if (cells.isEmpty()) return null
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (k in cells.keys) {
            val ix = (k shr 32).toInt()
            val iy = k.toInt()
            x0 = min(x0, ix); x1 = max(x1, ix); y0 = min(y0, iy); y1 = max(y1, iy)
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    /** Empty or weak cells inside the captured bounds: what the "Fill gaps" prompt counts. */
    fun gapCellsInsideBounds(): Int {
        val b = capturedBounds() ?: return 0
        var n = 0
        for (iy in b[1]..b[3]) for (ix in b[0]..b[2]) {
            val s = stateOf(ix, iy)
            if (s == CellState.NotCaptured || s == CellState.Weak) n++
        }
        return n
    }

    val capturedCellCount: Int get() = cells.size

    /** Flattened copy of the captured bounds for drawing the coverage strip; null when nothing is captured. */
    fun snapshot(): CoverageSnapshot? {
        val b = capturedBounds() ?: return null
        val w = b[2] - b[0] + 1
        val h = b[3] - b[1] + 1
        val states = ByteArray(w * h)
        for (iy in 0 until h) for (ix in 0 until w) {
            states[iy * w + ix] = stateOf(b[0] + ix, b[1] + iy).ordinal.toByte()
        }
        return CoverageSnapshot(cfg.cellMm, b[0], b[1], w, h, states)
    }
}

/** [states] holds [CellState] ordinals, row 0 at the bottom of the wall. */
class CoverageSnapshot(
    val cellMm: Double,
    val minIx: Int,
    val minIy: Int,
    val widthCells: Int,
    val heightCells: Int,
    val states: ByteArray,
)
