package app.wallcapture.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import app.wallcapture.app.ui.NoteColours
import app.wallcapture.storage.NoteEntity
import app.wallcapture.storage.SessionEntity
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Colours and type shared by every file Swath makes, so a report and a picture look like one family. */
internal object Look {
    const val INK = 0xFF16181D.toInt()
    const val MUTED = 0xFF5E636E.toInt()
    const val LINE = 0xFFE6E3DB.toInt()
    const val PAPER = 0xFFFBFAF7.toInt()
    const val ACCENT = 0xFF3441D6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun text(size: Float, color: Int = INK, face: Typeface = regular) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = size; this.color = color; typeface = face
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width }

    /** Draws wrapped text and returns its height. */
    fun wrapped(c: Canvas, text: String, x: Float, y: Float, width: Float, paint: TextPaint, maxLines: Int = Int.MAX_VALUE, lineSpacing: Float = 1.15f): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, lineSpacing).build()
        c.save(); c.translate(x, y); layout.draw(c); c.restore()
        return layout.height.toFloat()
    }

    fun wrappedHeight(text: String, width: Float, paint: TextPaint, maxLines: Int = Int.MAX_VALUE): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.15f).build()
        return layout.height.toFloat()
    }

    /** A swatch with the colour's letter inside, so the colour is never the only cue. */
    fun swatch(c: Canvas, name: String, x: Float, y: Float, size: Float) {
        c.drawRoundRect(RectF(x, y, x + size, y + size), size * 0.22f, size * 0.22f, fill(NoteColours.argb(name)))
        c.drawRoundRect(RectF(x, y, x + size, y + size), size * 0.22f, size * 0.22f, stroke(0x33000000, max(0.5f, size * 0.05f)))
        val p = text(size * 0.55f, INK, medium).apply { textAlign = Paint.Align.CENTER }
        c.drawText(NoteColours.letter(name), x + size / 2, y + size * 0.72f, p)
    }

    fun roundedBitmap(c: Canvas, bmp: Bitmap, dst: RectF, radius: Float) {
        c.save()
        val path = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
        c.clipPath(path)
        c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        c.restore()
        c.drawRoundRect(dst, radius, radius, stroke(LINE, 1.5f))
    }

    /** The longest round length that fits in [maxMm], for a scale bar. */
    fun niceLengthMm(maxMm: Double): Double = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0).lastOrNull { it <= maxMm } ?: 50.0
    fun lengthLabel(mm: Double) = if (mm < 1000) "${(mm / 10).toInt()} cm" else "${(mm / 1000).toInt()} m"

    fun scaleBar(c: Canvas, xLeft: Float, yBottom: Float, ptPerMm: Float, maxMm: Double, size: Float, onDarkCard: Boolean = false) {
        val mm = niceLengthMm(maxMm)
        val w = (mm * ptPerMm).toFloat()
        val label = lengthLabel(mm)
        val tp = text(size, INK, medium)
        val pad = size * 0.6f
        val card = RectF(xLeft, yBottom - size * 2.6f - pad, xLeft + max(w, tp.measureText(label)) + pad * 2, yBottom)
        c.drawRoundRect(card, size * 0.5f, size * 0.5f, fill(if (onDarkCard) INK else 0xE6FFFFFF.toInt()))
        c.drawText(label, card.left + pad, card.top + pad + size, text(size, if (onDarkCard) WHITE else INK, medium))
        val barY = card.bottom - pad
        val line = fill(if (onDarkCard) WHITE else INK)
        c.drawRect(card.left + pad, barY - size * 0.22f, card.left + pad + w, barY, line)
        c.drawRect(card.left + pad, barY - size * 0.7f, card.left + pad + size * 0.12f, barY, line)
        c.drawRect(card.left + pad + w - size * 0.12f, barY - size * 0.7f, card.left + pad + w, barY, line)
    }
}

/** A whole-wall bitmap from the pyramid, at least [minWidth] pixels wide where the wall allows. */
internal fun overviewBitmap(reader: TileReader, minWidth: Int): Bitmap {
    val m = reader.meta
    var level = m.levels - 1
    while (level > 0 && m.levelWidth(level) < minWidth) level--
    return reader.region(level, 0, 0, m.levelWidth(level), m.levelHeight(level))
}

private fun dateText(s: SessionEntity) = DateFormat.getDateInstance(DateFormat.LONG).format(Date(s.createdAt))
private fun sizeText(s: SessionEntity) = "%.1f m by %.1f m".format(s.wallWidthMm / 1000.0, s.wallHeightMm / 1000.0)

private fun themes(notes: List<NoteEntity>): List<List<NoteEntity>> =
    notes.groupBy { it.clusterId }.values.sortedByDescending { it.size }.map { list -> list.sortedWith(compareBy({ -(it.y / 150).toInt() }, { it.x })) }

// ---- PDF report ----------------------------------------------------------------------------------------------------

internal object ReportPdf {
    private const val W = 842
    private const val H = 595
    private const val M = 36f

    fun write(out: File, s: SessionEntity, notes: List<NoteEntity>, reader: TileReader, brand: String) {
        val doc = PdfDocument()
        var pageNo = 0
        fun newPage(): PdfDocument.Page { pageNo++; return doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()) }
        fun footer(c: Canvas) {
            c.drawText("Made with $brand", M, H - 18f, Look.text(8f, Look.MUTED))
            c.drawText(pageNo.toString(), W - M, H - 18f, Look.text(8f, Look.MUTED).apply { textAlign = Paint.Align.RIGHT })
        }
        val overview = overviewBitmap(reader, 1800)
        val groups = themes(notes)

        // 1. Cover.
        run {
            val page = newPage(); val c = page.canvas
            c.drawColor(Look.PAPER)
            c.drawRoundRect(RectF(M, 44f, M + 28f, 48f), 2f, 2f, Look.fill(Look.ACCENT))
            c.drawText("Wall report", M, 68f, Look.text(11f, Look.MUTED, Look.medium))
            val titleH = Look.wrapped(c, s.title, M, 80f, 300f, Look.text(30f, Look.INK, Look.bold), maxLines = 4)
            var y = 80f + titleH + 22f
            fun meta(label: String, value: String) {
                c.drawText(label, M, y, Look.text(9f, Look.MUTED))
                c.drawText(value, M, y + 16f, Look.text(13f, Look.INK, Look.medium))
                y += 40f
            }
            meta("Date", dateText(s))
            s.location?.takeIf { it.isNotBlank() }?.let { meta("Place", it) }
            meta("Wall size", sizeText(s))
            meta("Notes", notes.size.toString())
            meta("Themes", groups.size.toString())

            // Colour legend with counts.
            val counts = notes.groupingBy { it.userColour ?: it.colour }.eachCount()
            if (counts.isNotEmpty() && y < H - 150f) {
                c.drawText("Colours", M, y, Look.text(9f, Look.MUTED))
                var ly = y + 10f
                counts.entries.sortedByDescending { it.value }.forEach { (name, n) ->
                    if (ly < H - 40f) {
                        Look.swatch(c, name, M, ly, 14f)
                        c.drawText("${NoteColours.label(name)}   $n", M + 22f, ly + 11f, Look.text(10f, Look.INK))
                        ly += 20f
                    }
                }
            }

            // The wall.
            val box = RectF(372f, M, W - M, H - 80f)
            val sc = min(box.width() / overview.width, box.height() / overview.height)
            val dw = overview.width * sc; val dh = overview.height * sc
            val dst = RectF(box.left, box.top, box.left + dw, box.top + dh)
            Look.roundedBitmap(c, overview, dst, 8f)
            val ptPerMm = (dw / s.wallWidthMm).toFloat()
            Look.scaleBar(c, dst.left, dst.bottom + 56f, ptPerMm, s.wallWidthMm / 3.0, 9f)
            footer(c)
            doc.finishPage(page)
        }

        // 2. Notes by theme.
        if (groups.isNotEmpty()) {
            var page = newPage(); var c = page.canvas
            var y = 0f
            fun header() {
                c.drawColor(Look.PAPER)
                c.drawText("Notes by theme", M, 52f, Look.text(20f, Look.INK, Look.bold))
                c.drawText("${notes.size} notes in ${groups.size} themes", M, 70f, Look.text(10f, Look.MUTED))
                y = 96f
            }
            header()
            val bodyPaint = Look.text(10.5f, Look.INK)
            val textX = M + 30f
            val textW = W - M - textX
            groups.forEachIndexed { gi, list ->
                if (y + 60f > H - 40f) { footer(c); doc.finishPage(page); page = newPage(); c = page.canvas; header() }
                c.drawText("Theme ${gi + 1}  ·  ${list.size} notes", M, y + 10f, Look.text(11f, Look.ACCENT, Look.medium))
                c.drawLine(M, y + 16f, W - M, y + 16f, Look.stroke(Look.LINE, 1f))
                y += 30f
                for (n in list) {
                    val text = (n.userText ?: n.ocrText).replace('\n', ' ').ifBlank { "(no text found)" }
                    val h = max(18f, Look.wrappedHeight(text, textW, bodyPaint, 3))
                    if (y + h > H - 40f) {
                        footer(c); doc.finishPage(page); page = newPage(); c = page.canvas; header()
                        c.drawText("Theme ${gi + 1}, continued", M, y + 10f, Look.text(11f, Look.ACCENT, Look.medium))
                        y += 26f
                    }
                    Look.swatch(c, n.userColour ?: n.colour, M, y, 18f)
                    Look.wrapped(c, text, textX, y + 1f, textW, bodyPaint, 3)
                    y += h + 8f
                }
                y += 10f
            }
            footer(c); doc.finishPage(page)
        }

        // 3. Detail pages: the wall at a readable scale, with a mini-map, a scale bar and an invisible text layer.
        val m = reader.meta
        val areaW = W - 2 * M
        val areaH = H - 52f - 36f
        val winWmm = 900.0
        val winHmm = winWmm * areaH / areaW
        var level = 0
        while (winWmm * m.pxPerMm / (1 shl level) > 2800 && level < m.levels - 1) level++
        val z = 1 shl level
        val pxW = (winWmm * m.pxPerMm / z).toInt(); val pxH = (winHmm * m.pxPerMm / z).toInt()
        val lw = m.levelWidth(level); val lh = m.levelHeight(level)
        val cols = ceil(lw / pxW.toDouble()).toInt(); val rows = ceil(lh / pxH.toDouble()).toInt()
        val textPaint = Look.text(6f).apply { alpha = 2 }
        var detailNo = 0
        val detailTotal = cols * rows
        for (r in 0 until rows) for (col in 0 until cols) {
            val x0 = col * pxW; val y0 = r * pxH
            val w = min(pxW, lw - x0); val h = min(pxH, lh - y0)
            if (w <= 0 || h <= 0) continue
            detailNo++
            val bmp = reader.region(level, x0, y0, w, h)
            val page = newPage(); val c = page.canvas
            c.drawColor(Look.PAPER)
            c.drawText(s.title, M, 30f, Look.text(10f, Look.INK, Look.medium))
            c.drawText("Detail $detailNo of $detailTotal", M, 43f, Look.text(8.5f, Look.MUTED))
            // Mini-map of where this page sits on the wall.
            val mmW = 120f; val mmH = mmW * m.heightPx / m.widthPx
            val mm = RectF(W - M - mmW, 14f, W - M, 14f + min(mmH, 34f))
            val mmScale = min(mm.width() / overview.width, mm.height() / overview.height)
            val mmDst = RectF(mm.right - overview.width * mmScale, mm.top, mm.right, mm.top + overview.height * mmScale)
            Look.roundedBitmap(c, overview, mmDst, 3f)
            val fx = mmDst.width() / lw; val fy = mmDst.height() / lh
            c.drawRect(mmDst.left + x0 * fx, mmDst.top + y0 * fy, mmDst.left + (x0 + w) * fx, mmDst.top + (y0 + h) * fy, Look.stroke(Look.ACCENT, 1.4f))

            val k = areaW / pxW // points per level pixel
            val dst = RectF(M, 52f, M + w * k, 52f + h * k)
            Look.roundedBitmap(c, bmp, dst, 6f)
            val ptPerMm = (areaW / winWmm).toFloat() // points per millimetre of wall, the same on every page
            Look.scaleBar(c, dst.left + 8f, dst.bottom - 8f, ptPerMm, winWmm / 3.0, 8f)

            // Invisible text so the PDF is searchable and selectable.
            for (n in notes) {
                val text = n.userText ?: n.ocrText
                if (text.isBlank()) continue
                val nx = n.x * m.pxPerMm / z - x0; val ny = (lh - (n.y + n.h) * m.pxPerMm / z) - y0
                if (nx < 0 || ny < 0 || nx > w || ny > h) continue
                var line = 0
                for (t in text.lines()) { c.drawText(t, dst.left + (nx * k).toFloat(), dst.top + (ny * k).toFloat() + 7f * (++line), textPaint) }
            }
            footer(c)
            doc.finishPage(page)
            bmp.recycle()
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        overview.recycle()
    }
}

// ---- Share picture ------------------------------------------------------------------------------------------------

/** One image of the whole wall with a title, a scale bar and a colour key. Made for email and chat. */
internal object ShareCard {
    fun write(out: File, s: SessionEntity, notes: List<NoteEntity>, reader: TileReader, brand: String) {
        val width = 2400
        val pad = 110f
        val overview = overviewBitmap(reader, 2200)
        val imgW = width - 2 * pad
        val imgH = min(imgW * overview.height / overview.width, 1500f)
        val sc = min(imgW / overview.width, imgH / overview.height)
        val dw = overview.width * sc; val dh = overview.height * sc

        val counts = notes.groupingBy { it.userColour ?: it.colour }.eachCount().entries.sortedByDescending { it.value }
        val titleP = Look.text(78f, Look.INK, Look.bold)
        val subP = Look.text(40f, Look.MUTED)
        val titleH = Look.wrappedHeight(s.title, imgW, titleP, 2)
        val headerH = pad + titleH + 24f + 54f + 56f
        val footerH = 56f + 90f + 56f + 60f
        val height = (headerH + dh + footerH).toInt()

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Look.PAPER)
        c.drawRoundRect(RectF(pad, pad - 40f, pad + 90f, pad - 28f), 6f, 6f, Look.fill(Look.ACCENT))
        Look.wrapped(c, s.title, pad, pad, imgW, titleP, 2)
        c.drawText("${dateText(s)}   ·   ${sizeText(s)}   ·   ${notes.size} notes", pad, pad + titleH + 24f + 40f, subP)

        val dst = RectF(pad, headerH, pad + dw, headerH + dh)
        Look.roundedBitmap(c, overview, dst, 28f)
        Look.scaleBar(c, dst.left + 28f, dst.bottom - 28f, (dw / s.wallWidthMm).toFloat(), s.wallWidthMm / 3.0, 34f)

        // Colour key: swatch, name and count.
        var x = pad
        val y = dst.bottom + 56f
        for ((name, n) in counts) {
            val label = "${NoteColours.label(name)}  $n"
            val tp = Look.text(34f, Look.INK)
            val need = 64f + 16f + tp.measureText(label) + 40f
            if (x + need > width - pad) break
            Look.swatch(c, name, x, y, 64f)
            c.drawText(label, x + 80f, y + 46f, tp)
            x += need
        }
        c.drawText("Made with $brand", pad, height - 44f, Look.text(30f, Look.MUTED))
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle(); overview.recycle()
    }
}
