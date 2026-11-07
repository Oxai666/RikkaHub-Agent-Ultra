package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine

enum class SearchMode {
    OFF,
    LOCAL,
    BUILT_IN,
}

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    model: Model?,
) {
    var showSearchPicker by remember { mutableStateOf(false) }
    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch || model?.tools?.contains(BuiltInTools.Search) == true,
        onClick = {
            showSearchPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                val searchOn = enableSearch && currentService != null
                val modelTool = model?.tools?.contains(BuiltInTools.Search) == true

                when {
                    // 模型自带搜索：用 AI 搜索图标
                    modelTool -> Icon(
                        imageVector = HugeIcons.AiSearch02,
                        contentDescription = stringResource(R.string.use_web_search),
                    )

                    // 本地搜索开着：搜索方 logo 保持原尺寸，右上角另外挂一个
                    // 小放大镜，中间一道锥形光束打过去。
                    searchOn -> FocusOnServiceLogo(currentService!!.displayName)

                    // 没开：普通放大镜
                    else -> Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            var selectingProvider by remember { mutableStateOf(false) }
            AnimatedContent(
                targetState = selectingProvider,
                transitionSpec = {
                    if (targetState) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "SearchPickerPage"
            ) { selecting ->
                if (selecting) {
                    SearchProviderPicker(
                        settings = settings,
                        onUpdateSearchService = { index ->
                            onUpdateSearchService(index)
                            selectingProvider = false
                        },
                        onBack = { selectingProvider = false }
                    )
                } else {
                    SearchPicker(
                        enableSearch = enableSearch,
                        settings = settings,
                        onUpdateSearchMode = onUpdateSearchMode,
                        model = model,
                        onSelectProvider = { selectingProvider = true },
                        onDismiss = { showSearchPicker = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPicker(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onSelectProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val navBackStack = LocalNavController.current

    val provider = model?.findProvider(settings.providers)
    // Google 和使用 Responses API 的 OpenAI Provider 支持内置搜索
    val supportsBuiltInSearch = provider is ProviderSetting.Google ||
        provider is ProviderSetting.OpenAI && provider.useResponseApi
    // 模型是否已开启内置搜索（可能是不支持的模型残留的孤儿状态）
    val hasBuiltInSearchEnabled = model?.tools?.contains(BuiltInTools.Search) == true
    // 模型支持内置搜索，或已开启内置搜索（后者保证残留状态也能被关闭）时显示模型搜索卡片
    val showModelSearch = model != null && (supportsBuiltInSearch || hasBuiltInSearchEnabled)
    val isLocalSearchSelected = enableSearch && !hasBuiltInSearchEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    onDismiss()
                    navBackStack.navigate(Screen.SettingSearch)
                }
            ) {
                Icon(HugeIcons.Settings03, contentDescription = null)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SearchModeCard(
                title = stringResource(R.string.search_picker_local_title),
                description = stringResource(R.string.search_picker_local_description),
                icon = HugeIcons.GlobalSearch,
                selected = isLocalSearchSelected,
                onClick = {
                    if (isLocalSearchSelected) {
                        onUpdateSearchMode(SearchMode.OFF)
                    } else {
                        onUpdateSearchMode(SearchMode.LOCAL)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            if (showModelSearch) {
                SearchModeCard(
                    title = stringResource(R.string.search_picker_model_title),
                    description = stringResource(R.string.search_picker_model_description),
                    icon = HugeIcons.AiSearch02,
                    selected = hasBuiltInSearchEnabled,
                    onClick = {
                        onUpdateSearchMode(
                            if (hasBuiltInSearchEnabled) SearchMode.OFF else SearchMode.BUILT_IN
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        if (enableSearch || hasBuiltInSearchEnabled) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                if (isLocalSearchSelected) {
                    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)
                    TextButton(onClick = onSelectProvider) {
                        Text(
                            text = buildString {
                                append(stringResource(R.string.search_picker_select_provider))
                                currentService?.let { append(" · ${it.displayName}") }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp)
                        )
                    }
                }
                TextButton(
                    onClick = { onUpdateSearchMode(SearchMode.OFF) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.search_picker_turn_off),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    )
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SearchProviderPicker(
    settings: Settings,
    onUpdateSearchService: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.search_picker_select_provider),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            columns = GridCells.Adaptive(150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(settings.searchServices, key = { _, service -> service.id }) { index, service ->
                val containerColor = animateColorAsState(
                    if (settings.searchServiceSelected == index) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
                val textColor = animateColorAsState(
                    if (settings.searchServiceSelected == index) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = containerColor.value,
                        contentColor = textColor.value,
                    ),
                    onClick = {
                        onUpdateSearchService(index)
                    },
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AutoAIIcon(
                            name = service.displayName,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = service.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            SearchAbilityTagLine(
                                options = service,
                                modifier = Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * 搜索开着的时候，按钮里画的是这样子：
 *
 *        🔍        ← 右上角的小放大镜，轻微斜着
 *       ╱ ╲
 *      ╱   ╲       ← 锥形光束，越靠近 logo 越淡
 *     [LOGO]       ← 搜索方 logo，保持 24dp 原尺寸
 *
 * 外层格子只有 24dp，这里用 requiredSize 撑到 34dp 溢出去——
 * Compose 默认不裁子元素，画得比格子大不会出问题，也不会把 Row 撑高。
 */
@Composable
private fun FocusOnServiceLogo(serviceName: String) {
    val beamColor = MaterialTheme.colorScheme.primary

    val transition = rememberInfiniteTransition(label = "focus")

    // 光圈呼吸：0.35 亮到 0.85 再退回来
    val glowAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    // 闪光点：0 → 1 走一圈，边扩散边淡掉，然后重来
    val spark by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spark",
    )

    Box(
        modifier = Modifier.requiredSize(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 锥形光束：从放大镜底部扩散下来，落在 logo 身上。
        // 放大镜在左上，logo 在右下，所以整条光路是从左上斜向右下。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cone = Path().apply {
                moveTo(w * 0.38f, h * 0.29f)   // 放大镜右下
                lineTo(w * 0.18f, h * 0.38f)   // 放大镜左下
                lineTo(w * 0.24f, h * 0.76f)   // 锥底左
                lineTo(w * 0.88f, h * 0.59f)   // 锥底右
                close()
            }
            drawPath(
                path = cone,
                brush = Brush.linearGradient(
                    colors = listOf(
                        beamColor.copy(alpha = 0.40f),
                        beamColor.copy(alpha = 0.04f),
                    ),
                    start = Offset(w * 0.28f, h * 0.33f),
                    end = Offset(w * 0.58f, h * 0.66f),
                ),
            )
        }

        // logo 那一格：光圈、logo、闪光点三层叠在一起居中，
        // 光圈必须挂在这一格里而不是整个容器上，不然会浮在格子正中央，
        // 跟落在右下的 logo 对不上。
        Box(
            modifier = Modifier
                .size(24.dp)
                .align(Alignment.BottomEnd),
            contentAlignment = Alignment.Center,
        ) {
            // 光圈：呼吸的径向渐变，铺在 logo 背后
            Box(
                modifier = Modifier
                    .requiredSize(30.dp)
                    .graphicsLayer { alpha = glowAlpha }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                beamColor.copy(alpha = 0.60f),
                                beamColor.copy(alpha = 0.18f),
                                Color.Transparent,
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )

            AnimatedContent(
                targetState = serviceName,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.5f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.7f))
                },
                label = "focus-logo",
            ) { name ->
                AutoAIIcon(
                    name = name,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 闪光点：在 logo 中心起一个白点，边放大边淡掉，模拟光点打上去
            Box(
                modifier = Modifier
                    .size((2f + spark * 5f).dp)
                    .graphicsLayer { alpha = (1f - spark) * 0.95f }
                    .background(Color.White, CircleShape),
            )
        }

        // 左上角的小放大镜，斜一点指向右下的 logo
        Icon(
            imageVector = HugeIcons.Search01,
            contentDescription = stringResource(R.string.use_web_search),
            modifier = Modifier
                .size(13.dp)
                .align(Alignment.TopStart)
                .rotate(22f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
