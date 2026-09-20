package com.skyanchor.anynote.data.dao

import android.content.ContentValues
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.db.DefaultFolders
import com.skyanchor.anynote.data.db.Schema
import com.skyanchor.anynote.data.db.boolean
import com.skyanchor.anynote.data.entity.Folder

class FolderDao(private val db: AnyNoteDatabase) {

    fun list(): List<Folder> = db.readableDatabase.rawQuery(
        "SELECT * FROM folders ORDER BY sort_order ASC, created_at ASC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(c.toFolder())
        }
    }

    fun get(id: String): Folder? = db.readableDatabase.rawQuery(
        "SELECT * FROM folders WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toFolder() else null }

    fun create(name: String): Folder {
        val now = System.currentTimeMillis()
        val folder = Folder(
            id = db.newId(),
            name = name.trim(),
            colorKey = "custom",
            iconKey = "other",
            isBuiltIn = false,
            sortOrder = (list().maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = now,
            updatedAt = now,
        )
        db.writableDatabase.insert(Schema.FOLDERS, null, folder.toValues())
        return folder
    }

    fun rename(id: String, name: String) {
        db.writableDatabase.update(
            Schema.FOLDERS,
            ContentValues().apply {
                put("name", name.trim())
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(id)
        )
    }

    /** 基线 §17：删除分类不删除备忘录，把它们移到"其他"。 */
    fun delete(id: String) {
        if (id == DefaultFolders.OTHER) return
        val now = System.currentTimeMillis()
        db.writableDatabase.apply {
            beginTransaction()
            try {
                execSQL(
                    "UPDATE notes SET folder_id = ?, updated_at = ? WHERE folder_id = ?",
                    arrayOf<Any>(DefaultFolders.OTHER, now, id)
                )
                delete(Schema.FOLDERS, "id = ?", arrayOf(id))
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun noteCount(id: String): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM notes WHERE folder_id = ? AND status != 'trashed'", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}

internal fun android.database.Cursor.toFolder() = Folder(
    id = getString(getColumnIndexOrThrow("id")),
    name = getString(getColumnIndexOrThrow("name")),
    colorKey = getString(getColumnIndexOrThrow("color_key")),
    iconKey = getString(getColumnIndexOrThrow("icon_key")),
    isBuiltIn = boolean("is_builtin"),
    sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
)

internal fun Folder.toValues() = ContentValues().apply {
    put("id", id)
    put("name", name)
    put("color_key", colorKey)
    put("icon_key", iconKey)
    put("is_builtin", if (isBuiltIn) 1 else 0)
    put("sort_order", sortOrder)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
}
