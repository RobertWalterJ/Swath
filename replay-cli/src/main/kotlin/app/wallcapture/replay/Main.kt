package app.wallcapture.replay

import app.wallcapture.core.ProcessingConfig
import app.wallcapture.core.export.Exporter
import app.wallcapture.core.export.Json
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.model.SessionMeta
import app.wallcapture.core.pipeline.Pipeline
import app.wallcapture.core.session.SessionIO
import app.wallcapture.core.session.StillSidecar
import app.wallcapture.core.synthetic.Jitter
import app.wallcapture.core.synthetic.RenderOptions
import app.wallcapture.core.synthetic.SyntheticCamera
import app.wallcapture.core.synthetic.SyntheticWallFactory
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Usage:
  generate <outDir> [--width-mm 6000] [--height-mm 1500] [--drift-mm-per-m 4] [--seed 1]
      Render a synthetic wall walk (shake, tilt, drift, glare) into a recorded-session folder.
  replay <sessionDir> [--px-per-mm 1] [--no-refine]
      Run Phase C on a recorded session; writes mosaic/, tiles/, notes.csv, session-export.json and metrics.json.
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { println(USAGE.trim()); exitProcess(1) }
    val opts = parseOptions(args.drop(2))
    when (args[0]) {
        "generate" -> generate(File(args.getOrNull(1) ?: error("outDir required")), opts)
        "replay" -> replay(File(args.getOrNull(1) ?: error("sessionDir required")), opts)
        else -> { println(USAGE.trim()); exitProcess(1) }
    }
}

private fun parseOptions(a: List<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < a.size) {
        val k = a[i].removePrefix("--")
        if (i + 1 < a.size && !a[i + 1].startsWith("--")) { m[k] = a[i + 1]; i += 2 } else { m[k] = "true"; i++ }
    }
    return m
}

fun generate(out: File, o: Map<String, String>) {
    val width = o["width-mm"]?.toDouble() ?: 6000.0
    val height = o["height-mm"]?.toDouble() ?: 1500.0
    val drift = o["drift-mm-per-m"]?.toDouble() ?: 4.0
    val seed = o["seed"]?.toLong() ?: 1L
    val codec = JvmImageCodec()
    val wall = SyntheticWallFactory.create(width, height, pxPerMm = 0.5, noteCount = (width * height / 60000).toInt().coerceAtLeast(7), seed = seed)
    val frame = SyntheticCamera.testFrame()
    val k = SyntheticCamera.intrinsics()
    val dist = 1.2
    val footprint = dist * 1000.0 * k.width / k.fx
    val step = footprint * 0.4
    val xs = generateSequence(footprint / 2) { it + step }.takeWhile { it <= width - footprint / 2 + step * 0.5 }.map { it.coerceAtMost(width - footprint / 2) }.toList()
    val truth = SyntheticCamera.walk(frame, xs, height / 2, dist, Jitter(0.03, 2.0, 2.0, 5.0, 8.0), seed)
    val measured = SyntheticCamera.drifted(frame, truth, drift)
    SessionIO.writeSessionHeader(out, "Synthetic wall ${width.toInt()}x${height.toInt()} mm", frame, "synthetic")
    for ((i, p) in truth.withIndex()) {
        val glare = Triple(doubleArrayOf(0.3 + 0.4 * (i % 3) / 2.0, 0.4), 40.0, if (i % 4 == 0) 120f else 0f)
        val img = SyntheticCamera.render(wall, frame, p, k, RenderOptions(noiseSigma = 2f, glare = glare), seed + i)
        val h = PlaneHomography(frame, measured[i], k)
        SessionIO.writeStill(
            out,
            StillSidecar("s%03d".format(i), i * 1000L, k, measured[i], app.wallcapture.core.image.Sharpness.score(img), h.angleDeg, h.distanceM, "synthetic"),
            codec.encodeJpeg(img, 95),
        )
    }
    println("Wrote ${truth.size} stills and ${wall.notes.size} ground-truth notes to ${out.absolutePath}")
}

fun replay(dir: File, o: Map<String, String>) {
    val codec = JvmImageCodec()
    val session = SessionIO.read(dir, codec)
    val cfg = ProcessingConfig(pxPerMm = o["px-per-mm"]?.toDouble() ?: 1.0)
    val tiles = File(dir, "tiles")
    val started = System.nanoTime()
    val result = Pipeline(cfg).run(
        session.frame, session.stills, refine = o["no-refine"] == null, codec = codec,
        tileSink = { level, x, y, bytes -> File(tiles, "$level").apply { mkdirs() }.resolve("${x}_$y.jpg").writeBytes(bytes) },
        progress = { println(it) },
    )
    val mosaicDir = File(dir, "mosaic").apply { mkdirs() }
    val mosaic = result.renderer.renderAll().raster
    File(mosaicDir, "full.png").writeBytes(codec.encodePng(mosaic))
    File(mosaicDir, "preview.jpg").writeBytes(codec.encodeJpeg(mosaic, 85))
    val notesDir = File(dir, "notes").apply { mkdirs() }
    for ((id, crop) in result.crops) File(notesDir, "$id.jpg").writeBytes(codec.encodeJpeg(crop, 92))
    File(dir, "notes.csv").writeBytes(Exporter.notesCsv(result.notes))
    val meta = SessionMeta(dir.name, session.title, "", wallWidthMm = result.extent.width, wallHeightMm = result.extent.height, pxPerMm = cfg.pxPerMm, status = "ready")
    File(dir, "session-export.json").writeText(Exporter.sessionJson(meta, result.notes, emptyList()), Charsets.UTF_8)
    val maxShift = result.offsets.values.maxOfOrNull { kotlin.math.hypot(it.dx, it.dy) } ?: 0.0
    val metrics = mapOf(
        "stills" to session.stills.size,
        "pairsUsed" to result.pairsUsed, "pairsDropped" to result.pairsDropped,
        "maxCorrectionMm" to maxShift,
        "wallWidthMm" to result.extent.width, "wallHeightMm" to result.extent.height,
        "mosaicPx" to listOf(result.renderer.widthPx, result.renderer.heightPx),
        "pyramidLevels" to result.pyramid?.levels,
        "notesFound" to result.notes.size,
        "seconds" to (System.nanoTime() - started) / 1e9,
        "device" to session.device,
    )
    File(dir, "metrics.json").writeText(Json.write(metrics), Charsets.UTF_8)
    println(Json.write(metrics))
}
