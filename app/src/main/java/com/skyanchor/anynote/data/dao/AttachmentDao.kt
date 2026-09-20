package com.skyanchor.anynote.data.dao

import android.content.ContentValues
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.db.Schema
import com.skyanchor.anynote.data.entity.Attachment
import com.skyanchor.anynote.data.entity.AttachmentType

class AttachmentDao(private val db: AnyNoteDatabase) {

    fun add(noteId: String, type: AttachmentType, localPath: String, fileName: String, mimeType: String, size: Long): Attachment {
        val attachment = Attachment(
            id = db.newId(),
            noteId = noteId,
            type = type,
            localPath = localPath,
            fileName = fileName,
            mimeType = mimeType,
            size = size,
            createdAt = System.currentTimeMillis(),
        )
        db.writableDatabase.insert(Schema.ATTACHMENTS, null, attachment.toValues())
        return attachment
    }

    fun listOfNote(noteId: String): List<Attachment> = db.readableDatabase.rawQuery(
        "SELECT * FROM attachments WHERE note_id = ? ORDER BY created_at ASC", arrayOf(noteId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toAttachment()) } }

    fun get(id: String): Attachment? = db.readableDatabase.rawQuery(
        "SELECT * FROM attachments WHERE id = ?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.toAttachment() else null }

    fun delete(id: String) {
        db.writableDatabase.delete(Schema.ATTACHMENTS, "id = ?", arrayOf(id))
    }

    fun countOfNote(noteId: String): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM attachments WHERE note_id = ?", arrayOf(noteId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}

private fun Attachment.toValues() = ContentValues().apply {
    put("id", id)
    put("note_id", noteId)
    put("type", type.storage)
    put("local_path", localPath)
    put("file_name", fileName)
    put("mime_type", mimeType)
    put("size", size)
    put("created_at", createdAt)
}

private fun android.database.Cursor.toAttachment() = Attachment(
    id = getString(getColumnIndexOrThrow("id")),
    noteId = getString(getColumnIndexOrThrow("note_id")),
    type = AttachmentType.from(getString(getColumnIndexOrThrow("type"))),
    localPath = getString(getColumnIndexOrThrow("local_path")),
    fileName = getString(getColumnIndexOrThrow("file_name")),
    mimeType = getString(getColumnIndexOrThrow("mime_type")) ?: "",
    size = getLong(getColumnIndexOrThrow("size")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
)
