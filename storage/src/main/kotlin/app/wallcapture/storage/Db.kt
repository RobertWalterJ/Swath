package app.wallcapture.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val location: String? = null,
    val tags: String = "",
    val wallWidthMm: Double = 0.0,
    val wallHeightMm: Double = 0.0,
    val pxPerMm: Double = 4.0,
    /** capturing, processing, ready, failed */
    val status: String = "capturing",
    val folderPath: String,
    val deletedAt: Long? = null,
    val progress: String = "",
)

@Entity(indices = [Index("sessionId")])
data class StillEntity(
    @PrimaryKey val uid: String,
    val sessionId: String,
    val fileName: String,
    val timestamp: Long,
    val intrinsicsJson: String,
    val poseRawJson: String,
    val poseRefinedJson: String? = null,
    val sharpness: Double,
    val angleDeg: Double,
    val distanceM: Double,
    val kept: Boolean = true,
)

@Entity(indices = [Index("sessionId")])
data class NoteEntity(
    @PrimaryKey val uid: String,
    val sessionId: String,
    val noteId: String,
    val x: Double, val y: Double, val w: Double, val h: Double, val rotation: Double,
    val colour: String,
    val clusterId: Int,
    val cropFileName: String?,
    val ocrText: String,
    val ocrConfidence: Double,
    val userText: String? = null,
    val userColour: String? = null,
)

@Entity(indices = [Index("sessionId")])
data class TextBlockEntity(
    @PrimaryKey val uid: String,
    val sessionId: String,
    val blockId: String,
    val x: Double, val y: Double, val w: Double, val h: Double,
    val ocrText: String,
    val confidence: Double,
    val noteId: String? = null,
    val userText: String? = null,
)

@Entity(indices = [Index("sessionId")])
data class ClusterEntity(
    @PrimaryKey val uid: String,
    val sessionId: String,
    val clusterId: Int,
    val label: String? = null,
    val colour: String? = null,
)

data class SearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val noteId: String,
    val text: String,
    val colour: String,
    val x: Double, val y: Double, val w: Double, val h: Double,
    val createdAt: Long,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM SessionEntity WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM SessionEntity WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM SessionEntity WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("UPDATE SessionEntity SET deletedAt = :at WHERE id = :id")
    suspend fun setDeleted(id: String, at: Long?)

    @Query("UPDATE SessionEntity SET status = :status, progress = :progress WHERE id = :id")
    suspend fun setStatus(id: String, status: String, progress: String)

    @Query("UPDATE SessionEntity SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("SELECT * FROM SessionEntity WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun expired(before: Long): List<SessionEntity>

    @Query("DELETE FROM SessionEntity WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStill(s: StillEntity)

    @Query("SELECT * FROM StillEntity WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun stills(sessionId: String): List<StillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(n: List<NoteEntity>)

    @Query("SELECT * FROM NoteEntity WHERE sessionId = :sessionId ORDER BY y DESC, x")
    fun observeNotes(sessionId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM NoteEntity WHERE sessionId = :sessionId ORDER BY y DESC, x")
    suspend fun notes(sessionId: String): List<NoteEntity>

    @Query("DELETE FROM NoteEntity WHERE sessionId = :sessionId")
    suspend fun clearNotes(sessionId: String)

    @Query("SELECT COUNT(*) FROM NoteEntity WHERE sessionId = :sessionId")
    suspend fun noteCount(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlocks(b: List<TextBlockEntity>)

    @Query("SELECT * FROM TextBlockEntity WHERE sessionId = :sessionId")
    suspend fun blocks(sessionId: String): List<TextBlockEntity>

    @Query("DELETE FROM TextBlockEntity WHERE sessionId = :sessionId")
    suspend fun clearBlocks(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClusters(c: List<ClusterEntity>)

    @Query("DELETE FROM ClusterEntity WHERE sessionId = :sessionId")
    suspend fun clearClusters(sessionId: String)

    @Query("UPDATE NoteEntity SET userText = :text, userColour = :colour WHERE uid = :uid")
    suspend fun editNote(uid: String, text: String?, colour: String?)

    @Query(
        """SELECT n.sessionId AS sessionId, s.title AS sessionTitle, n.noteId AS noteId,
                  COALESCE(n.userText, n.ocrText) AS text, COALESCE(n.userColour, n.colour) AS colour,
                  n.x AS x, n.y AS y, n.w AS w, n.h AS h, s.createdAt AS createdAt
           FROM NoteEntity n JOIN SessionEntity s ON s.id = n.sessionId
           WHERE s.deletedAt IS NULL AND COALESCE(n.userText, n.ocrText) LIKE '%' || :q || '%'
             AND (:colour IS NULL OR COALESCE(n.userColour, n.colour) = :colour)
             AND s.createdAt >= :since
           ORDER BY s.createdAt DESC LIMIT 200""",
    )
    suspend fun searchNotes(q: String, colour: String?, since: Long): List<SearchHit>
}

@Database(
    entities = [SessionEntity::class, StillEntity::class, NoteEntity::class, TextBlockEntity::class, ClusterEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wall.db")
                .build().also { instance = it }
        }
    }
}
