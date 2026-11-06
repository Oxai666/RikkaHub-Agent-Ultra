package me.rerere.rikkahub.ui.pages.setting.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.root.RootPreferences
import me.rerere.rikkahub.data.root.RootShell
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * Root 控制台。
 *
 * 刻意做成「终端」的样子而不是普通设置页：
 *  - 顶部是大号状态（圆点 + 结论），不是标题+副标题的列表项
 *  - su 路径用等宽字体，因为它是一个命令
 *  - 测试输出用黑底等宽，看起来就是一段终端回显
 */
@Composable
fun SettingRootPage() {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var probing by remember { mutableStateOf(true) }
    var probe by remember { mutableStateOf<RootShell.Probe?>(null) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testRunning by remember { mutableStateOf(false) }
    var suCommandInput by remember { mutableStateOf(RootPreferences.suCommand()) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            probing = true
            probe = withContext(Dispatchers.IO) { RootShell.probe() }
            probing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    var firstResumeSkipped by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResumeSkipped) refresh() else firstResumeSkipped = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_root_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 状态 ──────────────────────────────────────────────
            RootStatusBlock(probing = probing, probe = probe)

            // ── su 命令 ───────────────────────────────────────────
            Text(
                text = stringResource(R.string.setting_root_su_command),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = suCommandInput,
                onValueChange = { suCommandInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(RootPreferences.DEFAULT_SU_COMMAND) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                supportingText = {
                    Text(stringResource(R.string.setting_root_su_command_hint))
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        suCommandInput = RootPreferences.DEFAULT_SU_COMMAND
                        RootPreferences.setSuCommand(RootPreferences.DEFAULT_SU_COMMAND)
                        RootShell.invalidate()
                        refresh()
                    },
                    enabled = !probing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.setting_root_su_command_reset)) }

                Button(
                    onClick = {
                        RootPreferences.setSuCommand(suCommandInput)
                        RootShell.invalidate()
                        refresh()
                    },
                    enabled = !probing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.setting_root_su_command_save)) }
            }

            // ── 测试 ──────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { refresh() },
                    enabled = !probing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.setting_root_recheck)) }

                Button(
                    onClick = {
                        scope.launch {
                            testRunning = true
                            testOutput = withContext(Dispatchers.IO) {
                                val result = RootShell.exec("id; echo ---; getprop ro.product.model")
                                buildString {
                                    append("$ ")
                                    append("id; echo ---; getprop ro.product.model")
                                    append('\n')
                                    if (result.stdout.isNotBlank()) append(result.stdout.trim()).append('\n')
                                    if (result.stderr.isNotBlank()) append("stderr: ").append(result.stderr.trim()).append('\n')
                                    append("[exit ${result.exitCode}]")
                                    if (result.timedOut) append(" (timeout)")
                                }
                            }
                            testRunning = false
                        }
                    },
                    enabled = !testRunning && probe?.available == true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (testRunning) stringResource(R.string.setting_root_testing)
                        else stringResource(R.string.setting_root_test)
                    )
                }
            }

            // ── 终端风格输出 ──────────────────────────────────────
            testOutput?.let { output ->
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF7CFC8A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0B0F0B))
                        .padding(14.dp)
                        .heightIn(min = 90.dp),
                )
            }

            Text(
                text = stringResource(R.string.setting_root_tool_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 状态块：一个圆点 + 一句结论 + 等宽的命令路径。 */
@Composable
private fun RootStatusBlock(probing: Boolean, probe: RootShell.Probe?) {
    val available = probe?.available == true
    val (dotColor, headline) = when {
        probing -> MaterialTheme.colorScheme.tertiary to stringResource(R.string.setting_root_probing)
        available -> Color(0xFF4CAF50) to stringResource(R.string.setting_root_status_available)
        else -> MaterialTheme.colorScheme.error to stringResource(R.string.setting_root_status_unavailable)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        Text(
            text = probe?.suPath ?: stringResource(R.string.setting_root_su_not_found),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val detail = probe?.detail
        if (!probing && !detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
