package com.skyanchor.anynote.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.skyanchor.anynote.core.FileStore
import com.skyanchor.anynote.core.StoredFile
import com.skyanchor.anynote.data.entity.AttachmentType
import com.skyanchor.anynote.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 附件的统一展示模型：既覆盖已入库的附件，也覆盖编辑态里尚未落盘的本地文件。 */
data class AttachmentUi(
    val key: String,
    val type: AttachmentType,
    val path: String,
    val name: String,
    val size: Long,
    val mime: String,
    val persisted: Boolean,
)

/** 导入后的落盘文件 → 展示模型。 */
fun StoredFile.toUi(type: AttachmentType, persisted: Boolean = false): AttachmentUi =
    AttachmentUi(
        key = path,
        type = type,
        path = path,
        name = fileName,
        size = size,
        mime = mimeType,
        persisted = persisted,
    )

/**
 * 图片走系统相册选择器（无需权限），文件走 SAF 文档选择器；两者都立刻把内容
 * 复制进应用私有目录，因此后续原始文件被删掉也不影响备忘录（基线 §13）。
 */
@Composable
fun rememberAttachmentPickers(
    onPicked: (StoredFile, AttachmentType) -> Unit,
    onFailed: () -> Unit,
): (AttachmentType) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun copyIn(uri: Uri, type: AttachmentType) {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { FileStore.import(context, uri, type) }
            if (stored == null) onFailed() else onPicked(stored, type)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { copyIn(it, AttachmentType.IMAGE) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyIn(it, AttachmentType.FILE) }
    }
    return { type ->
        if (type == AttachmentType.IMAGE) {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            filePicker.launch(arrayOf("*/*"))
        }
    }
}

@Composable
fun AttachmentStrip(
    items: List<AttachmentUi>,
    onRemove: (AttachmentUi) -> Unit,
    onClick: (AttachmentUi) -> Unit,
    onAdd: (AttachmentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.key }) { item ->
            AttachmentTile(item, onRemove = { onRemove(item) }, onClick = { onClick(item) })
        }
        item(key = "add_image") {
            AddTile(AppIcons.Image, "图片") { onAdd(AttachmentType.IMAGE) }
        }
        item(key = "add_file") {
            AddTile(AppIcons.File, "文件") { onAdd(AttachmentType.FILE) }
        }
    }
}

@Composable
private fun AttachmentTile(
    item: AttachmentUi,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val bitmap = if (item.type == AttachmentType.IMAGE) {
        remember(item.path) { FileStore.decodeImage(item.path, 360) }
    } else {
        null
    }
    Box(Modifier.size(width = 96.dp, height = 108.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(AppColors.Glass)
                .border(1.dp, AppColors.GlassBorder, shape)
                .noRippleClickable(onClick),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(AppColors.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap,
                        item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        if (item.type == AttachmentType.AUDIO) AppIcons.Mic else AppIcons.File,
                        null,
                        tint = AppColors.Primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    FileStore.formatSize(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xCC16213C))
                .noRippleClickable(onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Close, "移除附件", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun AddTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .size(width = 96.dp, height = 108.dp)
            .clip(shape)
            .border(1.dp, AppColors.PrimarySoft, shape)
            .background(AppColors.Glass)
            .noRippleClickable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AppColors.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = AppColors.Primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
    }
}
