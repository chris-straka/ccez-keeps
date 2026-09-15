package dev.cstraka.keeps.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.apiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Room mirror of the frozen Note contract. Column names match the D1 table
 * (id, title, body, color, pinned, archived, updatedAt, deleted); booleans
 * are stored as 0/1 exactly like the server mapping in worker/notes.ts.
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",
    val color: String = "default",
    val pinned: Int = 0,
    val archived: Int = 0,
    val updatedAt: Long = 0L,
    val deleted: Int = 0,
    /** Label ids as a JSON array string (v3; dangling ids are inert). */
    val labelIds: String = "[]",
    /** Reminder fire time, unix epoch ms; null = none (v3). */
    val reminderAt: Long? = null,
    /** Repeat rule ("daily"/"weekly"); null = fires once (v4). */
    val repeat: String? = null,
)

fun NoteEntity.toNote(): Note = Note(
    id = id, title = title, body = body, color = color,
    pinned = pinned == 1, archived = archived == 1,
    updatedAt = updatedAt, deleted = deleted == 1,
    labelIds = try {
        apiJson.decodeFromString<List<String>>(labelIds)
    } catch (e: Exception) {
        emptyList()
    },
    reminderAt = reminderAt,
    repeat = repeat,
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id, title = title, body = body, color = color,
    pinned = if (pinned) 1 else 0, archived = if (archived) 1 else 0,
    updatedAt = updatedAt, deleted = if (deleted) 1 else 0,
    labelIds = apiJson.encodeToString(labelIds),
    reminderAt = reminderAt,
    repeat = repeat,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NoteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun dropLocal(id: String)
}

@Database(
    entities = [NoteEntity::class, DrawingEntity::class, LabelEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class KeepsDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun drawingDao(): DrawingDao
    abstract fun labelDao(): LabelDao
}
