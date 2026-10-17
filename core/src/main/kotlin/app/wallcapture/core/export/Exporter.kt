package app.wallcapture.core.export

import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.model.Note
import app.wallcapture.core.model.SessionMeta
import app.wallcapture.core.model.TextBlock
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {
    val CSV_HEADER = listOf("id", "text", "colour", "cluster", "x_mm", "y_mm", "confidence", "edited")

    /** UTF-8 with a BOM so Excel opens accents correctly; RFC 4180 quoting; CRLF line ends. */
    fun notesCsv(notes: List<Note>): ByteArray {
        val sb = StringBuilder()
        sb.append('﻿')
        sb.append(CSV_HEADER.joinToString(",")).append("\r\n")
        for (n in notes) {
            val row = listOf(
                n.id, n.text, n.shownColour, n.clusterId.toString(),
                "%.1f".format(java.util.Locale.ROOT, n.boxMm.centreX),
                "%.1f".format(java.util.Locale.ROOT, n.boxMm.centreY),
                "%.2f".format(java.util.Locale.ROOT, n.ocrConfidence),
                if (n.edited) "yes" else "no",
            )
            sb.append(row.joinToString(",") { quote(it) }).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quote(s: String): String {
        // Leading = + - @ can be read as formulas by spreadsheets; neutralise them.
        val safe = if (s.isNotEmpty() && s[0] in "=+@" ) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** Minimal RFC 4180 parser used by the round-trip test (and handy for re-import). */
    fun parseCsv(bytes: ByteArray): List<List<String>> {
        var s = String(bytes, Charsets.UTF_8)
        if (s.startsWith('﻿')) s = s.substring(1)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !inQuotes && (c == '\r' || c == '\n') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun sessionJson(meta: SessionMeta, notes: List<Note>, blocks: List<TextBlock>, extra: Map<String, Any?> = emptyMap()): String {
        val m = linkedMapOf<String, Any?>(
            "session" to mapOf(
                "id" to meta.id, "title" to meta.title, "createdAt" to meta.createdAt,
                "location" to meta.location, "tags" to meta.tags,
                "wallWidthMm" to meta.wallWidthMm, "wallHeightMm" to meta.wallHeightMm,
                "pxPerMm" to meta.pxPerMm, "status" to meta.status,
            ),
            "notes" to notes.map { n ->
                mapOf(
                    "id" to n.id, "text" to n.text, "ocrText" to n.ocrText, "colour" to n.shownColour,
                    "cluster" to n.clusterId, "confidence" to n.ocrConfidence, "edited" to n.edited,
                    "box" to listOf(n.boxMm.x, n.boxMm.y, n.boxMm.w, n.boxMm.h, n.boxMm.rotationDeg),
                )
            },
            "textBlocks" to blocks.map { b ->
                mapOf(
                    "id" to b.id, "text" to (b.userText ?: b.ocrText), "confidence" to b.confidence,
                    "noteId" to b.noteId, "needsReview" to b.needsReview,
                    "box" to listOf(b.boxMm.x, b.boxMm.y, b.boxMm.w, b.boxMm.h, b.boxMm.rotationDeg),
                )
            },
        )
        m.putAll(extra)
        return Json.write(m)
    }

    /** Zip of note crops named by id. */
    fun noteImagesZip(crops: Map<String, app.wallcapture.core.image.Raster>, codec: ImageCodec): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((id, r) in crops) {
                z.putNextEntry(ZipEntry("$id.jpg"))
                z.write(codec.encodeJpeg(r, 92))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
