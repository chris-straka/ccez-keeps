package dev.cstraka.keeps.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.DrawingPoint
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.apiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Room mirror of the drawings contract amendment (contracts/data.md).
 * Strokes ride as a JSON blob in one column — drawings are small, always
 * read whole, and never queried by stroke content, so a normalized table
 * would buy nothing.
 */
@Entity(tableName = "drawings")
data class DrawingEntity(
    @PrimaryKey val id: String,
    val strokes: String = "[]",
    val updatedAt: Long = 0L,
    val deleted: Int = 0,
)

fun DrawingEntity.toDrawing(): Drawing = Drawing(
    id = id,
    strokes = try {
        apiJson.decodeFromString<List<DrawingStroke>>(strokes)
    } catch (e: Exception) {
        emptyList()
    },
    updatedAt = updatedAt,
    deleted = deleted == 1,
)

fun Drawing.toEntity(): DrawingEntity = DrawingEntity(
    id = id,
    strokes = apiJson.encodeToString(strokes),
    updatedAt = updatedAt,
    deleted = if (deleted) 1 else 0,
)

@Dao
interface DrawingDao {
    @Query("SELECT * FROM drawings")
    fun observeAll(): Flow<List<DrawingEntity>>

    @Query("SELECT * FROM drawings")
    suspend fun all(): List<DrawingEntity>

    @Query("SELECT * FROM drawings WHERE id = :id")
    suspend fun byId(id: String): DrawingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DrawingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DrawingEntity>)

    @Query("DELETE FROM drawings WHERE id = :id")
    suspend fun dropLocal(id: String)
}

/** v1 -> v2: drawings table. Notes are untouched; no data migration. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drawings` (" +
                "`id` TEXT NOT NULL, `strokes` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}
