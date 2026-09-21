package com.skyanchor.anynote.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.skyanchor.anynote.core.FileStore
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.db.Schema
import com.skyanchor.anynote.reminder.ReminderScheduler
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 恢复策略：覆盖 = 先清空再按备份还原；合并 = 保留现状，只补备份中不存在（按主键 ID 判断）的数据。 */
enum class RestoreMode { REPLACE, MERGE }

/** 备份文件里的清单信息，供恢复前向用户展示确认。 */
data class BackupInfo(
    val createdAt: Long,
    val appVersion: String,
    val notes: Int,
    val folders: Int,
    val rules: Int,
    val occurrences: Int,
    val attachments: Int,
    val attachmentBytes: Long,
)

data class BackupResult(val entries: Int, val bytes: Long)

data class RestoreResult(val notes: Int, val attachments: Int)

/** 当前数据规模，供「数据管理」页展示。 */
data class DataSummary(
    val notes: Int,
    val trashed: Int,
    val rules: Int,
    val occurrences: Int,
    val attachments: Int,
    val attachmentBytes: Long,
)

/**
 * 数据管理（本地备份 / 本地恢复 / 清空数据）。
 *
 * 备份格式是一个 .zip：`manifest.json`（清单）+ `database/anynote.db`（整库快照，打包前
 * 先做 WAL checkpoint 保证单文件自洽）+ `attachments/`（全部附件原文件）。
 * 恢复不直接覆盖运行中的库文件——OpenHelper 持有连接，改走"逐表行级复制"：
 * 附件的 `local_path` 按文件名重写到本机私有目录，换设备后依然有效。
 */
class DataBackupManager(
    context: Context,
    private val db: AnyNoteDatabase,
    private val scheduler: ReminderScheduler,
) {
    private val appContext = context.applicationContext

    // region summary & clear

    fun summary(): DataSummary = db.readableDatabase.let { live ->
        DataSummary(
            notes = live.countWhere(Schema.NOTES, "status != 'trashed'"),
            trashed = live.countWhere(Schema.NOTES, "status = 'trashed'"),
            rules = live.countWhere(Schema.RULES, null),
            occurrences = live.countWhere(Schema.OCCURRENCES, null),
            attachments = live.countWhere(Schema.ATTACHMENTS, null),
            attachmentBytes = FileStore.attachmentsDir(appContext)
                .listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L,
        )
    }

    /**
     * 清空数据：撤销全部系统闹钟，删除备忘录（含回收站）、规则、事件、附件记录与附件文件、
     * 诊断记录。分类与设置刻意保留——清空的是"记了什么"，不是"怎么记"。
     */
    fun clearAllData() {
        scheduler.cancelAll()
        val live = db.writableDatabase
        live.beginTransaction()
        try {
            DELETE_ORDER.forEach { live.delete(it, null, null) }
            live.setTransactionSuccessful()
        } finally {
            live.endTransaction()
        }
        FileStore.attachmentsDir(appContext).listFiles()?.forEach { runCatching { it.delete() } }
    }

    // endregion

    // region backup

    /** 把整库快照 + 附件目录打包写入用户经 SAF 选定的 .zip。 */
    fun backupTo(target: Uri): BackupResult {
        val writable = db.writableDatabase
        // TRUNCATE 检查点把 WAL 回写进主库文件，之后单拷 anynote.db 即自洽
        writable.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val dbFile = appContext.getDatabasePath(Schema.DATABASE_NAME)
        require(dbFile.exists()) { "数据库文件尚未生成，请先打开过一次应用" }
        val attachmentFiles = FileStore.attachmentsDir(appContext)
            .listFiles()?.filter { it.isFile } ?: emptyList()
        var entries = 0
        var bytes = 0L
        openOutputStream(target).use { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                val manifest = JSONObject()
                    .put("format", FORMAT_VERSION)
                    .put("createdAt", System.currentTimeMillis())
                    .put("appVersion", packageVersionName())
                    .put("dbVersion", Schema.DATABASE_VERSION)
                    .put("counts", JSONObject().apply {
                        put("notes", writable.countWhere(Schema.NOTES, null))
                        put("folders", writable.countWhere(Schema.FOLDERS, null))
                        put("rules", writable.countWhere(Schema.RULES, null))
                        put("occurrences", writable.countWhere(Schema.OCCURRENCES, null))
                        put("attachments", attachmentFiles.size)
                    })
                    .put("attachmentBytes", attachmentFiles.sumOf { it.length() })
                    .toString().toByteArray()
                zip.write(manifest)
                zip.closeEntry()
                entries++
                bytes += manifest.size
                entries += putFile(zip, DB_ENTRY, dbFile) { bytes += it }
                attachmentFiles.forEach { file ->
                    entries += putFile(zip, "$ATTACHMENTS_PREFIX${file.name}", file) { bytes += it }
                }
            }
        }
        return BackupResult(entries, bytes)
    }

    // endregion

    // region restore

    /** 只读备份里的 manifest，供恢复前展示确认信息。 */
    fun readBackupInfo(source: Uri): BackupInfo {
        val json = openInputStream(source).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                var found: String? = null
                while (entry != null && found == null) {
                    if (entry.name == MANIFEST_ENTRY) found = zip.readBytes().decodeToString()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                found ?: throw IllegalArgumentException("这不是有效的随记备份文件")
            }
        }
        return parseManifest(JSONObject(json))
    }

    /**
     * 从 .zip 恢复。全程在单个事务里做行级复制，任何一步失败都会整体回滚，
     * 不会留下"半份数据"。恢复完成后调用方负责 resyncAll 重新注册闹钟。
     */
    fun restoreFrom(source: Uri, mode: RestoreMode): RestoreResult {
        val workDir = File(appContext.cacheDir, "restore-${System.currentTimeMillis()}")
        val attachSrcDir = File(workDir, "files")
        try {
            var manifestText: String? = null
            openInputStream(source).use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == MANIFEST_ENTRY -> manifestText = zip.readBytes().decodeToString()
                            entry.name == DB_ENTRY -> extractTo(zip, File(workDir, "anynote.db"))
                            entry.name.startsWith(ATTACHMENTS_PREFIX) && !entry.isDirectory -> {
                                val name = File(entry.name.removePrefix(ATTACHMENTS_PREFIX)).name
                                if (name.isNotEmpty()) extractTo(zip, File(attachSrcDir, name))
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            val manifest = manifestText?.let(::JSONObject)
                ?: throw IllegalArgumentException("这不是有效的随记备份文件")
            require(manifest.optInt("format") == FORMAT_VERSION) { "不支持的备份格式版本" }

            // 读写打开：WAL 模式的库只读打开会因缺少 -shm/-wal 而失败；
            // 解出的副本在 cacheDir，允许它自建辅助文件，随工作目录一起删除。
            val backup = SQLiteDatabase.openDatabase(
                File(workDir, "anynote.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            backup.use {
                TABLE_ORDER.forEach { table ->
                    require(it.hasTable(table)) { "备份数据库缺少表 $table，文件可能已损坏" }
                }
                return restoreTables(backup, attachSrcDir, mode)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun restoreTables(
        backup: SQLiteDatabase,
        attachSrcDir: File,
        mode: RestoreMode,
    ): RestoreResult {
        scheduler.cancelAll()
        val live = db.writableDatabase
        val conflict = if (mode == RestoreMode.REPLACE) {
            SQLiteDatabase.CONFLICT_REPLACE
        } else {
            SQLiteDatabase.CONFLICT_IGNORE
        }
        var restoredNotes = 0
        var restoredAttachments = 0
        live.beginTransaction()
        try {
            if (mode == RestoreMode.REPLACE) {
                ALL_TABLES_REVERSE.forEach { live.delete(it, null, null) }
            }
            TABLE_ORDER.forEach { table ->
                when (table) {
                    Schema.META -> copyMeta(backup, live, mode)
                    Schema.ATTACHMENTS -> restoredAttachments =
                        copyAttachments(backup, live, attachSrcDir)
                    Schema.NOTES -> restoredNotes = copyRows(backup, live, table, conflict)
                    else -> copyRows(backup, live, table, conflict)
                }
            }
            live.setTransactionSuccessful()
        } finally {
            live.endTransaction()
        }
        // 覆盖模式下附件目录里属于"上一代数据"的文件已无人引用，统一清掉避免无限堆积；
        // 合并模式绝不能清，否则会删掉当前数据正在使用的附件。
        if (mode == RestoreMode.REPLACE) {
            val keep = live.rawQuery("SELECT local_path FROM ${Schema.ATTACHMENTS}", null)
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            FileStore.attachmentsDir(appContext).listFiles()?.forEach { file ->
                if (file.absolutePath !in keep) runCatching { file.delete() }
            }
        }
        return RestoreResult(restoredNotes, restoredAttachments)
    }

    /** 通用行级复制：按列类型原样搬运，返回实际写入的行数。 */
    private fun copyRows(
        backup: SQLiteDatabase,
        live: SQLiteDatabase,
        table: String,
        conflict: Int,
    ): Int {
        var inserted = 0
        backup.rawQuery("SELECT * FROM $table", null).use { c ->
            while (c.moveToNext()) {
                if (live.insertWithOnConflict(table, null, c.toValues(), conflict) != -1L) inserted++
            }
        }
        return inserted
    }

    /**
     * 附件恢复：`local_path` 是旧机器的绝对路径，必须按文件名重写到本机私有目录。
     * 合并模式下只有"这一行确实新插入"时才复制文件，重复附件直接跳过。
     */
    private fun copyAttachments(
        backup: SQLiteDatabase,
        live: SQLiteDatabase,
        attachSrcDir: File,
    ): Int {
        val dir = FileStore.attachmentsDir(appContext)
        var inserted = 0
        backup.rawQuery("SELECT * FROM ${Schema.ATTACHMENTS}", null).use { c ->
            while (c.moveToNext()) {
                val values = c.toValues()
                val oldPath = values.getAsString("local_path") ?: continue
                if (live.insertWithOnConflict(Schema.ATTACHMENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    continue // 合并时已存在同 ID 附件，连同行一起跳过
                }
                val name = File(oldPath).name
                val src = File(attachSrcDir, name)
                val dest = File(dir, name)
                if (src.exists()) {
                    if (!dest.exists()) runCatching { src.copyTo(dest) }
                    values.put("local_path", dest.absolutePath)
                    values.put("size", dest.length())
                }
                live.update(Schema.ATTACHMENTS, values, "id = ?", arrayOf(values.getAsString("id")))
                inserted++
            }
        }
        return inserted
    }

    /** meta 表：普通键合并时保留本机已有值；notification_seq 取两边最大值，避免闹钟槽位回退撞车。 */
    private fun copyMeta(backup: SQLiteDatabase, live: SQLiteDatabase, mode: RestoreMode) {
        val conflict = if (mode == RestoreMode.REPLACE) {
            SQLiteDatabase.CONFLICT_REPLACE
        } else {
            SQLiteDatabase.CONFLICT_IGNORE
        }
        backup.rawQuery("SELECT key, value FROM ${Schema.META}", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val value = c.getString(1)
                if (key == KEY_NOTIFICATION_SEQ && mode == RestoreMode.MERGE) {
                    val current = live.countMetaInt(key)
                    val incoming = value.toLongOrNull() ?: 0L
                    val merged = ContentValues().apply {
                        put("key", key)
                        put("value", maxOf(current, incoming).toString())
                    }
                    live.insertWithOnConflict(Schema.META, null, merged, SQLiteDatabase.CONFLICT_REPLACE)
                } else {
                    val values = ContentValues().apply {
                        put("key", key)
                        put("value", value)
                    }
                    live.insertWithOnConflict(Schema.META, null, values, conflict)
                }
            }
        }
    }

    // endregion

    // region zip helpers

    private fun putFile(zip: ZipOutputStream, name: String, file: File, onBytes: (Long) -> Unit): Int {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        onBytes(file.length())
        return 1
    }

    private fun extractTo(zip: ZipInputStream, target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> zip.copyTo(out) }
    }

    private fun openOutputStream(target: Uri): OutputStream =
        appContext.contentResolver.openOutputStream(target, "w")
            ?: throw IllegalStateException("无法写入所选位置")

    private fun openInputStream(source: Uri): InputStream =
        appContext.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("无法读取所选文件")

    private fun packageVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun parseManifest(m: JSONObject): BackupInfo {
        require(m.optInt("format") == FORMAT_VERSION) { "不支持的备份格式版本" }
        val counts = m.optJSONObject("counts") ?: JSONObject()
        return BackupInfo(
            createdAt = m.optLong("createdAt"),
            appVersion = m.optString("appVersion"),
            notes = counts.optInt("notes"),
            folders = counts.optInt("folders"),
            rules = counts.optInt("rules"),
            occurrences = counts.optInt("occurrences"),
            attachments = counts.optInt("attachments"),
            attachmentBytes = m.optLong("attachmentBytes"),
        )
    }

    // endregion

    private fun SQLiteDatabase.countWhere(table: String, where: String?): Int = rawQuery(
        if (where == null) "SELECT COUNT(*) FROM $table" else "SELECT COUNT(*) FROM $table WHERE $where",
        null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun SQLiteDatabase.hasTable(table: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)
    ).use { it.moveToFirst() }

    private fun SQLiteDatabase.countMetaInt(key: String): Long = rawQuery(
        "SELECT value FROM ${Schema.META} WHERE key = ?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }

    /** Cursor 行 → ContentValues：保持原列类型，NULL 列直接省略（落库即默认 null）。 */
    private fun Cursor.toValues(): ContentValues {
        val values = ContentValues()
        for (i in 0 until columnCount) {
            when (getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> values.put(getColumnName(i), getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> values.put(getColumnName(i), getDouble(i))
                Cursor.FIELD_TYPE_STRING -> values.put(getColumnName(i), getString(i))
                else -> Unit
            }
        }
        return values
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val DEFAULT_EXTENSION = ".zip"

        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DB_ENTRY = "database/anynote.db"
        private const val ATTACHMENTS_PREFIX = "attachments/"
        private const val KEY_NOTIFICATION_SEQ = "notification_seq"

        /** 恢复时的插入顺序：先父表后子表，覆盖模式下即使声明外键也安全。 */
        private val TABLE_ORDER = listOf(
            Schema.FOLDERS,
            Schema.NOTES,
            Schema.ATTACHMENTS,
            Schema.RULES,
            Schema.OCCURRENCES,
            Schema.META,
            Schema.HEALTH,
        )

        /** 清空时的删除顺序：先子表后父表。 */
        private val DELETE_ORDER = listOf(
            Schema.OCCURRENCES,
            Schema.RULES,
            Schema.ATTACHMENTS,
            Schema.NOTES,
            Schema.HEALTH,
        )

        private val ALL_TABLES_REVERSE = TABLE_ORDER.reversed()
    }
}
