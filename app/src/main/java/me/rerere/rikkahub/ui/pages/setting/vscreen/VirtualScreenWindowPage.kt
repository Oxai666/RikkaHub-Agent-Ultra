package me.rerere.rikkahub.ui.pages.setting.vscreen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vscreen.VirtualScreenManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 虚拟屏幕的小窗：实时画面 + 你自己点它。
 *
 * 画面走 [VirtualScreenManager.stream]（helper 每 66ms 推一帧 JPEG），
 * 触摸走 tap 命令（helper 里 InputManager.injectInputEvent + setDisplayId）。
 */
@Composable
fun VirtualScreenWindowPage() {
    val state by VirtualScreenManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var streamError by remember { mutableStateOf<String?>(null) }

    // 拉流：断开就隔一秒重连，直到页面销毁或虚拟屏停止
    LaunchedEffect(state.running, state.port) {
        if (!state.running) return@LaunchedEffect
        while (isActive) {
            runCatching {
                VirtualScreenManager.stream { bitmap ->
                    withContext(Dispatchers.Main) { frame = bitmap }
                }
            }.onFailure { streamError = it.message }
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_virtual_screen_window_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.running) {
                Text(stringResource(R.string.setting_virtual_screen_window_not_running))
                return@Column
            }

            val bitmap = frame
            val aspect = if (state.height > 0) {
                state.width.toFloat() / state.height.toFloat()
            } else {
                9f / 16f
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(state.width, state.height, viewSize) {
                        if (viewSize.width == 0 || state.width == 0) return@pointerInput
                        detectTapGestures { offset ->
                            // 视图坐标 → 虚拟屏像素
                            val x = offset.x / viewSize.width * state.width
                            val y = offset.y / viewSize.height * state.height
                            scope.launch { VirtualScreenManager.tap(x, y) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            streamError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                text = "display ${state.displayId} · ${state.width}×${state.height}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
