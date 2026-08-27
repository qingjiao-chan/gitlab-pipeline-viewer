package com.gitlab.pipeline.viewer.ui.selector

import com.gitlab.pipeline.viewer.model.ProjectEntry
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 项目选择器的 [com.intellij.ide.util.gotoByName.ChooseByNameModel] 实现。
 *
 * 直接 implements ChooseByNameModel，跳过 SimpleChooseByNameModel（Kotlin 处理 abstract
 * 父类时易混淆接口/类方法）。
 *
 * 设计要点：
 * - **平铺 names**：`getNames()` 直接返回所有项目的 path 列表
 *   （搜索 "sub" 即可匹配 "group/subgroup/project"）
 * - **顺序固定为 "当前窗口项目" 优先**：[orderedPaths] 拼接顺序为 currentWindow + remote
 * - **去重**：当前窗口项目 + 远程项目中 path 重复时，只保留在 currentWindow 列表
 * - **来源区分**：[ProjectItem.Source] 在渲染时决定 icon（Module vs Project）
 */
class ProjectChooseByNameModel(private val project: Project?) : ChooseByNameModel {

    private val itemsByPath: MutableMap<String, ProjectItem> = ConcurrentHashMap()
    private val orderedPaths: MutableList<String> = CopyOnWriteArrayList()

    // ---------------------------------------------------------------- ChooseByNameModel

    override fun getPromptText(): String = "输入项目名或组路径搜索（支持中段匹配）"

    override fun getNotInMessage(): String = "No model"

    override fun getNotFoundMessage(): String = "无匹配项目"

    override fun getCheckBoxName(): String? = null

    override fun loadInitialCheckBoxState(): Boolean = false

    override fun saveInitialCheckBoxState(state: Boolean) {}

    override fun getListCellRenderer(): javax.swing.ListCellRenderer<*> {
        return projectRenderer(
            currentWindowIcon = com.intellij.icons.AllIcons.Nodes.Module,
            remoteIcon = com.intellij.icons.AllIcons.Nodes.Project
        )
    }

    override fun getNames(checkBox: Boolean): Array<String> {
        return orderedPaths.toTypedArray()
    }

    override fun getElementsByName(name: String, checkBox: Boolean, pattern: String): Array<Any> {
        val item = itemsByPath[name] ?: return emptyArray()
        return arrayOf<Any>(item)
    }

    /**
     * 参数 @NotNull → Kotlin 非空 Any；返回 @Nullable → Kotlin 可空 String?
     */
    override fun getElementName(element: Any): String? {
        return (element as? ProjectItem)?.entry?.path
    }

    override fun getSeparators(): Array<String> = emptyArray()

    override fun getFullName(element: Any): String? {
        val item = element as? ProjectItem ?: return null
        return item.entry.path
    }

    override fun getHelpId(): String? = null

    override fun willOpenEditor(): Boolean = false

    override fun useMiddleMatching(): Boolean = true

    // ---------------------------------------------------------------- 对外业务方法

    /**
     * 替换"当前窗口"项目列表（即当前 IDEA 窗口能识别到的 Git 项目，置顶展示）。
     * 若 path 与已有的远程项目重复，远程列表里移除此 path。
     */
    fun setCurrentWindowProjects(projects: List<ProjectEntry>) {
        // 1) 先清空所有 CURRENT_WINDOW 项
        val iter = itemsByPath.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value.source == ProjectItem.Source.CURRENT_WINDOW) {
                iter.remove()
                orderedPaths.remove(e.key)
            }
        }
        // 2) 把新 current 项加入（去重：若已在 remote 列表则移除）
        for (p in projects) {
            if (p == null || p.path.isNullOrEmpty()) continue
            itemsByPath.remove(p.path)  // 不论 existing 是 current 还是 remote
            itemsByPath[p.path] = ProjectItem(p, ProjectItem.Source.CURRENT_WINDOW)
        }
        // 3) 重新生成 orderedPaths：current 在前，remote 按原顺序
        rebuildOrder()
    }

    /**
     * 替换"远程"项目列表（即 BFS 加载的全量项目）。已经标记为 CURRENT_WINDOW 的
     * path 不会被覆盖（仍置顶）。
     */
    fun setRemoteProjects(projects: List<ProjectEntry>) {
        // 1) 清空所有 REMOTE 项
        val iter = itemsByPath.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value.source == ProjectItem.Source.REMOTE) {
                iter.remove()
                orderedPaths.remove(e.key)
            }
        }
        // 2) 加入新的 remote 项（跳过已在 current 中的 path）
        for (p in projects) {
            if (p == null || p.path.isNullOrEmpty()) continue
            if (itemsByPath.containsKey(p.path)) continue
            itemsByPath[p.path] = ProjectItem(p, ProjectItem.Source.REMOTE)
        }
        // 3) 重新排序
        rebuildOrder()
    }

    /**
     * 按 path 查找 item（用于程序化选中当前已选项）
     */
    fun getItemByPath(path: String): ProjectItem? = itemsByPath[path]

    /**
     * 当前所有 item 总数（用于决定弹窗 forceShowing）
     */
    fun size(): Int = itemsByPath.size

    /**
     * 全部 paths 列表（按 orderedPaths 顺序）
     */
    fun allPaths(): List<String> = orderedPaths.toList()

    /**
     * 重新生成 orderedPaths：current 在前，remote 按原 BFS 顺序
     */
    private fun rebuildOrder() {
        val current = orderedPaths.filter { itemsByPath[it]?.source == ProjectItem.Source.CURRENT_WINDOW }
        val remote = orderedPaths.filter { itemsByPath[it]?.source == ProjectItem.Source.REMOTE }
        orderedPaths.clear()
        orderedPaths.addAll(current)
        orderedPaths.addAll(remote)
    }
}

/**
 * ChooseByNamePopup 用的项目包装：entity 数据 + 来源标记
 */
data class ProjectItem(
    val entry: ProjectEntry,
    val source: Source
) {
    enum class Source { CURRENT_WINDOW, REMOTE }
}
