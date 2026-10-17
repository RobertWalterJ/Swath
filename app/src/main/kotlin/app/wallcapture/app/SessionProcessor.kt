package app.wallcapture.app

import android.content.Context
import app.wallcapture.core.ProcessingConfig
import app.wallcapture.core.export.Exporter
import app.wallcapture.core.model.SessionMeta
import app.wallcapture.core.pipeline.Pipeline
import app.wallcapture.core.session.SessionIO
import app.wallcapture.ocr.MlKitTextReader
import app.wallcapture.storage.ClusterEntity
import app.wallcapture.storage.NoteEntity
import app.wallcapture.storage.SessionDao
import app.wallcapture.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Runs Phase C for one session on a background thread and stores the results. */
class SessionProcessor(private val context: Context, private val dao: SessionDao, private val store: SessionStore) {
    suspend fun process(id: String, pxPerMm: Double, progress: (String) -> Unit) = withContext(Dispatchers.Default) {
        val session = dao.get(id) ?: return@withContext
        dao.setStatus(id, "processing", "")
        val codec = AndroidImageCodec()
        val reader = MlKitTextReader()
        try {
            progress("Reading stills")
            val dir = store.dir(id)
            val rec = SessionIO.read(dir, codec)
            val cfg = ProcessingConfig(pxPerMm = pxPerMm)
            val tiles = store.tiles(id)
            tiles.deleteRecursively(); tiles.mkdirs()
            val result = Pipeline(cfg).run(
                frame = rec.frame, stills = rec.stills, reader = reader, codec = codec,
                tileSink = { level, x, y, bytes ->
                    File(tiles, "$level").apply { mkdirs() }.resolve("${x}_$y.jpg").writeBytes(bytes)
                },
                progress = progress,
            )
            val pyr = result.pyramid ?: error("no tiles were written")
            val meta = PyramidMeta(pyr.levels, pyr.widthPx, pyr.heightPx, pyr.tilePx, cfg.pxPerMm)
            PyramidMeta.write(tiles, meta)

            // The top pyramid level is a single tile: use it as the preview and the thumbnail.
            val mosaicDir = store.mosaic(id)
            File(tiles, "${pyr.levels - 1}/0_0.jpg").takeIf { it.exists() }?.copyTo(File(mosaicDir, "preview.jpg"), overwrite = true)

            val notesDir = store.notes(id)
            for ((noteId, crop) in result.crops) File(notesDir, "$noteId.jpg").writeBytes(codec.encodeJpeg(crop, 92))

            dao.clearNotes(id); dao.clearClusters(id)
            dao.upsertNotes(
                result.notes.map {
                    NoteEntity(
                        "$id/${it.id}", id, it.id, it.boxMm.x, it.boxMm.y, it.boxMm.w, it.boxMm.h, it.boxMm.rotationDeg,
                        it.colour, it.clusterId, "${it.id}.jpg", it.ocrText, it.ocrConfidence,
                    )
                },
            )
            dao.upsertClusters(result.notes.map { it.clusterId }.distinct().map { ClusterEntity("$id/$it", id, it) })

            val updated = session.copy(
                wallWidthMm = result.extent.width, wallHeightMm = result.extent.height, pxPerMm = cfg.pxPerMm, status = "ready",
            )
            dao.upsert(updated)
            File(dir, "session-export.json").writeText(
                Exporter.sessionJson(
                    SessionMeta(id, session.title, session.createdAt.toString(), session.location, emptyList(), result.extent.width, result.extent.height, cfg.pxPerMm, "ready"),
                    result.notes, emptyList(),
                ),
            )
            progress("")
        } catch (t: Throwable) {
            dao.setStatus(id, "failed", t.message ?: t.javaClass.simpleName)
            progress("")
        } finally {
            reader.close()
        }
    }
}
