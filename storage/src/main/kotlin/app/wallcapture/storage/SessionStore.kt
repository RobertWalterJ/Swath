package app.wallcapture.storage

import android.content.Context
import java.io.File
import java.util.UUID

/** Per-session folders under app storage: stills/, mosaic/, tiles/, notes/ and session.json. */
class SessionStore(context: Context) {
    val root: File = File(context.filesDir, "Sessions").apply { mkdirs() }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun dir(id: String): File = File(root, id).apply { mkdirs() }
    fun stills(id: String) = File(dir(id), "stills").apply { mkdirs() }
    fun mosaic(id: String) = File(dir(id), "mosaic").apply { mkdirs() }
    fun tiles(id: String) = File(dir(id), "tiles").apply { mkdirs() }
    fun notes(id: String) = File(dir(id), "notes").apply { mkdirs() }
    fun exports(id: String) = File(dir(id), "exports").apply { mkdirs() }

    fun sizeBytes(id: String): Long = dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Frees raw stills (keeps mosaic, tiles and notes). */
    fun freeUpSpace(id: String): Long {
        val before = sizeBytes(id)
        stills(id).deleteRecursively()
        return before - sizeBytes(id)
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    companion object {
        const val RECENTLY_DELETED_DAYS = 30
    }
}
