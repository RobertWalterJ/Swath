package app.wallcapture.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.image.Raster
import app.wallcapture.ocr.MlKitTextReader
import java.io.ByteArrayOutputStream

/** Bitmap-backed codec. Large stills are decoded at reduced size so a long wall stays inside the memory budget. */
class AndroidImageCodec(private val maxDecodeDim: Int = 3000) : ImageCodec {
    override fun encodeJpeg(r: Raster, quality: Int): ByteArray {
        val bmp = MlKitTextReader.toBitmap(r)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it); bmp.recycle() }.toByteArray()
    }

    override fun encodePng(r: Raster): ByteArray {
        val bmp = MlKitTextReader.toBitmap(r)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    override fun decode(bytes: ByteArray): Raster {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDecodeDim) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("unreadable image")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val r = Raster(bmp.width, bmp.height)
        for (i in px.indices) {
            val p = px[i]
            r.data[i * 3] = ((p shr 16) and 255).toFloat()
            r.data[i * 3 + 1] = ((p shr 8) and 255).toFloat()
            r.data[i * 3 + 2] = (p and 255).toFloat()
        }
        bmp.recycle()
        return r
    }
}
