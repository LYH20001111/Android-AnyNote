package com.skyanchor.anynote.data.dao

import android.content.ContentValues
import android.database.Cursor
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.db.Schema
import com.skyanchor.anynote.data.db.boolean
import com.skyanchor.anynote.data.db.longOrNull
import com.skyanchor.anynote.data.db.stringOrNull
import com.skyanchor.anynote.data.entity.Note
import com.skyanchor.anynote.data.entity.NoteStatus
import com.skyanchor.anynote.data.entity.Priority

/** 备忘录查询条件（基线 §19 搜索与筛选）。 */
data class NoteQuery(
    val status: NoteStatus = NoteStatus.ACTIVE,
    val text: String? = null,
    val folderId: String? = null,
    val priority: Priority? = null,
)

class NoteDao(private val db: AnyNoteDatabase) {

    fun get(id: String): Note? = db.readableDatabase.rawQuery(
        "SELECT * FROM notes WHERE id = ?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.toNote() else null }

    fun insert(note: Note): Note {
        db.writableDatabase.insert(Schema.NOTES, null, note.toValues())
        return note
    }

    fun update(note: Note): Note {
        db.writableDatabase.update(Schema.NOTES, note.toValues(), "id = ?", arrayOf(note.id))
        return note
    }

    fun query(q: NoteQuery): List<Note> {
        val where = StringBuilder("status = ?")
        val args = ArrayList<String>().apply { add(q.status.storage) }
        q.folderId?.let { where.append(" AND folder_id = ?"); args += it }
        q.priority?.let { where.append(" AND priority = ?"); args += it.storage }
        q.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (title LIKE ? OR body LIKE ?)")
            val like = "%$it%"
            args += like
            args += like
        }
        return db.readableDatabase.rawQuery(
            "SELECT * FROM notes WHERE $where ORDER BY updated_at DESC", args.toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toNote()) } }
    }

    fun touch(id: String) {
        db.writableDatabase.update(
            Schema.NOTES,
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?", arrayOf(id)
        )
    }

    /**
     * 基线 §16：删除 = 进入回收站，数据不物理删除。
     * 恢复 = 回到 active 并由调用方重新调度提醒。
     */
    fun moveToTrash(id: String) {
        val now = System.currentTimeMillis()
        db.writableDatabase.update(
            Schema.NOTES,
            ContentValues().apply {
                put("status", NoteStatus.TRASHED.storage)
                put("deleted_at", now)
                put("updated_at", now)
            },
            "id = ?", arrayOf(id)
        )
    }

    fun restore(id: String) {
        val now = System.currentTimeMillis()
        db.writableDatabase.update(
            Schema.NOTES,
            ContentValues().apply {
                put("status", NoteStatus.ACTIVE.storage)
                putNull("deleted_at")
                put("updated_at", now)
            },
            "id = ?", arrayOf(id)
        )
    }

    fun markCompleted(id: String, completed: Boolean) {
        val now = System.currentTimeMillis()
        db.writableDatabase.update(
            Schema.NOTES,
            ContentValues().apply {
                put("status", if (completed) NoteStatus.COMPLETED.storage else NoteStatus.ACTIVE.storage)
                if (completed) put("completed_at", now) else putNull("completed_at")
                put("updated_at", now)
            },
            "id = ?", arrayOf(id)
        )
    }

    /** 基线 §16：永久删除。返回该备忘录下的附件路径，供调用方清理文件。 */
    fun purge(id: String): List<String> {
        val paths = db.readableDatabase.rawQuery(
            "SELECT local_path FROM attachments WHERE note_id = ?", arrayOf(id)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        db.writableDatabase.apply {
            beginTransaction()
            try {
                execSQL("DELETE FROM reminder_occurrences WHERE note_id = ?", arrayOf(id))
                execSQL("DELETE FROM reminder_rules WHERE note_id = ?", arrayOf(id))
                execSQL("DELETE FROM attachments WHERE note_id = ?", arrayOf(id))
                execSQL("DELETE FROM notes WHERE id = ?", arrayOf(id))
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        return paths
    }

    fun countByStatus(status: NoteStatus): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM notes WHERE status = ?", arrayOf(status.storage)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}

internal fun Cursor.toNote() = Note(
    id = getString(getColumnIndexOrThrow("id")),
    folderId = getString(getColumnIndexOrThrow("folder_id")),
    title = stringOrNull("title"),
    body = getString(getColumnIndexOrThrow("body")) ?: "",
    priority = Priority.from(stringOrNull("priority")),
    status = NoteStatus.from(stringOrNull("status")),
    notificationPreviewEnabled = boolean("notification_preview_enabled"),
    completionEnabled = boolean("completion_enabled"),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    completedAt = longOrNull("completed_at"),
    deletedAt = longOrNull("deleted_at"),
)

internal fun Note.toValues() = ContentValues().apply {
    put("id", id)
    put("folder_id", folderId)
    put("title", title)
    put("body", body)
    put("priority", priority.storage)
    put("status", status.storage)
    put("notification_preview_enabled", if (notificationPreviewEnabled) 1 else 0)
    put("completion_enabled", if (completionEnabled) 1 else 0)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    if (completedAt == null) putNull("completed_at") else put("completed_at", completedAt)
    if (deletedAt == null) putNull("deleted_at") else put("deleted_at", deletedAt)
}
