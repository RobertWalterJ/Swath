package app.wallcapture.replay

import app.wallcapture.core.export.Exporter
import app.wallcapture.core.export.Json
import app.wallcapture.core.export.asDouble
import app.wallcapture.core.export.asList
import app.wallcapture.core.export.asMap
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayTest {
    @Test
    fun generatedSessionReplaysEndToEnd() {
        val dir = Files.createTempDirectory("swath-replay").toFile()
        try {
            generate(dir, mapOf("width-mm" to "4000", "height-mm" to "1000", "drift-mm-per-m" to "10", "seed" to "3"))
            val stills = File(dir, "stills").listFiles()!!.count { it.extension == "jpg" }
            assertTrue(stills >= 5, "stills $stills")

            replay(dir, mapOf("px-per-mm" to "1"))

            val metrics = Json.parse(File(dir, "metrics.json").readText()).asMap()
            assertTrue(metrics.getValue("pairsUsed").asDouble() >= 4, "metrics $metrics")
            // 10 mm/m of injected drift over ~2.5 m of travel means corrections of roughly 25 mm.
            val corr = metrics.getValue("maxCorrectionMm").asDouble()
            assertTrue(corr in 8.0..50.0, "max correction $corr")
            assertTrue(metrics.getValue("pyramidLevels").asDouble() >= 2, "metrics $metrics")
            assertTrue(File(dir, "mosaic/full.png").length() > 10_000, "png size")
            assertTrue(File(dir, "tiles/0").listFiles()!!.isNotEmpty(), "tiles")

            val rows = Exporter.parseCsv(File(dir, "notes.csv").readBytes())
            assertEquals(metrics.getValue("notesFound").asDouble().toInt(), rows.size - 1)
            assertTrue(rows.size - 1 >= 4, "notes found ${rows.size - 1}")

            val export = Json.parse(File(dir, "session-export.json").readText()).asMap()
            assertEquals(rows.size - 1, export.getValue("notes").asList().size, "export notes")
        } finally {
            dir.deleteRecursively()
        }
    }
}
