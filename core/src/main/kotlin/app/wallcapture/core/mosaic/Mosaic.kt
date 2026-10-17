package app.wallcapture.core.mosaic

import app.wallcapture.core.ProcessingConfig
import app.wallcapture.core.geometry.Intrinsics
import app.wallcapture.core.geometry.Mat4
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.geometry.Polygon
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.image.Raster
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun interface ImageSource {
    fun load(): Raster
}

/** One kept still: pixels (lazily loaded), measured pose, intrinsics and its sharpness score. */
class StillInput(
    val id: String,
    val intrinsics: Intrinsics,
    val pose: Mat4,
    val sharpness: Double,
    val source: ImageSource,
)

/** In-plane correction for one still, in wall mm. The still is sampled at (x + dx, y + dy). */
data class WallOffset(val dx: Double, val dy: Double) {
    companion object { val ZERO = WallOffset(0.0, 0.0) }
}

/** Extent of the mosaic in wall-frame mm. */
data class Extent(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
}

class MosaicTile(val raster: Raster, val covered: BooleanArray)

/**
 * Renders the wall as seen straight on. Pixel (i, j) of the mosaic is at wall
 * x = minX + (i + 0.5) / s and y = maxY - (j + 0.5) / s, where s is pixels per mm.
 */
class MosaicRenderer(
    val frame: WallFrame,
    val stills: List<StillInput>,
    val offsets: Map<String, WallOffset>,
    val cfg: ProcessingConfig,
    val pxPerMm: Double = cfg.pxPerMm,
    extentOverride: Extent? = null,
) {
    private class Loaded(
        val still: StillInput,
        val h: PlaneHomography,
        val offset: WallOffset,
        val raster: Raster,
        val blurred: Raster,
        val sharpNorm: Double,
        val sx: Double,
        val sy: Double,
    )

    private val homographies = stills.associate { it.id to PlaneHomography(frame, it.pose, it.intrinsics) }
    private val maxSharp = max(1e-9, stills.maxOfOrNull { it.sharpness } ?: 1.0)
    private val cache = object : LinkedHashMap<String, Loaded>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loaded>?) = size > 6
    }

    val extent: Extent = extentOverride ?: run {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (s in stills) {
            val o = offsets[s.id] ?: WallOffset.ZERO
            for (p in homographies.getValue(s.id).footprint()) {
                x0 = min(x0, p[0] - o.dx); x1 = max(x1, p[0] - o.dx)
                y0 = min(y0, p[1] - o.dy); y1 = max(y1, p[1] - o.dy)
            }
        }
        require(x0 < x1) { "no stills" }
        Extent(x0, y0, x1, y1)
    }

    val widthPx: Int = ceil(extent.width * pxPerMm).toInt()
    val heightPx: Int = ceil(extent.height * pxPerMm).toInt()

    private fun load(s: StillInput): Loaded = cache.getOrPut(s.id) {
        val h = homographies.getValue(s.id)
        val raster = s.source.load()
        val sx = raster.width.toDouble() / s.intrinsics.width
        val sy = raster.height.toDouble() / s.intrinsics.height
        val srcPxPerMm = s.intrinsics.fx * sx / (h.distanceM * 1000.0)
        val radius = max(1, (cfg.lowBandRadiusMm * srcPxPerMm).roundToInt())
        Loaded(s, h, offsets[s.id] ?: WallOffset.ZERO, raster, raster.boxBlur(radius), s.sharpness / maxSharp, sx, sy)
    }

    /** Renders pixels [x0, x0 + w) x [y0, y0 + h) of the mosaic. */
    fun render(x0: Int, y0: Int, w: Int, h: Int): MosaicTile {
        val out = Raster(w, h)
        val covered = BooleanArray(w * h)
        val left = extent.minX + x0 / pxPerMm
        val right = extent.minX + (x0 + w) / pxPerMm
        val top = extent.maxY - y0 / pxPerMm
        val bottom = extent.maxY - (y0 + h) / pxPerMm
        val region = listOf(
            doubleArrayOf(left, bottom), doubleArrayOf(right, bottom),
            doubleArrayOf(right, top), doubleArrayOf(left, top),
        )
        val loaded = ArrayList<Loaded>()
        for (s in stills) {
            val o = offsets[s.id] ?: WallOffset.ZERO
            val fp = homographies.getValue(s.id).footprint()
                .map { doubleArrayOf(it[0] - o.dx, it[1] - o.dy) }
            if (Polygon.clipConvex(fp, region).size >= 3) loaded.add(load(s))
        }
        val n = loaded.size
        val cr = FloatArray(n); val cg = FloatArray(n); val cb = FloatArray(n)
        val lr = FloatArray(n); val lg = FloatArray(n); val lb = FloatArray(n)
        val wt = FloatArray(n); val lum = FloatArray(n)
        val tmp = FloatArray(3)
        val uv = DoubleArray(2)

        for (j in 0 until h) {
            val y = extent.maxY - (y0 + j + 0.5) / pxPerMm
            for (i in 0 until w) {
                val x = extent.minX + (x0 + i + 0.5) / pxPerMm
                var m = 0
                for (c in loaded) {
                    val wx = x + c.offset.dx
                    val wy = y + c.offset.dy
                    if (!c.h.wallToPixel(wx, wy, uv)) continue
                    val img = c.raster
                    // Intrinsics describe the full-size still; the loaded raster may have been decoded smaller.
                    val u = (uv[0] + 0.5) * c.sx - 0.5
                    val v = (uv[1] + 0.5) * c.sy - 0.5
                    if (!img.sample(u, v, tmp)) continue
                    val edge = min(min(u, v), min(img.width - 1 - u, img.height - 1 - v))
                    val feather = cfg.edgeFeatherFraction * min(img.width, img.height)
                    val edgeW = min(1.0, edge / feather)
                    if (edgeW <= 0.0) continue
                    cr[m] = tmp[0]; cg[m] = tmp[1]; cb[m] = tmp[2]
                    c.blurred.sample(u, v, tmp)
                    lr[m] = tmp[0]; lg[m] = tmp[1]; lb[m] = tmp[2]
                    val cam = c.h.cameraInWall
                    val dx = wx - cam.x * 1000.0; val dy = wy - cam.y * 1000.0; val dz = cam.z * 1000.0
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    val cos = abs(dz) / dist
                    val res = c.still.intrinsics.fx * cos / dist
                    wt[m] = ((0.25 + c.sharpNorm) * cos * edgeW * res * 1000.0).toFloat()
                    lum[m] = 0.299f * cr[m] + 0.587f * cg[m] + 0.114f * cb[m]
                    m++
                }
                val o = out.idx(i, j)
                if (m == 0) {
                    out.data[o] = 30f; out.data[o + 1] = 30f; out.data[o + 2] = 30f
                    continue
                }
                covered[j * w + i] = true
                val keep = rejectGlare(m, lum, wt)
                var sw = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                var best = -1; var bestW = -1f
                for (k in 0 until m) {
                    if (!keep[k]) continue
                    sw += wt[k]; sr += wt[k] * lr[k]; sg += wt[k] * lg[k]; sb += wt[k] * lb[k]
                    if (wt[k] > bestW) { bestW = wt[k]; best = k }
                }
                if (best < 0 || sw <= 0f) {
                    out.data[o] = cr[0]; out.data[o + 1] = cg[0]; out.data[o + 2] = cb[0]
                    continue
                }
                // Two-band: weighted-average low frequencies plus high frequencies from the best sample.
                out.data[o] = (sr / sw + cr[best] - lr[best]).coerceIn(0f, 255f)
                out.data[o + 1] = (sg / sw + cg[best] - lg[best]).coerceIn(0f, 255f)
                out.data[o + 2] = (sb / sw + cb[best] - lb[best]).coerceIn(0f, 255f)
            }
        }
        return MosaicTile(out, covered)
    }

    private val keepBuf = BooleanArray(64)

    /** Marks samples to keep: drops saturated samples when a non-saturated one exists, and samples far above the median. */
    private fun rejectGlare(m: Int, lum: FloatArray, wt: FloatArray): BooleanArray {
        val keep = if (m <= keepBuf.size) keepBuf else BooleanArray(m)
        for (k in 0 until m) keep[k] = wt[k] > 0f
        if (m < 2) return keep
        var unsat = 0
        for (k in 0 until m) if (keep[k] && lum[k] < cfg.glareSaturation) unsat++
        if (unsat in 1 until m) {
            for (k in 0 until m) if (lum[k] >= cfg.glareSaturation) keep[k] = false
        }
        // Lower median of the surviving samples.
        val vals = ArrayList<Float>(m)
        for (k in 0 until m) if (keep[k]) vals.add(lum[k])
        if (vals.size >= 2) {
            vals.sort()
            val med = vals[(vals.size - 1) / 2]
            var kept = 0
            for (k in 0 until m) if (keep[k] && lum[k] - med > cfg.glareDelta) keep[k] = false
            for (k in 0 until m) if (keep[k]) kept++
            if (kept == 0) for (k in 0 until m) keep[k] = wt[k] > 0f
        }
        return keep
    }

    /** Renders the whole mosaic into memory. Only for small walls and tests; use [TilePyramid] for real sessions. */
    fun renderAll(): MosaicTile = render(0, 0, widthPx, heightPx)
}

fun interface TileSink {
    fun put(level: Int, tx: Int, ty: Int, jpeg: ByteArray)
}

data class PyramidInfo(val levels: Int, val widthPx: Int, val heightPx: Int, val tilePx: Int)

/** Writes a 256 px tile pyramid by rendering the mosaic in bounded blocks. */
object TilePyramid {
    fun write(renderer: MosaicRenderer, cfg: ProcessingConfig, codec: ImageCodec, sink: TileSink): PyramidInfo {
        val tp = cfg.pyramidTilePx
        val block = max(tp, (cfg.renderTilePx / tp) * tp)
        val w = renderer.widthPx; val h = renderer.heightPx
        var prev = HashMap<Long, ByteArray>()
        fun key(x: Int, y: Int) = (x.toLong() shl 32) or y.toLong()
        for (by in 0 until h step block) for (bx in 0 until w step block) {
            val bw = min(block, w - bx); val bh = min(block, h - by)
            val tile = renderer.render(bx, by, bw, bh).raster
            for (ty in 0 until ceil(bh / tp.toDouble()).toInt()) for (tx in 0 until ceil(bw / tp.toDouble()).toInt()) {
                val sub = tile.crop(tx * tp, ty * tp, min(tp, bw - tx * tp), min(tp, bh - ty * tp))
                val bytes = codec.encodeJpeg(sub, 90)
                val gx = bx / tp + tx; val gy = by / tp + ty
                sink.put(0, gx, gy, bytes)
                prev[key(gx, gy)] = bytes
            }
        }
        var level = 0
        var lw = w; var lh = h
        while (lw > tp || lh > tp) {
            level++
            val nw = max(1, (lw + 1) / 2); val nh = max(1, (lh + 1) / 2)
            val next = HashMap<Long, ByteArray>()
            val tilesX = ceil(nw / tp.toDouble()).toInt(); val tilesY = ceil(nh / tp.toDouble()).toInt()
            for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
                val canvas = Raster(tp * 2, tp * 2)
                for (cy in 0..1) for (cx in 0..1) {
                    val child = prev[key(tx * 2 + cx, ty * 2 + cy)] ?: continue
                    val img = codec.decode(child)
                    for (yy in 0 until img.height) for (xx in 0 until img.width) {
                        val si = img.idx(xx, yy)
                        canvas.set(cx * tp + xx, cy * tp + yy, img.data[si], img.data[si + 1], img.data[si + 2])
                    }
                }
                val small = canvas.downscale2()
                val tw = min(tp, nw - tx * tp); val th = min(tp, nh - ty * tp)
                val bytes = codec.encodeJpeg(small.crop(0, 0, tw, th), 90)
                sink.put(level, tx, ty, bytes)
                next[key(tx, ty)] = bytes
            }
            prev = next
            lw = nw; lh = nh
        }
        return PyramidInfo(level + 1, w, h, tp)
    }
}
