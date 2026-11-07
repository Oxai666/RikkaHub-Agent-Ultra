package me.rerere.rikkahub.ui.pages.assistant.detail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.BACKGROUND_SCALE_CROP
import me.rerere.rikkahub.data.model.BACKGROUND_SCALE_FIT
import me.rerere.rikkahub.data.model.BACKGROUND_SCALE_STRETCH
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.pages.chat.isVideoBackground
import org.koin.compose.koinInject

@Composable
fun BackgroundPicker(
    modifier: Modifier = Modifier,
    background: String?,
    backgroundOpacity: Float = 1.0f,
    backgroundScale: String = BACKGROUND_SCALE_CROP,
    onUpdate: (String?) -> Unit,
    onScaleChange: (String) -> Unit = {},
) {
    val filesManager: FilesManager = koinInject()
    var showPickOption by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdate(localUri.toString())
            }
        }
    }

    val previewOpacity = backgroundOpacity.coerceIn(0f, 1f)
    val previewScale = when (backgroundScale) {
        BACKGROUND_SCALE_FIT -> ContentScale.Fit
        BACKGROUND_SCALE_STRETCH -> ContentScale.FillBounds
        else -> ContentScale.Crop
    }

    FormItem(
        modifier = modifier,
        label = {
            Text(stringResource(R.string.assistant_page_chat_background))
        },
        description = {
            Text(stringResource(R.string.assistant_page_chat_background_desc))
        }
    ) {
        Button(
            onClick = {
                showPickOption = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (background != null) {
                    stringResource(R.string.assistant_page_change_background)
                } else {
                    stringResource(R.string.assistant_page_select_background)
                }
            )
        }

        if (background != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_background_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        onUpdate(null)
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_remove))
                }
            }

            // 预览统一放在一个固定高度的卡片里，图片和视频共用同一个框，
            // 这样切换背景类型时版面不会跳。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.medium)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isVideoBackground(background)) {
                        VideoFramePreview(
                            url = background,
                            opacity = previewOpacity,
                            contentScale = previewScale,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            model = background,
                            contentDescription = null,
                            contentScale = previewScale,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(previewOpacity)
                        )
                    }
                }
            }
        }

        // 缩放方式：图片和视频都吃这一份设置。
        // 没设背景时也显示，用户可以先选好再挑文件。
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.assistant_page_background_scale),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    BACKGROUND_SCALE_CROP to R.string.assistant_page_background_scale_crop,
                    BACKGROUND_SCALE_FIT to R.string.assistant_page_background_scale_fit,
                    BACKGROUND_SCALE_STRETCH to R.string.assistant_page_background_scale_stretch,
                ).forEach { (value, labelRes) ->
                    FilterChip(
                        selected = backgroundScale == value,
                        onClick = { onScaleChange(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }
    }

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_select_background))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPickOption = false
                            mediaPickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_select_from_gallery))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            mediaPickerLauncher.launch("video/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_select_video_background))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_enter_image_url))
                    }
                    if (background != null) {
                        Button(
                            onClick = {
                                showPickOption = false
                                onUpdate(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.assistant_page_remove_background))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_enter_image_url))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.assistant_page_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate(urlInput.trim())
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUrlInput = false }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
}

/**
 * 视频背景的静帧预览。
 *
 * 设置页原来对视频只显示一行「已设置视频背景」——选了视频之后完全看不到画面，
 * 也没法判断缩放选得对不对。这里用 MediaMetadataRetriever 抽第一帧同步解码出来，
 * 不启动播放器（省电，也不会在设置页莫名其妙出声）。
 *
 * 网络地址和本地文件走两个不同的 setDataSource 重载：前者要传 headers，
 * 后者要传 Context + Uri，传错了会抛 IllegalArgumentException。
 */
@Composable
private fun VideoFramePreview(
    url: String,
    opacity: Float,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var frame by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        failed = false
        frame = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                        retriever.setDataSource(url, emptyMap<String, String>())
                    } else {
                        retriever.setDataSource(context, Uri.parse(url))
                    }
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
        }
        failed = frame == null
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bitmap = frame
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(opacity),
            )
        } else {
            Text(
                text = if (failed) {
                    stringResource(R.string.assistant_page_video_background)
                } else {
                    stringResource(R.string.assistant_page_loading_preview)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
