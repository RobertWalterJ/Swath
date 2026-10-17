package app.wallcapture.core

import app.wallcapture.core.export.Exporter
import app.wallcapture.core.export.Json
import app.wallcapture.core.export.asDouble
import app.wallcapture.core.export.asList
import app.wallcapture.core.export.asMap
import app.wallcapture.core.extraction.Clusterer
import app.wallcapture.core.extraction.NoteBuilder
import app.wallcapture.core.extraction.NoteDetector
import app.wallcapture.core.extraction.TextReader
import app.wallcapture.core.extraction.WindowedOcr
import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.image.Raster
import app.wallcapture.core.model.BoxMm
import app.wallcapture.core.model.Note
import app.wallcapture.core.model.SessionMeta
import app.wallcapture.core.model.TextLine
import app.wallcapture.core.synthetic.SyntheticWallFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteDetectionTest {
    @Test
    fun findsAtLeast95PercentOfSevenColourNotesOnKraft() {
        val wall = SyntheticWallFactory.create(3600.0, 1400.0, pxPerMm = 1.0, noteCount = 28, seed = 21)
        val detected = NoteDetector(ProcessingConfig()).detect(wall.image, 1.0)
        var found = 0
        val usedColours = HashMap<String, Int>()
        for (gt in wall.notes) {
            val m = detected.firstOrNull { hypot(it.boxMm.centreX - gt.box.centreX, it.boxMm.centreY - gt.box.centreY) < 25 }
            if (m != null) {
                found++
                if (m.colour == gt.colour) usedColours.merge(gt.colour, 1, Int::plus)
            }
        }
        assertTrue(found >= (wall.notes.size * 0.95).toInt() + 1 || found == wall.notes.size, "found $found of ${wall.notes.size}")
        assertEquals(found, usedColours.values.sum(), "colour names should match ground truth: $usedColours")
        assertTrue(detected.size <= wall.notes.size + 1, "false positives: ${detected.size} vs ${wall.notes.size}")
    }

    @Test
    fun detectedBoxesAreCloseToGroundTruthSize() {
        val wall = SyntheticWallFactory.create(2000.0, 900.0, pxPerMm = 2.0, noteCount = 7, seed = 4)
        val detected = NoteDetector(ProcessingConfig()).detect(wall.image, 2.0)
        assertTrue(detected.isNotEmpty())
        for (gt in wall.notes) {
            val m = detected.firstOrNull { hypot(it.boxMm.centreX - gt.box.centreX, it.boxMm.centreY - gt.box.centreY) < 15 } ?: continue
            assertTrue(kotlin.math.abs(maxOf(m.boxMm.w, m.boxMm.h) - gt.box.w) < 14, "size ${m.boxMm} vs ${gt.box}")
        }
    }

    @Test
    fun clusteringGroupsNearbyNotesAndLeavesLoneNotesAlone() {
        val boxes = listOf(
            BoxMm(0.0, 0.0, 80.0, 80.0), BoxMm(100.0, 0.0, 80.0, 80.0), BoxMm(50.0, 100.0, 80.0, 80.0),
            BoxMm(2000.0, 0.0, 80.0, 80.0),
        )
        val ids = Clusterer.cluster(boxes, 1.5)
        assertEquals(ids[0], ids[1]); assertEquals(ids[0], ids[2])
        assertTrue(ids[3] != ids[0])
    }

    @Test
    fun windowedOcrMergesDuplicatesFromOverlappingWindows() {
        val img = Raster(300, 100)
        // Windows start at x = 0 and x = 100. A word at absolute x = 150 sits at 150 in the first window
        // and at 50 in the second, so both windows report it.
        val relativeX = ArrayDeque(listOf(150.0, 50.0))
        val reader = TextReader { listOf(TextLine("hello", 0.9, relativeX.removeFirst(), 10.0, 40.0, 12.0)) }
        val lines = WindowedOcr.read(img, reader, 200, 0.5)
        assertEquals(1, lines.size)
        assertEquals(150.0, lines[0].x, 1e-9)
        val dup = WindowedOcr.dedupe(listOf(TextLine("a", 0.4, 10.0, 10.0, 50.0, 10.0), TextLine("a", 0.8, 12.0, 11.0, 50.0, 10.0)))
        assertEquals(1, dup.size)
        assertEquals(0.8, dup[0].confidence, 1e-9)
    }

    @Test
    fun noteBuilderOcrsEachCropAndOrdersLines() {
        val wall = SyntheticWallFactory.create(1200.0, 600.0, pxPerMm = 1.0, noteCount = 3, seed = 9)
        val det = NoteDetector(ProcessingConfig()).detect(wall.image, 1.0)
        val reader = TextReader { listOf(TextLine("b", 0.8, 0.0, 20.0, 5.0, 5.0), TextLine("a", 0.6, 0.0, 5.0, 5.0, 5.0)) }
        val notes = NoteBuilder.build(det, reader, ProcessingConfig())
        assertTrue(notes.isNotEmpty())
        assertEquals("a\nb", notes[0].ocrText)
        assertEquals(0.7, notes[0].ocrConfidence, 1e-9)
    }
}

class ExportTest {
    @Test
    fun csvRoundTripsCommasQuotesAccentsAndLineBreaks() {
        val tricky = listOf(
            "plain", "with, comma", "say \"hello\"", "café déjà vu — ünïcode", "line one\nline two", "crlf\r\ninside", "",
            "multi, \"all\"\nof it",
        )
        val notes = tricky.mapIndexed { i, t ->
            Note("n$i", BoxMm(10.0 * i, 5.0, 80.0, 80.0), "yellow", i % 2, ocrText = t, ocrConfidence = 0.5)
        }
        val bytes = Exporter.notesCsv(notes)
        assertEquals(0xEF.toByte(), bytes[0]) // UTF-8 BOM for Excel
        val rows = Exporter.parseCsv(bytes)
        assertEquals(Exporter.CSV_HEADER, rows[0])
        assertEquals(notes.size + 1, rows.size)
        for ((i, t) in tricky.withIndex()) {
            assertEquals(t.replace("\r\n", "\n").replace("\n", "\n"), rows[i + 1][1].replace("\r\n", "\n"), "row $i")
            assertEquals("n$i", rows[i + 1][0])
            assertEquals(8, rows[i + 1].size)
        }
    }

    @Test
    fun editedTextAndColourWinAndAreFlagged() {
        val n = Note("n1", BoxMm(0.0, 0.0, 80.0, 80.0), "pink", 0, ocrText = "teh", userText = "the", userColour = "green")
        val row = Exporter.parseCsv(Exporter.notesCsv(listOf(n)))[1]
        assertEquals("the", row[1]); assertEquals("green", row[2]); assertEquals("yes", row[7])
    }

    @Test
    fun spreadsheetFormulaStartsAreNeutralised() {
        val n = Note("n1", BoxMm(0.0, 0.0, 80.0, 80.0), "pink", 0, ocrText = "=SUM(A1:A9)")
        assertEquals("'=SUM(A1:A9)", Exporter.parseCsv(Exporter.notesCsv(listOf(n)))[1][1])
    }

    @Test
    fun jsonWritesAndParsesBack() {
        val notes = listOf(Note("n1", BoxMm(1.5, 2.5, 80.0, 70.0, 3.0), "blue", 2, ocrText = "tab\t\"q\"\nnl é"))
        val text = Exporter.sessionJson(SessionMeta("s1", "Title", "2026-10-16", wallWidthMm = 6000.0), notes, emptyList())
        val m = Json.parse(text).asMap()
        assertEquals("Title", m.getValue("session").asMap()["title"])
        val n0 = m.getValue("notes").asList()[0].asMap()
        assertEquals("tab\t\"q\"\nnl é", n0["text"])
        assertEquals(2.5, n0.getValue("box").asList()[1].asDouble(), 1e-9)
    }

    @Test
    fun noteZipContainsOneJpegPerNote() {
        val codec = object : ImageCodec {
            override fun encodeJpeg(r: Raster, quality: Int) = byteArrayOf(r.width.toByte(), r.height.toByte())
            override fun encodePng(r: Raster) = ByteArray(0)
            override fun decode(bytes: ByteArray) = Raster(1, 1)
        }
        val zip = Exporter.noteImagesZip(mapOf("n001" to Raster(4, 5), "n002" to Raster(6, 7)), codec)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { names.add((z.nextEntry ?: break).name) } }
        assertEquals(listOf("n001.jpg", "n002.jpg"), names)
    }
}
