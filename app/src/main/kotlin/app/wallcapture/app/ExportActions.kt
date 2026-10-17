package app.wallcapture.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.wallcapture.core.export.Exporter
import app.wallcapture.core.model.SessionMeta
import app.wallcapture.storage.NoteEntity
import app.wallcapture.storage.SessionEntity
import app.wallcapture.storage.SessionStore
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

enum class ExportKind(val label: Int, val ext: String, val mime: String) {
    Pdf(R.string.export_pdf, "pdf", "application/pdf"),
    Png(R.string.export_png, "png", "image/png"),
    Jpeg(R.string.export_jpeg, "jpg", "image/jpeg"),
    Csv(R.string.export_csv, "csv", "text/csv"),
    Json(R.string.export_json, "json", "application/json"),
    Zip(R.string.export_zip, "zip", "application/zip"),
}

/** Builds export files into the session's exports/ folder and hands them to the Android share sheet. */
class ExportActions(private val context: Context, private val store: SessionStore) {
    fun build(kind: ExportKind, s: SessionEntity, notes: List<NoteEntity>, reader: TileReader?): File {
        val out = File(store.exports(s.id), "${safeName(s.title)}.${kind.ext}")
        val domain = notes.map { n ->
            app.wallcapture.core.model.Note(
                n.noteId, app.wallcapture.core.model.BoxMm(n.x, n.y, n.w, n.h, n.rotation), n.colour, n.clusterId,
                n.ocrText, n.ocrConfidence, n.userText, n.userColour,
            )
        }
        when (kind) {
            ExportKind.Csv -> out.writeBytes(Exporter.notesCsv(domain))
            ExportKind.Json -> out.writeText(
                Exporter.sessionJson(
                    SessionMeta(s.id, s.title, s.createdAt.toString(), s.location, s.tags.split(",").filter { it.isNotBlank() }, s.wallWidthMm, s.wallHeightMm, s.pxPerMm, s.status),
                    domain, emptyList(),
                ),
            )
            ExportKind.Zip -> {
                val crops = notes.mapNotNull { n ->
                    val f = File(store.notes(s.id), n.cropFileName ?: return@mapNotNull null)
                    if (f.exists()) n.noteId to f.readBytes() else null
                }
                out.writeBytes(zipOf(crops))
            }
            ExportKind.Jpeg -> File(store.mosaic(s.id), "preview.jpg").copyTo(out, overwrite = true)
            ExportKind.Png -> pngOf(reader ?: error("no mosaic yet"), out)
            ExportKind.Pdf -> pdfOf(s, notes, reader ?: error("no mosaic yet"), out)
        }
        return out
    }

    fun share(kind: ExportKind, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = kind.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun zipOf(items: List<Pair<String, ByteArray>>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            for ((id, bytes) in items) { z.putNextEntry(java.util.zip.ZipEntry("$id.jpg")); z.write(bytes); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    /** Android cannot hold a 190 MP bitmap, so the PNG uses the sharpest pyramid level that fits in about 36 MP. */
    private fun pngOf(reader: TileReader, out: File) {
        var level = 0
        while (level < reader.meta.levels - 1 && reader.meta.levelWidth(level).toLong() * reader.meta.levelHeight(level) > 36_000_000L) level++
        val bmp = reader.region(level, 0, 0, reader.meta.levelWidth(level), reader.meta.levelHeight(level))
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /**
     * Overview page, then pages that tile the wall at readable scale (about 900 mm of wall per page width).
     * Each page also carries a near-invisible text layer so the PDF is searchable and selectable.
     */
    private fun pdfOf(s: SessionEntity, notes: List<NoteEntity>, reader: TileReader, out: File) {
        val doc = PdfDocument()
        val pageW = 842; val pageH = 595 // A4 landscape in points
        val m = reader.meta
        val title = Paint().apply { textSize = 16f; isFakeBoldText = true }

        // Overview page.
        run {
            val level = m.levels - 1
            val overview = reader.region(level, 0, 0, m.levelWidth(level), m.levelHeight(level))
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
            page.canvas.drawText(s.title, 24f, 32f, title)
            val box = RectF(24f, 48f, pageW - 24f, pageH - 24f)
            val scale = min(box.width() / overview.width, box.height() / overview.height)
            page.canvas.drawBitmap(overview, null, RectF(box.left, box.top, box.left + overview.width * scale, box.top + overview.height * scale), null)
            doc.finishPage(page)
            overview.recycle()
        }

        val winWmm = 900.0
        val winHmm = winWmm * pageH / pageW
        val pxW = (winWmm * m.pxPerMm).toInt(); val pxH = (winHmm * m.pxPerMm).toInt()
        val cols = ceil(m.widthPx / pxW.toDouble()).toInt(); val rows = ceil(m.heightPx / pxH.toDouble()).toInt()
        var pageNo = 2
        val textPaint = Paint().apply { alpha = 2; color = 0xFF000000.toInt() }
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = c * pxW; val y0 = r * pxH
            val w = min(pxW, m.widthPx - x0); val h = min(pxH, m.heightPx - y0)
            val bmp = reader.region(0, x0, y0, w, h)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo++).create())
            val k = pageW.toFloat() / pxW // points per pixel
            page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, w * k, h * k), null)
            for (n in notes) {
                val text = n.userText ?: n.ocrText
                if (text.isBlank()) continue
                val nx = n.x * m.pxPerMm - x0; val ny = (m.heightPx - (n.y + n.h) * m.pxPerMm) - y0
                if (nx < 0 || ny < 0 || nx > w || ny > h) continue
                textPaint.textSize = maxOf(4f, (n.h * m.pxPerMm * k / 5).toFloat())
                var line = 0
                for (t in text.lines()) page.canvas.drawText(t, (nx * k).toFloat(), (ny * k).toFloat() + textPaint.textSize * (++line), textPaint)
            }
            doc.finishPage(page)
            bmp.recycle()
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun safeName(t: String) = t.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "wall" }
}
