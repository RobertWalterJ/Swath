package app.wallcapture.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.LruCache
import app.wallcapture.core.export.Json
import app.wallcapture.core.export.asDouble
import app.wallcapture.core.export.asMap
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** Size of the pyramid, written to tiles/info.json by the processor. */
data class PyramidMeta(val levels: Int, val widthPx: Int, val heightPx: Int, val tilePx: Int, val pxPerMm: Double) {
    fun levelWidth(level: Int): Int { var w = widthPx; repeat(level) { w = max(1, (w + 1) / 2) }; return w }
    fun levelHeight(level: Int): Int { var h = heightPx; repeat(level) { h = max(1, (h + 1) / 2) }; return h }
    fun tilesX(level: Int) = ceil(levelWidth(level) / tilePx.toDouble()).toInt()
    fun tilesY(level: Int) = ceil(levelHeight(level) / tilePx.toDouble()).toInt()

    companion object {
        fun write(dir: File, m: PyramidMeta) {
            File(dir, "info.json").writeText(
                Json.write(mapOf("levels" to m.levels, "width" to m.widthPx, "height" to m.heightPx, "tile" to m.tilePx, "pxPerMm" to m.pxPerMm)),
            )
        }

        fun read(dir: File): PyramidMeta? = try {
            val m = Json.parse(File(dir, "info.json").readText()).asMap()
            PyramidMeta(
                m.getValue("levels").asDouble().toInt(), m.getValue("width").asDouble().toInt(),
                m.getValue("height").asDouble().toInt(), m.getValue("tile").asDouble().toInt(), m.getValue("pxPerMm").asDouble(),
            )
        } catch (e: Exception) { null }
    }
}

/** Reads tiles from a session's pyramid, with a small memory cache shared by the viewer and the exporters. */
class TileReader(private val tilesDir: File, val meta: PyramidMeta) {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(level: Int, x: Int, y: Int): Bitmap? = cache.get("$level/$x/$y")

    fun load(level: Int, x: Int, y: Int): Bitmap? {
        cached(level, x, y)?.let { return it }
        val f = File(tilesDir, "$level/${x}_$y.jpg")
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        cache.put("$level/$x/$y", bmp)
        return bmp
    }

    /** Composes a region of one level into a bitmap, in that level's pixels. */
    fun region(level: Int, x0: Int, y0: Int, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val t = meta.tilePx
        for (ty in (y0 / t)..((y0 + h - 1) / t)) for (tx in (x0 / t)..((x0 + w - 1) / t)) {
            val tile = load(level, tx, ty) ?: continue
            canvas.drawBitmap(tile, null, Rect(tx * t - x0, ty * t - y0, tx * t - x0 + tile.width, ty * t - y0 + tile.height), null)
        }
        return out
    }
}
