package me.rerere.rikkahub.ui.pages.setting.vscreen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.vscreen.VirtualScreenManager
import me.rerere.rikkahub.data.vscreen.VirtualScreenOverlay
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 虚拟屏幕控制台。
 *
 * 和普通设置页刻意做得不一样：这块的视觉主体是**屏幕本身**，
 * 所以画面预览放在最上面占一大块，参数和按钮围绕它排，
 * 而不是一堆「标题 + 副标题」的列表项。
 */
@Composable
fun SettingVirtualScreenPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val state by VirtualScreenManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var busy by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var widthText by rememberSaveable { mutableStateOf("720") }
    var heightText by rememberSaveable { mutableStateOf("1280") }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var packageInput by rememberSaveable { mutableStateOf("") }
    var launchMessage by remember { mutableStateOf<String?>(null) }
    var overlayShowing by remember { mutableStateOf(VirtualScreenOverlay.isShowing()) }
    var overlayHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!VirtualScreenManager.state.value.running) {
            VirtualScreenManager.restore(context)
        }
    }

    // 设置页也拉实时流：这样「看到画面」不用再点一次截图
    LaunchedEffect(state.running, state.port) {
        if (!state.running) {
            frame = null
            return@LaunchedEffect
        }
        while (isActive) {
            runCatching {
                VirtualScreenManager.stream { bitmap ->
                    withContext(Dispatchers.Main) { frame = bitmap }
                }
            }
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_virtual_screen_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── ① 屏幕本体 ────────────────────────────────────────────
            ScreenStage(
                frame = frame,
                state = state,
            )

            // ── ② 状态 + 参数 ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatusDot(state = state)
                state.error?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (!state.running) {
                // 未启动：尺寸 + 镜像 + 启动
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter { c -> c.isDigit() } },
                        label = { Text("W") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter { c -> c.isDigit() } },
                        label = { Text("H") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_virtual_screen_mirror),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setting_virtual_screen_mirror_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = mirror, onCheckedChange = { mirror = it })
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            VirtualScreenManager.start(
                                context = context,
                                width = widthText.toIntOrNull()?.coerceIn(240, 2160) ?: 720,
                                height = heightText.toIntOrNull()?.coerceIn(240, 2160) ?: 1280,
                                mirror = mirror,
                            )
                            busy = false
                        }
                    },
                    enabled = !busy && !state.starting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (state.starting) stringResource(R.string.setting_virtual_screen_starting)
                        else stringResource(R.string.setting_virtual_screen_start)
                    )
                }
            } else {
                // 已启动：操作条
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { navController.navigate(Screen.VirtualScreenWindow) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.setting_virtual_screen_window_short)) }

                    FilledTonalButton(
                        onClick = {
                            if (!VirtualScreenOverlay.ensurePermission(context)) {
                                overlayHint = "已跳转到授权页，允许「显示在其他应用上层」后回来再点一次"
                            } else if (VirtualScreenOverlay.isShowing()) {
                                VirtualScreenOverlay.hide()
                                overlayShowing = false
                            } else {
                                VirtualScreenOverlay.show(context)
                                overlayShowing = VirtualScreenOverlay.isShowing()
                                overlayHint = if (overlayShowing) "悬浮窗已打开：点顶部白条可拖动/关闭" else "打开失败"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.setting_virtual_screen_overlay_short)) }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                VirtualScreenManager.stop(context)
                                overlayShowing = false
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.setting_virtual_screen_stop)) }
                }

                overlayHint?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }

                // ── ③ 投送 ────────────────────────────────────────
                Text(
                    text = stringResource(R.string.setting_virtual_screen_launch_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = packageInput,
                        onValueChange = { packageInput = it.trim() },
                        label = { Text(stringResource(R.string.setting_virtual_screen_launch_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = VirtualScreenManager.launchApp(context, packageInput)
                                launchMessage = if (result.ok) "已投送：${result.message}" else result.message
                                busy = false
                            }
                        },
                        enabled = !busy && packageInput.isNotBlank() && !state.mirror,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.setting_virtual_screen_launch)) }
                }
                launchMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                text = stringResource(R.string.setting_virtual_screen_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 画面舞台：黑底 + 等比画面 + 右下角参数。 */
@Composable
private fun ScreenStage(
    frame: Bitmap?,
    state: VirtualScreenManager.State,
) {
    val aspect = if (state.height > 0) {
        state.width.toFloat() / state.height.toFloat()
    } else {
        9f / 16f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = frame
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.setting_virtual_screen_no_preview),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
            )
        }

        if (state.running) {
            Text(
                text = "display ${state.displayId} · ${state.width}×${state.height}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            )
        }
    }
}

/** 一个圆点 + 一句话，比列表项更能一眼看出状态。 */
@Composable
private fun StatusDot(state: VirtualScreenManager.State) {
    val (color, label) = when {
        state.starting -> MaterialTheme.colorScheme.tertiary to
            stringResource(R.string.setting_virtual_screen_starting)
        state.running -> Color(0xFF4CAF50) to
            stringResource(R.string.setting_virtual_screen_running)
        else -> MaterialTheme.colorScheme.outline to
            stringResource(R.string.setting_virtual_screen_stopped)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
