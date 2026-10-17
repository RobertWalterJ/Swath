package app.wallcapture.ocr

import android.graphics.Bitmap
import app.wallcapture.core.extraction.TextReader
import app.wallcapture.core.image.Raster
import app.wallcapture.core.model.TextLine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * ML Kit Text Recognition v2 with the bundled Latin model (English and French), fully on device.
 * [read] blocks, so call it from a background dispatcher.
 */
class MlKitTextReader : TextReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun read(image: Raster): List<TextLine> {
        val bmp = toBitmap(image)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val out = ArrayList<TextLine>()
            for (block in result.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                // ML Kit gives per-line confidence on v16.0.1 via Line.confidence.
                out.add(TextLine(line.text, line.confidence.toDouble(), box.left.toDouble(), box.top.toDouble(), box.width().toDouble(), box.height().toDouble()))
            }
            return out
        } finally {
            bmp.recycle()
        }
    }

    fun close() = recognizer.close()

    companion object {
        fun toBitmap(r: Raster): Bitmap {
            val px = IntArray(r.width * r.height)
            for (i in px.indices) {
                px[i] = (0xFF shl 24) or
                    (r.data[i * 3].toInt().coerceIn(0, 255) shl 16) or
                    (r.data[i * 3 + 1].toInt().coerceIn(0, 255) shl 8) or
                    r.data[i * 3 + 2].toInt().coerceIn(0, 255)
            }
            return Bitmap.createBitmap(px, r.width, r.height, Bitmap.Config.ARGB_8888)
        }
    }
}
