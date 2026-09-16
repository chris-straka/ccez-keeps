package dev.cstraka.keeps.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.applyLabelDelete
import dev.cstraka.keeps.sync.applyLabelRename
import dev.cstraka.keeps.sync.isLabel
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the labels amendment (contracts/data.md). Labels sync on
 * their own lane with an independent cursor; notes reference them by id
 * with no cascade (dangling ids are inert).
 */
@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey val id: String,
    val name: String = "",
    val color: String = "default",
    val updatedAt: Long = 0L,
    val deleted: Int = 0,
)

fun LabelEntity.toLabel(): Label = Label(
    id = id, name = name, color = color,
    updatedAt = updatedAt, deleted = deleted == 1,
)

fun Label.toEntity(): LabelEntity = LabelEntity(
    id = id, name = name, color = color,
    updatedAt = updatedAt, deleted = if (deleted) 1 else 0,
)

@Dao
interface LabelDao {
    @Query("SELECT * FROM labels")
    fun observeAll(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels")
    suspend fun all(): List<LabelEntity>

    @Query("SELECT * FROM labels WHERE id = :id")
    suspend fun byId(id: String): LabelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LabelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LabelEntity>)

    @Query("DELETE FROM labels WHERE id = :id")
    suspend fun dropLocal(id: String)
}

/**
 * Drawer long-press mutations. Both write Room first (instant UI via Flow)
 * with a fresh updatedAt so the row sits above the labels push-mark and
 * converges over the existing `/api/labels` lane — no new endpoints.
 */

/** Rename a label; null when the id is unknown or the name is blank. */
suspend fun LocalStore.renameLabel(id: String, name: String): Label? {
    val current = labelById(id) ?: return null
    val updated = applyLabelRename(current, name, System.currentTimeMillis()) ?: return null
    if (!isLabel(updated)) return null
    putLabel(updated)
    return updated
}

/** Soft-delete a label; notes holding the id keep it inert (no cascade). */
suspend fun LocalStore.deleteLabel(id: String) {
    val current = labelById(id) ?: return
    putLabel(applyLabelDelete(current, System.currentTimeMillis()))
}

/**
 * v3 -> v4: note repeat column. Existing rows default to fires-once.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `repeat` TEXT")
    }
}

/**
 * v2 -> v3: labels table plus the note label/reminder columns. Notes keep
 * their rows; new columns default to no labels and no reminder.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `labels` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`color` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `labelIds` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `reminderAt` INTEGER")
    }
}
