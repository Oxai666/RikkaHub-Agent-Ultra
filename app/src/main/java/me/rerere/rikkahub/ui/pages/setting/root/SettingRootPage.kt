package me.rerere.rikkahub.ui.pages.setting.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.root.RootShell
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * Root 权限状态页。
 *
 * 只做三件事：探测 su 是否可用、显示 su 路径、跑一条测试命令。
 * 具体的开关在「助手 → 本地工具 → Root 权限」，这里不控制谁在用。
 */
@Composable
fun SettingRootPage() {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var probing by remember { mutableStateOf(true) }
    var probe by remember { mutableStateOf<RootShell.Probe?>(null) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testRunning by remember { mutableStateOf(false) }
    // 防重入：进入页面时 LaunchedEffect 和 ON_RESUME 会各触发一次，
    // 两个探测并发跑完各写一次 probe，界面就闪两下。
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

    // 从外部回来（比如刚在 KernelSU 里授权）时自动重测。
    // 首次 ON_RESUME 跳过：那时 LaunchedEffect 已经在探了。
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(
                title = { Text(stringResource(R.string.setting_root_section_status)) },
            ) {
                item(
                    headlineContent = {
                        Text(
                            when {
                                probing -> stringResource(R.string.setting_root_probing)
                                probe?.available == true -> stringResource(R.string.setting_root_status_available)
                                else -> stringResource(R.string.setting_root_status_unavailable)
                            }
                        )
                    },
                    supportingContent = {
                        val detail = probe?.detail
                        Text(
                            when {
                                probing -> "…"
                                detail.isNullOrBlank() -> stringResource(R.string.setting_root_no_detail)
                                else -> detail
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_root_su_path)) },
                    supportingContent = {
                        Text(probe?.suPath ?: stringResource(R.string.setting_root_su_not_found))
                    },
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_root_tool_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedButton(
                onClick = { refresh() },
                enabled = !probing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setting_root_recheck))
            }

            Button(
                onClick = {
                    scope.launch {
                        testRunning = true
                        testOutput = withContext(Dispatchers.IO) {
                            val result = RootShell.exec("id; echo ---; getprop ro.product.model")
                            buildString {
                                append("exit_code=").append(result.exitCode)
                                if (result.timedOut) append("  (timeout)")
                                append('\n')
                                if (result.stdout.isNotBlank()) append(result.stdout.trim()).append('\n')
                                if (result.stderr.isNotBlank()) append("stderr: ").append(result.stderr.trim())
                            }
                        }
                        testRunning = false
                    }
                },
                enabled = !testRunning && probe?.available == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (testRunning) stringResource(R.string.setting_root_testing)
                    else stringResource(R.string.setting_root_test)
                )
            }

            testOutput?.let { output ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
