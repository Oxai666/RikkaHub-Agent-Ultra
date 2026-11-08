package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.groupSkillsByCategory
import me.rerere.rikkahub.data.files.skillCategoryLabel
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.skills.CatalogEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }

    // 搜索 + 分组折叠。84 个技能平铺下来要滑很久才找得到。
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(skills, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            skills
        } else {
            skills.filter {
                it.name.contains(q, ignoreCase = true) ||
                    (it.title?.contains(q, ignoreCase = true) == true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }
    }
    val grouped = remember(filtered) { groupSkillsByCategory(filtered) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(
                            imageVector = Lucide.Globe,
                            contentDescription = stringResource(R.string.skill_catalog_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(onClick = { showImportDialog = true }) {
                    Icon(
                        HugeIcons.Download01,
                        contentDescription = stringResource(R.string.skills_page_import_from_github)
                    )
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.accessibility_add_skill))
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (skills.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Puzzle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 搜索：84 个技能靠滑找不到。中英文名和描述都匹配。
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.skills_page_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Lucide.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Lucide.X,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (filtered.isEmpty() && query.isNotBlank()) {
                item(key = "no-result") {
                    Text(
                        text = stringResource(R.string.skills_page_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            grouped.forEach { (categoryKey, categorySkills) ->
                item(key = "header-$categoryKey") {
                    val open = expanded[categoryKey] == true || query.isNotBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded[categoryKey] = !open }
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (open) Lucide.ChevronDown else Lucide.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = skillCategoryLabel(categoryKey),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = categorySkills.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (expanded[categoryKey] == true || query.isNotBlank()) {
                    items(categorySkills, key = { it.skillDir.absolutePath }) { skill ->
                        SkillCard(
                            skill = skill,
                            onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                            onDelete = { deleteTarget = skill },
                        )
                    }
                }
            }
        }
    }
    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            knownCategories = remember(skills) {
                skills.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
                    .distinct()
                    .sorted()
            },
            onConfirm = { name, content, title, category ->
                vm.saveSkill(name, content) { ok ->
                    if (ok && (title.isNotBlank() || category.isNotBlank())) {
                        vm.updateMeta(name, title, category)
                    }
                    showAddDialog = false
                }
            },
        )
    }

    // Phase 19C — local-file picker. Lifted to screen scope so the launcher survives
    // dialog dismiss/recreate (per Compose docs: `rememberLauncherForActivityResult`
    // belongs at the highest stable composable, NOT inside a dialog body).
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importFromLocalFile(uri) { success, message ->
                showImportDialog = false
                if (success) {
                    toaster.show(context.getString(R.string.skills_page_import_success, message))
                } else {
                    val key = mapImportErrorKeyToString(context, message)
                    toaster.show(context.getString(R.string.skills_page_import_failed, key))
                }
            }
        }
    }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(context.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
            onPickFile = {
                openDocumentLauncher.launch(arrayOf(
                    "text/markdown",
                    "text/plain",
                    "application/zip",
                    "application/octet-stream",
                ))
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }

    if (showCatalog) {
        val installedNames by vm.installedSkillNames.collectAsStateWithLifecycle()
        FeaturedCatalogSheet(
            entries = vm.catalog.skills,
            installedNames = installedNames,
            onInstall = { entry, onDone ->
                vm.installFromCatalog(entry) { success, message ->
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        val key = mapImportErrorKeyToString(context, message)
                        toaster.show(context.getString(R.string.skill_catalog_install_failed, key))
                    }
                    onDone()
                }
            },
            onDismiss = { showCatalog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeaturedCatalogSheet(
    entries: List<CatalogEntry>,
    installedNames: Set<String>,
    onInstall: (CatalogEntry, onResult: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_catalog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.cancel))
                }
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_page_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        CatalogRow(
                            entry = entry,
                            installed = entry.name in installedNames,
                            installing = entry.name in installing,
                            onInstall = {
                                installing = installing + entry.name
                                onInstall(entry) {
                                    installing = installing - entry.name
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${entry.category} · ${entry.sizeKb} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            FilledTonalButton(
                onClick = onInstall,
                enabled = !installed && !installing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    when {
                        installed -> stringResource(R.string.skill_catalog_installed)
                        installing -> stringResource(R.string.skill_catalog_installing)
                        else -> stringResource(R.string.skill_catalog_install)
                    }
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 主色圆底图标，和详情页顶部那张卡保持一个语言
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Puzzle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = skill.title?.takeIf { it.isNotBlank() } ?: skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 中文名下面挂英文标识符，小字。排查问题时要对得上文件名。
                if (!skill.title.isNullOrBlank() && skill.title != skill.name) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 分类徽章：主色半透明底，跟详情页一样
                skill.category?.takeIf { it.isNotBlank() }?.let { category ->
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = skillCategoryLabel(category),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    knownCategories: List<String> = emptyList(),
    onConfirm: (name: String, content: String, title: String, category: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                    placeholder = {
                        Text(
                            "---\nname: my-skill\ndescription: \"...\"\n---\n\nSkill body goes here...",
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    supportingText = {
                        if (nameError) Text(
                            stringResource(R.string.skills_page_name_error),
                            color = MaterialTheme.colorScheme.error
                        )
                        else if (name.isNotBlank()) Text(stringResource(R.string.skills_page_skill_name, name))
                        else Text(stringResource(R.string.skills_page_paste_hint))
                    },
                    isError = nameError,
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 粘贴完顺手填个中文名，省得再点进详情页改
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("中文名（可选）") },
                    placeholder = { Text("留空就显示英文标识符") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类（可选）") },
                    placeholder = { Text("填新的就是新分类") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (knownCategories.isNotEmpty()) {
                    Text(
                        text = "已有分类",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        knownCategories.forEach { c ->
                            AssistChip(
                                onClick = { category = c },
                                label = { Text(c, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content, title, category) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
    onPickFile: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Phase 19C — secondary action that defers to the screen-level file
                // launcher. The dialog passes through; SkillsPage handles the SAF
                // OpenDocument result and routes to the VM.
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skill_import_from_file_label))
                }
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Map a VM-emitted error key (one of the `skill_import_*` resource names) to its
 * localized string. We use this rather than passing string-resource IDs out of the VM
 * because the VM has no `Context` callback path for stringResource lookups.
 */
private fun mapImportErrorKeyToString(context: android.content.Context, key: String): String {
    return when (key) {
        "skill_import_unsupported_file_type" ->
            context.getString(R.string.skill_import_unsupported_file_type)
        "skill_import_missing_skill_md" ->
            context.getString(R.string.skill_import_missing_skill_md)
        "skill_import_path_traversal" ->
            context.getString(R.string.skill_import_path_traversal)
        "skill_import_zip_too_large" ->
            context.getString(R.string.skill_import_zip_too_large)
        "skill_import_md_too_large" ->
            context.getString(R.string.skill_import_md_too_large)
        "skill_import_empty_file" ->
            context.getString(R.string.skill_import_empty_file)
        else -> key  // already a free-form message (e.g. importer's "html_response" detail)
    }
}
