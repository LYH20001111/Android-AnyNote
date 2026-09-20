package com.skyanchor.anynote.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.skyanchor.anynote.data.entity.Folder
import java.util.UUID

object DefaultFolders {
    const val WORK = "folder_work"
    const val LIFE = "folder_life"
    const val FAMILY = "folder_family"
    const val STUDY = "folder_study"
    const val OTHER = "folder_other"

    val ALL = listOf(
        Triple(WORK, "工作", "work"),
        Triple(LIFE, "生活", "life"),
        Triple(FAMILY, "家庭", "family"),
        Triple(STUDY, "学习", "study"),
        Triple(OTHER, "其他", "other"),
    )
}

/**
 * OpenHelper（基线 §22–§24）。数据库是提醒的唯一事实来源，因此结构变更必须走升级阶梯：
 * 已装机的老库若只等 `onCreate` 就永远拿不到逾期标记与调度诊断表，
 * 升级用户的"到点没响"会重新变成无从查证的主观抱怨。
 */
class AnyNoteDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, Schema.DATABASE_NAME, null, Schema.DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.CREATE.forEach { db.execSQL(it) }
        seedFolders(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
    }

    /**
     * v1 → v2：逾期补发需要一个"已确认逾期"的标记位，外加一张跨重启留痕的调度诊断表。
     * 老库可能已带同名列/表（版本回退过），所以逐条容错而不是整体事务。
     */
    private fun migrateToV2(db: SQLiteDatabase) {
        runCatching { db.execSQL("ALTER TABLE reminder_occurrences ADD COLUMN overdue_at INTEGER") }
        runCatching { db.execSQL(Schema.HEALTH_TABLE) }
        runCatching { db.execSQL(Schema.HEALTH_INDEX) }
        runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_occ_overdue ON reminder_occurrences(status, overdue_at)") }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("PRAGMA user_version = $newVersion")
    }

    private fun seedFolders(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            DefaultFolders.ALL.forEachIndexed { index, (id, name, icon) ->
                db.execSQL(
                    "INSERT INTO folders (id,name,color_key,icon_key,is_builtin,sort_order,created_at,updated_at)" +
                        " VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(
                        id, name, icon, icon, 1, index, now, now,
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** 通知 ID 单调递增，保证每条提醒事件占用的系统通知槽位唯一且可复用。 */
    @Synchronized
    fun nextNotificationSeq(): Int {
        val writable = writableDatabase
        val current = writable.rawQuery("SELECT value FROM meta WHERE key = 'notification_seq'", null)
            .use { if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: 0 else 0 }
        val next = (current + 1).coerceAtLeast(1)
        val values = android.content.ContentValues().apply {
            put("key", "notification_seq")
            put("value", next.toString())
        }
        writable.insertWithOnConflict("meta", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        return next
    }
}

fun Cursor.stringOrNull(name: String): String? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getString(index)
}

fun Cursor.longOrNull(name: String): Long? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getLong(index)
}

fun Cursor.intOrNull(name: String): Int? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getInt(index)
}

fun Cursor.boolean(name: String): Boolean = getInt(getColumnIndexOrThrow(name)) != 0
