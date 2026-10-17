package app.wallcapture.core.pipeline

import app.wallcapture.core.ProcessingConfig
import app.wallcapture.core.extraction.DetectedNote
import app.wallcapture.core.extraction.NoteBuilder
import app.wallcapture.core.extraction.NoteDetector
import app.wallcapture.core.extraction.TextReader
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.image.Raster
import app.wallcapture.core.model.BoxMm
import app.wallcapture.core.model.Note
import app.wallcapture.core.mosaic.Extent
import app.wallcapture.core.mosaic.MosaicRenderer
import app.wallcapture.core.mosaic.PyramidInfo
import app.wallcapture.core.mosaic.StillInput
import app.wallcapture.core.mosaic.TilePyramid
import app.wallcapture.core.mosaic.TileSink
import app.wallcapture.core.mosaic.WallOffset
import app.wallcapture.core.refinement.Refiner
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

data class PipelineResult(
    val renderer: MosaicRenderer,
    val offsets: Map<String, WallOffset>,
    val pairsUsed: Int,
    val pairsDropped: Int,
    val extent: Extent,
    val pyramid: PyramidInfo?,
    /** Notes with boxes in mm from the bottom-left of the captured extent. */
    val notes: List<Note>,
    /** Full-resolution, deskewed crops keyed by note id. */
    val crops: Map<String, Raster>,
)

/** Phase C: refine poses, build the mosaic, then find notes. Every step is reusable on its own. */
class Pipeline(private val cfg: ProcessingConfig = ProcessingConfig()) {
    fun run(
        frame: WallFrame,
        stills: List<StillInput>,
        refine: Boolean = true,
        reader: TextReader? = null,
        codec: ImageCodec? = null,
        tileSink: TileSink? = null,
        detectNotes: Boolean = true,
        progress: (String) -> Unit = {},
    ): PipelineResult {
        require(stills.isNotEmpty()) { "no stills to process" }
        progress("Refining poses")
        val refinement = if (refine && stills.size > 1) Refiner.refine(frame, stills, cfg) else null
        val offsets = refinement?.offsets ?: emptyMap()
        val renderer = MosaicRenderer(frame, stills, offsets, cfg)

        var pyramid: PyramidInfo? = null
        if (codec != null && tileSink != null) {
            progress("Writing tiles")
            pyramid = TilePyramid.write(renderer, cfg, codec, tileSink)
        }

        var notes = emptyList<Note>()
        var crops = emptyMap<String, Raster>()
        if (detectNotes) {
            progress("Finding notes")
            val (found, hiCrops) = findNotes(frame, stills, offsets, renderer)
            progress("Reading notes")
            notes = NoteBuilder.build(found.mapIndexed { i, d -> d.copy(crop = hiCrops[i]) }, reader, cfg)
            crops = notes.indices.associate { notes[it].id to hiCrops[it] }
        }
        return PipelineResult(
            renderer, offsets, refinement?.pairsUsed ?: 0, refinement?.pairsDropped ?: 0,
            renderer.extent, pyramid, notes, crops,
        )
    }

    /** Detects on a bounded-size render, then cuts each note from the full-resolution mosaic. */
    private fun findNotes(
        frame: WallFrame,
        stills: List<StillInput>,
        offsets: Map<String, WallOffset>,
        renderer: MosaicRenderer,
    ): Pair<List<DetectedNote>, List<Raster>> {
        val ext = renderer.extent
        val area = ext.width * ext.height
        val scale = min(min(cfg.noteDetectPxPerMm, renderer.pxPerMm), sqrt(cfg.noteDetectMaxPixels / area))
        val low = if (scale < renderer.pxPerMm) MosaicRenderer(frame, stills, offsets, cfg, scale, ext) else renderer
        val detected = NoteDetector(cfg).detect(low.renderAll().raster, low.pxPerMm)

        val s = renderer.pxPerMm
        val lowHeightMm = low.heightPx / low.pxPerMm
        val bottomWallY = ext.maxY - lowHeightMm // wall-frame y of the bottom edge of the detection render
        val shiftToExtent = bottomWallY - ext.minY // detection y is measured from that bottom edge
        val placed = ArrayList<DetectedNote>()
        val crops = ArrayList<Raster>()
        for (d in detected) {
            val b = d.boxMm
            val wallBox = BoxMm(ext.minX + b.x, bottomWallY + b.y, b.w, b.h, b.rotationDeg)
            val r = hypot(b.w, b.h) / 2 + 3.0
            val x0 = floor((wallBox.centreX - r - ext.minX) * s).toInt()
            val y0 = floor((ext.maxY - (wallBox.centreY + r)) * s).toInt()
            val size = ceil(2 * r * s).toInt() + 2
            val region = renderer.render(x0, y0, size, size).raster
            crops.add(
                NoteDetector.deskewFrom(region, s, ext.minX + x0 / s, ext.maxY - y0 / s, wallBox),
            )
            placed.add(d.copy(boxMm = BoxMm(b.x, b.y + shiftToExtent, b.w, b.h, b.rotationDeg)))
        }
        return placed to crops
    }
}
