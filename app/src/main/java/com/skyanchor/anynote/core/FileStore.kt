package com.skyanchor.anynote.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.skyanchor.anynote.data.entity.AttachmentType
import java.io.File

/** 导入到应用私有目录后的附件。 */
data class StoredFile(
    val path: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
)

/**
 * 附件落盘：MVP 只保存图片与文件，统一复制进应用私有目录，
 * 这样原始文件被删除或移动后备忘录仍能打开它（基线 §13）。
 */
object FileStore {

    fun attachmentsDir(context: Context): File =
        File(context.applicationContext.filesDir, "attachments").apply { mkdirs() }

    fun import(context: Context, uri: Uri, type: AttachmentType): StoredFile? {
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val target = File(attachmentsDir(context), "${System.currentTimeMillis()}-$name")
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            StoredFile(
                path = target.absolutePath,
                fileName = name,
                mimeType = resolver.getType(uri) ?: defaultMime(type),
                size = target.length(),
            )
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "附件"
        val cursor = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull() ?: return uri.lastPathSegment ?: "附件"
        return cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else (uri.lastPathSegment ?: "附件")
        }
    }

    /** 只解码到展示尺寸，避免大图占满内存。 */
    fun decodeImage(path: String, maxSide: Int = 900): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull() ?: return null
        return bitmap.asImageBitmap()
    }

    fun fileExists(path: String): Boolean = File(path).exists()

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "${bytes / 1_048_576}.${(bytes % 1_048_576) * 10 / 1_048_576} MB"
        bytes >= 1_024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun defaultMime(type: AttachmentType): String = when (type) {
        AttachmentType.IMAGE -> "image/jpeg"
        AttachmentType.AUDIO -> "audio/mp4"
        AttachmentType.FILE -> "application/octet-stream"
    }
}
