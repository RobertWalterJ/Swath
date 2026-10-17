package app.wallcapture.core.image

import kotlin.math.max
import kotlin.math.min

/** Interleaved RGB float image, values 0..255. Pixel centres at integer coordinates. */
class Raster(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height * 3)) {
    fun idx(x: Int, y: Int) = (y * width + x) * 3

    fun set(x: Int, y: Int, r: Float, g: Float, b: Float) {
        val i = idx(x, y)
        data[i] = r; data[i + 1] = g; data[i + 2] = b
    }

    fun luminance(x: Int, y: Int): Float {
        val i = idx(x, y)
        return 0.299f * data[i] + 0.587f * data[i + 1] + 0.114f * data[i + 2]
    }

    /** Bilinear sample at (u, v); false when outside the image. */
    fun sample(u: Double, v: Double, out: FloatArray): Boolean {
        if (u < 0 || v < 0 || u > width - 1 || v > height - 1) return false
        val x0 = u.toInt(); val y0 = v.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val fx = (u - x0).toFloat(); val fy = (v - y0).toFloat()
        val i00 = idx(x0, y0); val i10 = idx(x1, y0); val i01 = idx(x0, y1); val i11 = idx(x1, y1)
        for (c in 0 until 3) {
            val top = data[i00 + c] * (1 - fx) + data[i10 + c] * fx
            val bot = data[i01 + c] * (1 - fx) + data[i11 + c] * fx
            out[c] = top * (1 - fy) + bot * fy
        }
        return true
    }

    fun toGray(): Gray {
        val g = Gray(width, height)
        for (y in 0 until height) for (x in 0 until width) g.data[y * width + x] = luminance(x, y)
        return g
    }

    /** Separable box blur with edge-shrinking windows. */
    fun boxBlur(radius: Int): Raster {
        if (radius <= 0) return Raster(width, height, data.copyOf())
        val tmp = FloatArray(data.size)
        val out = Raster(width, height)
        val prefix = DoubleArray(max(width, height) + 1)
        for (c in 0 until 3) {
            for (y in 0 until height) {
                prefix[0] = 0.0
                for (x in 0 until width) prefix[x + 1] = prefix[x] + data[idx(x, y) + c]
                for (x in 0 until width) {
                    val lo = max(x - radius, 0); val hi = min(x + radius, width - 1)
                    tmp[idx(x, y) + c] = ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
                }
            }
            for (x in 0 until width) {
                prefix[0] = 0.0
                for (y in 0 until height) prefix[y + 1] = prefix[y] + tmp[idx(x, y) + c]
                for (y in 0 until height) {
                    val lo = max(y - radius, 0); val hi = min(y + radius, height - 1)
                    out.data[idx(x, y) + c] = ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
                }
            }
        }
        return out
    }

    fun crop(x0: Int, y0: Int, w: Int, h: Int): Raster {
        val out = Raster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x0 + x; val sy = y0 + y
            if (sx in 0 until width && sy in 0 until height) {
                val si = idx(sx, sy)
                out.set(x, y, data[si], data[si + 1], data[si + 2])
            }
        }
        return out
    }

    /** Half-size image by averaging 2x2 blocks. */
    fun downscale2(): Raster {
        val w = max(1, width / 2); val h = max(1, height / 2)
        val out = Raster(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            for (c in 0 until 3) {
                var s = 0f
                var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val sx = min(x * 2 + dx, width - 1); val sy = min(y * 2 + dy, height - 1)
                    s += data[idx(sx, sy) + c]; n++
                }
                out.data[out.idx(x, y) + c] = s / n
            }
        }
        return out
    }

    companion object {
        fun filled(w: Int, h: Int, r: Float, g: Float, b: Float): Raster {
            val ra = Raster(w, h)
            for (i in 0 until w * h) {
                ra.data[i * 3] = r; ra.data[i * 3 + 1] = g; ra.data[i * 3 + 2] = b
            }
            return ra
        }
    }
}

class Gray(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    operator fun get(x: Int, y: Int) = data[y * width + x]

    fun downscaleBy(f: Int): Gray {
        if (f <= 1) return this
        val w = max(1, width / f); val h = max(1, height / f)
        val out = Gray(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val sx = min(x * f + dx, width - 1); val sy = min(y * f + dy, height - 1)
                s += this[sx, sy]; n++
            }
            out.data[y * w + x] = s / n
        }
        return out
    }
}

object Sharpness {
    /** Variance of the 4-neighbour Laplacian. Higher means sharper. */
    fun varianceOfLaplacian(g: Gray): Double {
        if (g.width < 3 || g.height < 3) return 0.0
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until g.height - 1) for (x in 1 until g.width - 1) {
            val l = g[x - 1, y] + g[x + 1, y] + g[x, y - 1] + g[x, y + 1] - 4f * g[x, y]
            sum += l; sumSq += l.toDouble() * l; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Scores a full frame on a copy no larger than [maxDim] pixels on its long side. */
    fun score(r: Raster, maxDim: Int = 512): Double {
        val f = max(1, max(r.width, r.height) / maxDim)
        return varianceOfLaplacian(r.toGray().downscaleBy(f))
    }
}

/** Encoding lives behind this interface so core has no Android or javax.imageio dependency. */
interface ImageCodec {
    fun encodeJpeg(r: Raster, quality: Int = 90): ByteArray
    fun encodePng(r: Raster): ByteArray
    fun decode(bytes: ByteArray): Raster
}
