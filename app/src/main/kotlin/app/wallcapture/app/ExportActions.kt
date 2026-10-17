package app.wallcapture.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import app.wallcapture.core.export.Exporter
import app.wallcapture.core.model.BoxMm
import app.wallcapture.core.model.Note
import app.wallcapture.core.model.SessionMeta
import app.wallcapture.storage.NoteEntity
import app.wallcapture.storage.SessionEntity
import app.wallcapture.storage.SessionStore
import java.io.File

enum class ExportKind(val ext: String, val mime: String) {
    Report("pdf", "application/pdf"),
    Picture("jpg", "image/jpeg"),
    Csv("csv", "text/csv"),
    Png("png", "image/png"),
    Json("json", "application/json"),
    Zip("zip", "application/zip"),
}

/** Builds export files into the session's exports/ folder and hands them to the Android share sheet. */
class ExportActions(private val context: Context, private val store: SessionStore, private val brand: String) {
    fun build(kind: ExportKind, s: SessionEntity, notes: List<NoteEntity>, reader: TileReader?): File {
        val out = File(store.exports(s.id), "${safeName(s.title)}.${kind.ext}")
        val domain = notes.map { n ->
            Note(n.noteId, BoxMm(n.x, n.y, n.w, n.h, n.rotation), n.colour, n.clusterId, n.ocrText, n.ocrConfidence, n.userText, n.userColour)
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
            ExportKind.Picture -> ShareCard.write(out, s, notes, reader ?: error("no mosaic yet"), brand)
            ExportKind.Png -> pngOf(reader ?: error("no mosaic yet"), out)
            ExportKind.Report -> ReportPdf.write(out, s, notes, reader ?: error("no mosaic yet"), brand)
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

    private fun safeName(t: String) = t.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "wall" }
}
