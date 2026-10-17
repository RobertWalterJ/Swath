package app.wallcapture.core.extraction

import app.wallcapture.core.ProcessingConfig
import app.wallcapture.core.image.Raster
import app.wallcapture.core.model.BoxMm
import app.wallcapture.core.model.Note
import app.wallcapture.core.model.TextLine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** OCR sits behind an interface so core stays free of Android. ML Kit implements it in :ocr-mlkit. */
fun interface TextReader {
    fun read(image: Raster): List<TextLine>
}

data class DetectedNote(
    val boxMm: BoxMm,
    val colour: String,
    val crop: Raster,
)

object ColourNamer {
    fun rgbToHsv(r: Float, g: Float, b: Float): DoubleArray {
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d < 1e-9 -> 0.0
            mx == rf -> 60 * (((gf - bf) / d) % 6)
            mx == gf -> 60 * ((bf - rf) / d + 2)
            else -> 60 * ((rf - gf) / d + 4)
        }
        return doubleArrayOf((h + 360) % 360, if (mx < 1e-9) 0.0 else d / mx, mx)
    }

    /** Fixed palette: yellow, pink, orange, green, blue, purple, white. */
    fun name(r: Float, g: Float, b: Float): String {
        val (h, s, v) = rgbToHsv(r, g, b).let { Triple(it[0], it[1], it[2]) }
        if (s < 0.18 && v > 0.75) return "white"
        return when {
            h >= 330 || h < 12 -> "pink"
            h < 40 -> "orange"
            h < 70 -> "yellow"
            h < 170 -> "green"
            h < 260 -> "blue"
            else -> "purple"
        }
    }
}

/** Finds sticky notes on the finished mosaic by colour segmentation and rectangle fitting. */
class NoteDetector(private val cfg: ProcessingConfig) {
    private class Rect(val cx: Double, val cy: Double, val w: Double, val h: Double, val angleRad: Double, val fill: Double)

    /** [mosaic] is at [pxPerMm]; returned boxes are in mm with origin at the bottom-left of the mosaic. */
    fun detect(mosaic: Raster, pxPerMm: Double): List<DetectedNote> {
        // Work on a 1 px/mm grid.
        val step = max(1.0, pxPerMm)
        val gridMm = step / pxPerMm // one grid cell, in mm
        val mosaicHeightMm = mosaic.height / pxPerMm
        val gw = (mosaic.width / step).toInt()
        val gh = (mosaic.height / step).toInt()
        if (gw < 4 || gh < 4) return emptyList()
        val rgb = FloatArray(gw * gh * 3)
        for (y in 0 until gh) for (x in 0 until gw) {
            val sx = min(mosaic.width - 1, (x * step + step / 2).toInt())
            val sy = min(mosaic.height - 1, (y * step + step / 2).toInt())
            val si = mosaic.idx(sx, sy)
            val o = (y * gw + x) * 3
            rgb[o] = mosaic.data[si]; rgb[o + 1] = mosaic.data[si + 1]; rgb[o + 2] = mosaic.data[si + 2]
        }
        val bg = backgroundColour(rgb)
        val bgHsv = ColourNamer.rgbToHsv(bg[0], bg[1], bg[2])
        val whiteBg = bgHsv[1] < 0.15 && bgHsv[2] > 0.8

        val mask = BooleanArray(gw * gh)
        for (i in 0 until gw * gh) {
            val r = rgb[i * 3]; val g = rgb[i * 3 + 1]; val b = rgb[i * 3 + 2]
            val hsv = ColourNamer.rgbToHsv(r, g, b)
            val dist = sqrt(((r - bg[0]) * (r - bg[0]) + (g - bg[1]) * (g - bg[1]) + (b - bg[2]) * (b - bg[2])).toDouble())
            val chroma = hsv[1] >= cfg.noteMinSaturation && hsv[2] >= 0.30 && dist > cfg.noteMinChromaDistance
            val white = !whiteBg && hsv[1] < 0.12 && hsv[2] > 0.9 && dist > 30
            mask[i] = chroma || white
        }

        val out = ArrayList<DetectedNote>()
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        for (start in 0 until gw * gh) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            val pts = ArrayList<Int>()
            while (sp > 0) {
                val p = stack[--sp]
                pts.add(p)
                val px = p % gw; val py = p / gw
                if (px > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (px < gw - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (py > 0 && mask[p - gw] && !seen[p - gw]) { seen[p - gw] = true; stack[sp++] = p - gw }
                if (py < gh - 1 && mask[p + gw] && !seen[p + gw]) { seen[p + gw] = true; stack[sp++] = p + gw }
            }
            val minArea = cfg.noteMinMm * cfg.noteMinMm * 0.4
            if (pts.size * gridMm * gridMm < minArea) continue
            val rect = minAreaRect(pts, gw)
            val longSide = max(rect.w, rect.h) * gridMm; val shortSide = min(rect.w, rect.h) * gridMm
            if (shortSide < cfg.noteMinMm || longSide > cfg.noteMaxMm) continue
            val aspect = rect.w / rect.h
            if (aspect < cfg.noteMinAspect || aspect > cfg.noteMaxAspect) continue
            if (rect.fill < 0.5) continue

            var sr = 0.0; var sg = 0.0; var sb = 0.0
            for (p in pts) { sr += rgb[p * 3]; sg += rgb[p * 3 + 1]; sb += rgb[p * 3 + 2] }
            val colour = ColourNamer.name((sr / pts.size).toFloat(), (sg / pts.size).toFloat(), (sb / pts.size).toFloat())

            // Grid cell (i, j) covers [i, i+1) x [j, j+1) cells; rect centre is in cell indices, so add half a cell.
            val cxMm = (rect.cx + 0.5) * gridMm
            val cyMm = mosaicHeightMm - (rect.cy + 0.5) * gridMm // y up from the bottom of the mosaic
            val wMm = rect.w * gridMm; val hMm = rect.h * gridMm
            val box = BoxMm(cxMm - wMm / 2, cyMm - hMm / 2, wMm, hMm, -Math.toDegrees(rect.angleRad))
            val crop = deskewFrom(mosaic, pxPerMm, 0.0, mosaicHeightMm, box)
            out.add(DetectedNote(box, colour, crop))
        }
        return out.sortedWith(compareBy({ -(it.boxMm.y / 150).toInt() }, { it.boxMm.x }))
    }

    private fun backgroundColour(rgb: FloatArray): FloatArray {
        val n = rgb.size / 3
        val stride = max(1, n / 20000)
        val rs = ArrayList<Float>(); val gs = ArrayList<Float>(); val bs = ArrayList<Float>()
        var i = 0
        while (i < n) { rs.add(rgb[i * 3]); gs.add(rgb[i * 3 + 1]); bs.add(rgb[i * 3 + 2]); i += stride }
        rs.sort(); gs.sort(); bs.sort()
        return floatArrayOf(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
    }

    /** Minimum-area bounding rectangle by sweeping angles; sizes are in grid pixels. */
    private fun minAreaRect(pts: List<Int>, gw: Int): Rect {
        val sample = if (pts.size > 6000) pts.filterIndexed { i, _ -> i % (pts.size / 6000 + 1) == 0 } else pts
        val xs = DoubleArray(sample.size) { (sample[it] % gw).toDouble() }
        val ys = DoubleArray(sample.size) { (sample[it] / gw).toDouble() }
        fun eval(a: Double): DoubleArray {
            val c = cos(a); val s = sin(a)
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (k in xs.indices) {
                val u = xs[k] * c + ys[k] * s
                val v = -xs[k] * s + ys[k] * c
                if (u < x0) x0 = u; if (u > x1) x1 = u
                if (v < y0) y0 = v; if (v > y1) y1 = v
            }
            return doubleArrayOf((x1 - x0 + 1) * (y1 - y0 + 1), x0, x1, y0, y1)
        }
        var bestA = 0.0; var best = eval(0.0)
        var a = 0.0
        while (a < Math.PI / 2) {
            val e = eval(a)
            if (e[0] < best[0]) { best = e; bestA = a }
            a += Math.toRadians(2.0)
        }
        var fine = bestA - Math.toRadians(2.0)
        while (fine <= bestA + Math.toRadians(2.0)) {
            val e = eval(fine)
            if (e[0] < best[0]) { best = e; bestA = fine }
            fine += Math.toRadians(0.5)
        }
        val c = cos(bestA); val s = sin(bestA)
        val w = best[2] - best[1] + 1
        val h = best[4] - best[3] + 1
        val ucx = (best[1] + best[2]) / 2; val vcy = (best[3] + best[4]) / 2
        val cx = ucx * c - vcy * s
        val cy = ucx * s + vcy * c
        val fill = pts.size / (w * h)
        return Rect(cx, cy, w, h, bestA, fill)
    }

    companion object {
        /**
         * Cuts a note out of [src] and straightens it. [src] has [srcPxPerMm] pixels per mm and its top-left corner is
         * at ([srcLeftMm], [srcTopMm]) in wall mm (y up). The crop is [box].w x [box].h mm at the same resolution.
         */
        fun deskewFrom(src: Raster, srcPxPerMm: Double, srcLeftMm: Double, srcTopMm: Double, box: BoxMm): Raster {
            val w = max(1, (box.w * srcPxPerMm).roundToInt())
            val h = max(1, (box.h * srcPxPerMm).roundToInt())
            val out = Raster(w, h)
            val theta = Math.toRadians(box.rotationDeg) // counter-clockwise in y-up wall space
            val c = cos(theta); val s = sin(theta)
            val tmp = FloatArray(3)
            for (y in 0 until h) for (x in 0 until w) {
                val u = (x + 0.5) / srcPxPerMm - box.w / 2 // along the box's right axis
                val v = (y + 0.5) / srcPxPerMm - box.h / 2 // along the box's down axis
                val xMm = box.centreX + u * c + v * s
                val yMm = box.centreY + u * s - v * c
                val px = (xMm - srcLeftMm) * srcPxPerMm - 0.5
                val py = (srcTopMm - yMm) * srcPxPerMm - 0.5
                if (src.sample(px, py, tmp)) out.set(x, y, tmp[0], tmp[1], tmp[2])
            }
            return out
        }
    }
}

/** DBSCAN with minPts = 1, so lone notes form their own cluster. Returns a cluster id per box. */
object Clusterer {
    fun cluster(boxes: List<BoxMm>, epsNoteWidths: Double): IntArray {
        val n = boxes.size
        val ids = IntArray(n) { -1 }
        if (n == 0) return ids
        val widths = boxes.map { max(it.w, it.h) }.sorted()
        val eps = epsNoteWidths * widths[widths.size / 2]
        var next = 0
        for (i in 0 until n) {
            if (ids[i] != -1) continue
            val queue = ArrayDeque<Int>()
            queue.add(i); ids[i] = next
            while (queue.isNotEmpty()) {
                val a = queue.removeFirst()
                for (b in 0 until n) {
                    if (ids[b] != -1) continue
                    if (hypot(boxes[a].centreX - boxes[b].centreX, boxes[a].centreY - boxes[b].centreY) <= eps) {
                        ids[b] = next; queue.add(b)
                    }
                }
            }
            next++
        }
        return ids
    }
}

/** Runs a [TextReader] over overlapping windows of a big mosaic and merges duplicates. */
object WindowedOcr {
    fun read(image: Raster, reader: TextReader, windowPx: Int, overlap: Double): List<TextLine> {
        val stride = max(1, (windowPx * (1 - overlap)).toInt())
        val all = ArrayList<TextLine>()
        var y = 0
        while (true) {
            var x = 0
            val h = min(windowPx, image.height - y)
            while (true) {
                val w = min(windowPx, image.width - x)
                val win = if (x == 0 && y == 0 && w == image.width && h == image.height) image else image.crop(x, y, w, h)
                for (l in reader.read(win)) all.add(l.copy(x = l.x + x, y = l.y + y))
                if (x + windowPx >= image.width) break
                x += stride
            }
            if (y + windowPx >= image.height) break
            y += stride
        }
        return dedupe(all)
    }

    /** Keeps the more confident of two lines whose boxes overlap heavily and whose text matches loosely. */
    fun dedupe(lines: List<TextLine>): List<TextLine> {
        val kept = ArrayList<TextLine>()
        for (l in lines.sortedByDescending { it.confidence }) {
            val dup = kept.any { k ->
                val ix = max(0.0, min(k.x + k.w, l.x + l.w) - max(k.x, l.x))
                val iy = max(0.0, min(k.y + k.h, l.y + l.h) - max(k.y, l.y))
                val inter = ix * iy
                val smaller = min(k.w * k.h, l.w * l.h)
                smaller > 0 && inter / smaller > 0.5
            }
            if (!dup) kept.add(l)
        }
        return kept
    }
}

object NoteBuilder {
    /** Turns detections into notes: clusters them and OCRs each crop. */
    fun build(detected: List<DetectedNote>, reader: TextReader?, cfg: ProcessingConfig): List<Note> {
        val clusters = Clusterer.cluster(detected.map { it.boxMm }, cfg.clusterEpsNoteWidths)
        return detected.mapIndexed { i, d ->
            val lines = reader?.read(d.crop).orEmpty()
            val text = lines.sortedBy { it.y }.joinToString("\n") { it.text }
            val conf = if (lines.isEmpty()) 0.0 else lines.map { it.confidence }.average()
            Note(
                id = "n%03d".format(i + 1),
                boxMm = d.boxMm,
                colour = d.colour,
                clusterId = clusters[i],
                ocrText = text,
                ocrConfidence = conf,
            )
        }
    }
}

internal fun angleBetween(ax: Double, ay: Double, bx: Double, by: Double) = abs(atan2(ax * by - ay * bx, ax * bx + ay * by))
