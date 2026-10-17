package app.wallcapture.replay

import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.image.Raster
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** javax.imageio codec for the desktop replay tool and tests. Android supplies its own Bitmap codec. */
class JvmImageCodec : ImageCodec {
    private fun toBuffered(r: Raster): BufferedImage {
        val img = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val i = r.idx(x, y)
            val rgb = (r.data[i].toInt().coerceIn(0, 255) shl 16) or
                (r.data[i + 1].toInt().coerceIn(0, 255) shl 8) or r.data[i + 2].toInt().coerceIn(0, 255)
            img.setRGB(x, y, rgb)
        }
        return img
    }

    override fun encodeJpeg(r: Raster, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { out ->
            writer.output = out
            writer.write(null, IIOImage(toBuffered(r), null, null), param)
        }
        writer.dispose()
        return bos.toByteArray()
    }

    override fun encodePng(r: Raster): ByteArray {
        val bos = ByteArrayOutputStream()
        ImageIO.write(toBuffered(r), "png", bos)
        return bos.toByteArray()
    }

    override fun decode(bytes: ByteArray): Raster {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("unreadable image")
        val r = Raster(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            r.set(x, y, ((p shr 16) and 255).toFloat(), ((p shr 8) and 255).toFloat(), (p and 255).toFloat())
        }
        return r
    }
}
