package me.rerere.rikkahub.ui.pages.setting.vscreen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.vscreen.VirtualScreenManager
import me.rerere.rikkahub.data.vscreen.VirtualScreenOverlay
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 虚拟屏幕。
 *
 * 这块屏是**额外挂出来的一台显示器**，不是当前屏幕的镜像。
 * 想在它上面跑东西，得用 root_shell 把 Activity 投到对应 display id 上。
 */
@Composable
fun SettingVirtualScreenPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val state by VirtualScreenManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var widthText by rememberSaveable { mutableStateOf("720") }
    var heightText by rememberSaveable { mutableStateOf("1280") }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var packageInput by rememberSaveable { mutableStateOf("") }
    var launchMessage by remember { mutableStateOf<String?>(null) }
    var overlayShowing by remember { mutableStateOf(VirtualScreenOverlay.isShowing()) }
    var overlayHint by remember { mutableStateOf<String?>(null) }

    // 进页面时对一次状态：App 进程可能被回收过，helper 却还在
    LaunchedEffect(Unit) {
        if (!VirtualScreenManager.state.value.running) {
            VirtualScreenManager.restore(context)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(
                title = { Text(stringResource(R.string.setting_virtual_screen_section_status)) },
            ) {
                item(
                    headlineContent = {
                        Text(
                            when {
                                state.starting -> stringResource(R.string.setting_virtual_screen_starting)
                                state.running -> stringResource(R.string.setting_virtual_screen_running)
                                else -> stringResource(R.string.setting_virtual_screen_stopped)
                            }
                        )
                    },
                    supportingContent = {
                        val detail = when {
                            state.running ->
                                "display ${state.displayId} · 127.0.0.1:${state.port} · ${state.width}×${state.height}" +
                                    if (state.mirror) " · 镜像" else ""
                            state.error != null -> state.error!!
                            else -> stringResource(R.string.setting_virtual_screen_idle_hint)
                        }
                        Text(detail)
                    },
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_virtual_screen_explain),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!state.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_virtual_screen_mirror),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setting_virtual_screen_mirror_desc),
                            style = MaterialTheme.typography.bodySmall,
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setting_virtual_screen_start))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                shot = VirtualScreenManager.screenshot()
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.setting_virtual_screen_capture))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                VirtualScreenManager.stop(context)
                                shot = null
                                overlayShowing = false
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.setting_virtual_screen_stop))
                    }
                }

                // 小窗：实时画面 + 自己在上面点
                Button(
                    onClick = { navController.navigate(Screen.VirtualScreenWindow) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.setting_virtual_screen_open_window))
                }

                // 悬浮小窗：浮在别的应用上面，边刷手机边看
                OutlinedButton(
                    onClick = {
                        if (!VirtualScreenOverlay.ensurePermission(context)) {
                            overlayHint = "已跳转到授权页，允许「显示在其他应用上层」后回来再点一次"
                        } else if (VirtualScreenOverlay.isShowing()) {
                            VirtualScreenOverlay.hide()
                            overlayShowing = false
                        } else {
                            VirtualScreenOverlay.show(context)
                            overlayShowing = VirtualScreenOverlay.isShowing()
                            overlayHint = if (overlayShowing) {
                                "悬浮窗已打开，顶部黑条可以拖动，点画面会转发到虚拟屏"
                            } else {
                                "打开失败"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (overlayShowing) R.string.setting_virtual_screen_close_overlay
                            else R.string.setting_virtual_screen_open_overlay
                        )
                    )
                }
                overlayHint?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }

                shot?.let { bitmap ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    }
                }

                // 投应用：独立屏刚开出来是空的，得往里投东西才看得见
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    ) {
                        Text(stringResource(R.string.setting_virtual_screen_launch))
                    }
                }
                launchMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
