package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File

data class SkillFile(
    val file: File,
    val relativePath: String,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

class SkillDetailVM(
    private val context: Context,
    private val skillManager: SkillManager,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    /** 当前技能的元信息，详情页顶部的卡片和编辑区都读它。 */
    private val _meta = MutableStateFlow<SkillMetadata?>(null)
    val meta = _meta.asStateFlow()

    /** 已有分类，给分类选择器做候选。 */
    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories = _categories.asStateFlow()

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
        loadMeta()
    }

    fun loadMeta() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = skillManager.listSkills()
            _meta.value = all.firstOrNull { it.name == skillName }
            _categories.value = all
                .mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
                .distinct()
                .sorted()
        }
    }

    /**
     * 改中文名和分类。
     *
     * 只动 SKILL.md 的 frontmatter 里的两行，别的键原样保留。
     * 空串表示清掉这个键（中文名为空时列表会回退显示英文名）。
     */
    fun updateMeta(title: String, category: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = skillManager.updateSkillMeta(
                skillName = skillName,
                title = title.trim().ifBlank { null },
                category = category.trim().ifBlank { null },
            )
            if (ok) {
                val all = skillManager.listSkills()
                _meta.value = all.firstOrNull { it.name == skillName }
                _categories.value = all
                    .mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
                    .distinct()
                    .sorted()
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = skillManager.getSkillDir(skillName) ?: return@launch
            _tree.value = buildTree(dir, dir)
        }
    }

    private fun buildTree(root: File, dir: File): List<SkillFileNode> {
        val items = dir.listFiles()?.toList() ?: return emptyList()
        val files = items
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name }))
            .map { f -> SkillFileNode.FileNode(SkillFile(f, f.relativeTo(root).path)) }
        val dirs = items
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { d -> SkillFileNode.DirNode(d.name, d.relativeTo(root).path, buildTree(root, d)) }
        return dirs + files
    }

    fun readFile(skillFile: SkillFile): String = skillFile.file.readText()

    // Returns null on success, error message on failure
    fun saveFile(relativePath: String, content: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    withContext(Dispatchers.Main) {
                        onResult(context.getString(R.string.skill_detail_name_immutable, skillName))
                    }
                    return@launch
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            withContext(Dispatchers.Main) {
                onResult(if (success) null else context.getString(R.string.skill_detail_save_failed))
            }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }
}
