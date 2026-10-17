package app.wallcapture.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.wallcapture.core.model.Note
import app.wallcapture.core.model.BoxMm
import app.wallcapture.storage.AppDatabase
import app.wallcapture.storage.NoteEntity
import app.wallcapture.storage.SearchHit
import app.wallcapture.storage.SessionEntity
import app.wallcapture.storage.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Library : Screen
    data class Capture(val sessionId: String) : Screen
    data class Processing(val sessionId: String) : Screen
    data class Viewer(val sessionId: String, val focusNoteId: String? = null) : Screen
    data object Search : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val dao = db.sessions()
    val store = SessionStore(app)
    private val processor = SessionProcessor(app, dao, store)
    private val prefs = app.getSharedPreferences("wall", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Library)
    val progress = mutableStateMapOf<String, String>()
    var showHowTo by mutableStateOf(false)

    val sessions = dao.observeSessions().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val deleted = dao.observeDeleted().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    var pxPerMm: Double
        get() = prefs.getFloat("pxPerMm", 4f).toDouble()
        set(v) = prefs.edit().putFloat("pxPerMm", v.toFloat()).apply()

    var seenOnboarding: Boolean
        get() = prefs.getBoolean("onboarding", false)
        set(v) = prefs.edit().putBoolean("onboarding", v).apply()

    init {
        // Purge sessions that have sat in Recently Deleted for 30 days.
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - SessionStore.RECENTLY_DELETED_DAYS * 86_400_000L
            for (s in dao.expired(cutoff)) { store.delete(s.id); dao.deleteRow(s.id) }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val id = store.newId()
            val now = System.currentTimeMillis()
            val title = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(now)
            dao.upsert(SessionEntity(id, title, now, folderPath = store.dir(id).path))
            screen = Screen.Capture(id)
        }
    }

    /** Called when the user taps Done: capture is over, processing starts. */
    fun finishCapture(id: String) {
        screen = Screen.Processing(id)
        process(id)
    }

    suspend fun noteCount(id: String): Int = withContext(Dispatchers.IO) { dao.noteCount(id) }

    fun process(id: String) {
        viewModelScope.launch {
            processor.process(id, pxPerMm) { msg -> if (msg.isEmpty()) progress.remove(id) else progress[id] = msg }
        }
    }

    fun rename(id: String, title: String) = viewModelScope.launch { dao.rename(id, title) }
    fun delete(id: String) = viewModelScope.launch { dao.setDeleted(id, System.currentTimeMillis()) }
    fun restore(id: String) = viewModelScope.launch { dao.setDeleted(id, null) }

    fun freeUp(id: String) = viewModelScope.launch(Dispatchers.IO) { store.freeUpSpace(id) }

    fun duplicate(id: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = dao.get(id) ?: return@launch
        val newId = store.newId()
        store.dir(id).copyRecursively(store.dir(newId), overwrite = true)
        dao.upsert(s.copy(id = newId, title = s.title + " copy", createdAt = System.currentTimeMillis(), folderPath = store.dir(newId).path, deletedAt = null))
        dao.upsertNotes(dao.notes(id).map { it.copy(uid = "$newId/${it.noteId}", sessionId = newId) })
    }

    fun sizeOf(id: String): Long = store.sizeBytes(id)

    fun tileReader(id: String): TileReader? {
        val dir = store.tiles(id)
        val meta = PyramidMeta.read(dir) ?: return null
        return TileReader(dir, meta)
    }

    fun editNote(n: NoteEntity, text: String?, colour: String?) = viewModelScope.launch {
        dao.editNote(n.uid, text?.takeIf { it != n.ocrText }, colour?.takeIf { it != n.colour })
    }

    suspend fun search(q: String, colour: String?, sinceDays: Int?): List<SearchHit> = withContext(Dispatchers.IO) {
        val since = if (sinceDays == null) 0L else System.currentTimeMillis() - sinceDays * 86_400_000L
        dao.searchNotes(q.trim(), colour, since)
    }

    fun cropFile(sessionId: String, n: NoteEntity): File? = n.cropFileName?.let { File(store.notes(sessionId), it) }?.takeIf { it.exists() }

    fun toNote(n: NoteEntity) = Note(
        n.noteId, BoxMm(n.x, n.y, n.w, n.h, n.rotation), n.colour, n.clusterId, n.ocrText, n.ocrConfidence, n.userText, n.userColour,
    )
}
