package com.gitlab.pipeline.viewer.ui

import com.gitlab.pipeline.viewer.model.GroupChildrenView
import com.gitlab.pipeline.viewer.model.GroupEntry
import com.gitlab.pipeline.viewer.model.ProjectEntry
import com.gitlab.pipeline.viewer.services.ProjectSelectionService
import com.gitlab.pipeline.viewer.settings.GitLabSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.NotNull
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 树形项目选择器（对标 Element Plus 的 tree-select）：
 * - 单个下拉：点击弹出「搜索框 + 树」；
 * - 树的第一项固定为「当前项目」（当前 IDEA 窗口检测到的项目），其后按 GitLab 项目组分层；
 * - 项目组/子组按需懒加载（展开时才拉取子组与直接项目），不一次性加载所有项目；
 * - 搜索框只对「已加载出来的项目」做模糊过滤（组/子组节点随命中祖先一起显示）；
 * - 只能选中项目（叶子），组节点不可选。
 */
class ProjectTreeSelector(@NotNull private val ideaProject: Project) : JPanel(BorderLayout()) {

    private val displayField = JTextField()

    /**
     * IDEA 原生弹层（按需创建）：在 ToolWindow 内嵌时仍按 IdeEventQueue 标准路径派发事件
     */
    private var popup: JBPopup? = null
    private val searchField = SearchTextField()
    private val rootNode: DefaultMutableTreeNode = DefaultMutableTreeNode("root")
    private val treeModel: DefaultTreeModel = DefaultTreeModel(rootNode)
    private val tree: JTree = JTree(treeModel)
    private var scroll: JBScrollPane? = null
    private var popupContent: JPanel? = null

    /**
     * 已加载过子内容的组节点（避免展开时重复请求）
     */
    private val loadedGroups = mutableSetOf<DefaultMutableTreeNode>()

    /**
     * 正在加载子内容的组节点（避免搜索展开/重复展开触发并发重复请求）
     */
    private val loadingGroups = mutableSetOf<DefaultMutableTreeNode>()

    /**
     * 搜索过滤树的克隆节点 -> 真实树节点 映射（展开克隆组时懒加载到真实节点上）
     */
    private val liveNodes = mutableMapOf<DefaultMutableTreeNode, DefaultMutableTreeNode>()

    private var groupsLoaded = false
    var selectedProject: ProjectEntry? = null
        private set

    /**
     * 重新加载的版本号：手动刷新后丢弃过期异步结果
     */
    private var reloadGen: Long = 0

    private val listeners = mutableListOf<(ProjectEntry) -> Unit>()

    init {
        buildUi()
    }

    // ---------------------------------------------------------------- 构建 UI

    private fun buildUi() {
        // 展示框：不可编辑，点击弹出下拉
        displayField.isEditable = false
        displayField.isFocusable = false
        displayField.preferredSize = Dimension(JBUI.scale(400), JBUI.scale(26))
        displayField.toolTipText = "点击选择项目"
        val arrow = JBLabel(" ▾")
        arrow.cursor = Cursor.getDefaultCursor()
        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = togglePopup()
        }
        displayField.addMouseListener(toggle)
        arrow.addMouseListener(toggle)
        val fieldWrap = JPanel(BorderLayout())
        fieldWrap.add(displayField, BorderLayout.CENTER)
        fieldWrap.add(arrow, BorderLayout.EAST)
        add(fieldWrap, BorderLayout.CENTER)

        // 弹层：搜索框 + 树
        searchField.preferredSize = Dimension(JBUI.scale(440), 26)
        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        tree.isRootVisible = false
        tree.showsRootHandles = true
        // 单击组节点即展开（触发懒加载）；项目节点单击仍是选中
        tree.toggleClickCount = 1
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                t: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val c = super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus)
                if (value is DefaultMutableTreeNode) {
                    when (val uo = value.userObject) {
                        is ProjectEntry -> {
                            // 只显示最后一级项目名，层级由树结构体现（path 放在 tooltip）
                            text = uo.toString()
                            toolTipText = uo.path
                        }

                        is GroupEntry -> {
                            // 只显示组名（单级），不显示带斜杠的完整路径
                            text = uo.name
                            toolTipText = uo.fullPath
                        }

                        is String -> text = uo
                    }
                }
                return c
            }
        }
        // 展开组节点时懒加载其子组与直接项目（过滤树的克隆节点映射到真实节点后加载）
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                val last = event.path.lastPathComponent
                if (last is DefaultMutableTreeNode) {
                    var node = last
                    val live = liveNodes[node]
                    if (live != null) node = live
                    val uo = node.userObject
                    if (uo is GroupEntry) {
                        loadGroupChildren(node, uo)
                    }
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                // 不需要处理折叠
            }
        })
        // 只允许选中项目（叶子），组/当前项目等节点不可选
        tree.addTreeSelectionListener {
            val last = tree.lastSelectedPathComponent
            if (last is DefaultMutableTreeNode) {
                val uo = last.userObject
                if (uo is ProjectEntry) {
                    selectProject(uo)
                } else {
                    tree.clearSelection()
                }
            }
        }
        // 右键菜单：复制命中节点的路径（项目/组），树本身不支持文本选中，右键复制更方便
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showCopyPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showCopyPopup(e)
            }
        })

        val content = JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
            border = BorderFactory.createEmptyBorder(
                JBUI.scale(4), JBUI.scale(4), JBUI.scale(4), JBUI.scale(4)
            )
            add(searchField, BorderLayout.NORTH)
        }
        val sp = JBScrollPane(tree).apply {
            setVerticalScrollBarPolicy(JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED)
            preferredSize = Dimension(JBUI.scale(440), JBUI.scale(320))
        }
        scroll = sp
        content.add(sp, BorderLayout.CENTER)
        popupContent = content
        // 弹层由 JBPopupFactory 在 togglePopup 中按需创建——content（搜索框+滚动树）
        // 已构建完成，JBScrollPane 自带 L&F 的 MouseWheelListener，
        // JBPopup 参与 IdeEventQueue 分发，滚轮事件按 Swing 标准路径派发到 scroll。
    }

    // ---------------------------------------------------------------- 对外接口

    /**
     * 设置「当前项目」下的项目列表（当前窗口检测到的 GitLab 项目）
     */
    fun setCurrentWindowProjects(@NotNull projects: List<ProjectEntry>) {
        var currentNode = findCurrentProjectNode()
        if (currentNode == null) {
            currentNode = DefaultMutableTreeNode(CURRENT_PROJECT_LABEL)
            rootNode.insert(currentNode, 0)
        }
        currentNode.removeAllChildren()
        for (p in projects) {
            currentNode.add(DefaultMutableTreeNode(p))
        }
        if (projects.isEmpty()) {
            currentNode.add(DefaultMutableTreeNode(EMPTY_LABEL))
        }
        treeModel.nodeStructureChanged(currentNode)
        // 尝试恢复上次选择的项目（仅限当前窗口项目，静默恢复，不触发回调）；
        // 未恢复成功时默认选中第一个当前窗口项目
        val last = GitLabSettings.getInstance().lastProjectPath
        var pick: ProjectEntry? = null
        for (p in projects) {
            if (p.path == last) {
                pick = p
                break
            }
        }
        if (pick == null && projects.isNotEmpty()) {
            pick = projects[0]
        }
        if (pick != null) {
            selectedProject = pick
            displayField.text = pick.path
            displayField.toolTipText = pick.path
        }
        applyFilter()
    }

    fun addProjectSelectionListener(@NotNull listener: (ProjectEntry) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 手动刷新：清缓存、重置组树，并立即重新加载顶级项目组
     */
    fun reload() {
        service().clearCache()
        reloadGen++
        groupsLoaded = false
        loadedGroups.clear()
        loadingGroups.clear()
        for (i in rootNode.childCount - 1 downTo 0) {
            val n = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val uo = n.userObject
            if (!(uo is String && CURRENT_PROJECT_LABEL == uo)) {
                rootNode.remove(i)
            }
        }
        treeModel.nodeStructureChanged(rootNode)
        loadTopLevelGroups()
    }

    // ---------------------------------------------------------------- 弹层与懒加载

    private fun togglePopup() {
        val visible = popup?.isVisible == true
        if (visible) {
            popup?.closeOk(null)
            return
        }
        val pc = popupContent ?: return
        // 弹层宽度取目标宽度与当前窗口可用宽度的较小值，避免超出窗口被裁切
        val w = popupWidth()
        val h = popupHeight()
        searchField.preferredSize = Dimension(w, 26)
        scroll?.preferredSize = Dimension(w, h)
        // 单位步进按行高设置，使默认滚轮行为一档对应一行
        scroll?.verticalScrollBar?.unitIncrement = currentRowHeight()
        searchField.text = ""
        applyFilter()
        tree.clearSelection()
        // IDEA 原生弹层：参与 IdeEventQueue 分发，ToolWindow 内滚轮等事件按标准路径派发
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(pc, searchField)
            .setTitle("选择项目")
            .setResizable(true)
            .setMovable(false)
            .setRequestFocus(true)
            .setFocusable(true)
            .setShowBorder(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMinSize(Dimension(JBUI.scale(280), JBUI.scale(180)))
            .setDimensionServiceKey(ideaProject, "ProjectTreeSelector.Popup", false)
            .createPopup()
        popup?.showUnderneathOf(this)
        SwingUtilities.invokeLater {
            expandCurrentProjectNode()
            searchField.requestFocusInWindow()
        }
    }

    /**
     * 树的当前行高（像素），供滚动条单位步进使用
     */
    private fun currentRowHeight(): Int {
        var rowH = tree.rowHeight
        if (rowH <= 0) {
            val r: Rectangle? = if (tree.rowCount > 0) tree.getRowBounds(0) else null
            rowH = if (r != null && r.height > 0) r.height else JBUI.scale(16)
        }
        return maxOf(1, rowH)
    }

    /**
     * 自动展开「当前项目」节点（存在且有子节点时）
     */
    private fun expandCurrentProjectNode() {
        val cur = findCurrentProjectNode()
        if (cur != null && cur.childCount > 0) {
            tree.expandPath(TreePath(cur.path))
        }
    }

    /**
     * 弹层可用宽度：目标 440，但不超过最外层容器的可用宽度
     */
    private fun popupWidth(): Int {
        val target = JBUI.scale(440)
        val min = JBUI.scale(260)
        var c: Component? = this
        while (c?.parent != null) c = c.parent
        val avail = (c?.width ?: 0) - JBUI.scale(20)
        return maxOf(min, minOf(target, maxOf(avail, min)))
    }

    /**
     * 弹层可用高度：目标 320，但不超过本组件下方到屏幕底部的可用空间（避免弹出后底部被切掉）
     */
    private fun popupHeight(): Int {
        val target = JBUI.scale(320)
        val min = JBUI.scale(120)
        return try {
            val pos = locationOnScreen
            val bottom = Toolkit.getDefaultToolkit().screenSize.height
            val below = bottom - (pos.y + height)
            maxOf(min, minOf(target, below - JBUI.scale(8)))
        } catch (_: Exception) {
            target
        }
    }

    /**
     * 懒加载顶级项目组（GET /groups），成功后挂到根节点
     */
    private fun loadTopLevelGroups() {
        if (groupsLoaded) return
        groupsLoaded = true
        val gen = reloadGen
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "加载项目组", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    val groups: List<GroupEntry> = service().loadRootGroups()
                    ApplicationManager.getApplication().invokeLater {
                        if (gen != reloadGen) return@invokeLater
                        for (g in groups) {
                            rootNode.add(groupNode(g))
                        }
                        treeModel.nodeStructureChanged(rootNode)
                        applyFilter()
                    }
                } catch (t: Exception) {
                    groupsLoaded = false // 下次打开再重试
                }
            }
        })
    }

    /**
     * 展开组节点时懒加载其直接子组与直接项目
     */
    private fun loadGroupChildren(node: DefaultMutableTreeNode, group: GroupEntry) {
        if (loadedGroups.contains(node) || loadingGroups.contains(node)) return
        loadingGroups.add(node)
        val gen = reloadGen
        node.removeAllChildren()
        node.add(DefaultMutableTreeNode(LOADING_LABEL))
        treeModel.nodeStructureChanged(node)
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "加载项目组 $group", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    val view: GroupChildrenView = service().loadChildren(group.id, group.name)
                    val subGroups = view.subGroups()
                    val projects = view.projects()
                    ApplicationManager.getApplication().invokeLater {
                        if (gen != reloadGen) return@invokeLater
                        node.removeAllChildren()
                        for (g in subGroups) {
                            node.add(groupNode(g))
                        }
                        for (p in projects) {
                            node.add(DefaultMutableTreeNode(p))
                        }
                        if (node.childCount == 0) {
                            node.add(DefaultMutableTreeNode(EMPTY_LABEL))
                        }
                        loadingGroups.remove(node)
                        loadedGroups.add(node)
                        treeModel.nodeStructureChanged(node)
                        // 仅在真实树可见时重新展开该组；搜索过滤态下由 applyFilter 统一展开
                        if (treeModel.root === rootNode) {
                            tree.expandPath(TreePath(node.path))
                        }
                        applyFilter()
                    }
                } catch (t: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (gen != reloadGen) return@invokeLater
                        loadingGroups.remove(node)
                        node.removeAllChildren()
                        node.add(DefaultMutableTreeNode(ERROR_LABEL))
                        treeModel.nodeStructureChanged(node)
                    }
                }
            }
        })
    }

    /**
     * 组节点：带「加载中…」占位子节点，使其可展开以触发懒加载
     */
    private fun groupNode(g: GroupEntry): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(g)
        node.add(DefaultMutableTreeNode(LOADING_LABEL))
        return node
    }

    // ---------------------------------------------------------------- 搜索过滤

    /**
     * 按搜索框关键字过滤树：只对已加载出来的节点做过滤；
     * 组/子组作为命中项目的祖先节点保留，命中组自身也保留（但不连带展示其全部子节点）。
     */
    private fun applyFilter() {
        val q = searchField.text.lowercase().trim()
        liveNodes.clear()
        if (q.isEmpty()) {
            // 已在真实树时不要重复 setRoot：setRoot 会触发整树结构变更，导致已展开的组被收起
            if (treeModel.root !== rootNode) {
                treeModel.setRoot(rootNode)
            }
            return
        }
        val filtered = DefaultMutableTreeNode("root")
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val f = filterNode(child, q)
            if (f != null) filtered.add(f)
        }
        treeModel.setRoot(filtered)
        expandAll(filtered)
    }

    private fun filterNode(node: DefaultMutableTreeNode, q: String): DefaultMutableTreeNode? {
        val uo = node.userObject
        val selfMatch = matches(uo, q)
        val copy = DefaultMutableTreeNode(uo)
        liveNodes[copy] = node
        var visible = selfMatch
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (isPlaceholder(child)) {
                val pc = DefaultMutableTreeNode(child.userObject)
                liveNodes[pc] = child
                copy.add(pc)
                continue
            }
            val f = filterNode(child, q)
            if (f != null) {
                copy.add(f)
                visible = true
            }
        }
        return if (visible) copy else null
    }

    private fun matches(uo: Any?, q: String): Boolean {
        if (uo == null) return false
        if (q.isEmpty()) return true
        return when (uo) {
            is ProjectEntry ->
                uo.path.lowercase().contains(q) || uo.toString().lowercase().contains(q)

            is GroupEntry ->
                uo.fullPath.lowercase().contains(q) || uo.name.lowercase().contains(q)

            is String -> uo.lowercase().contains(q)
            else -> false
        }
    }

    private fun isPlaceholder(node: DefaultMutableTreeNode): Boolean {
        val uo = node.userObject
        return uo is String && (LOADING_LABEL == uo || EMPTY_LABEL == uo || ERROR_LABEL == uo)
    }

    private fun expandAll(root: DefaultMutableTreeNode) {
        for (e in root.depthFirstEnumeration()) {
            val n = e as DefaultMutableTreeNode
            if (n !== root && n.childCount > 0) {
                tree.expandPath(TreePath(n.path))
            }
        }
    }

    // ---------------------------------------------------------------- 选择

    private fun selectProject(pe: ProjectEntry) {
        selectedProject = pe
        displayField.text = pe.path
        displayField.toolTipText = pe.path
        popup?.takeIf { it.isVisible }?.closeOk(null)
        for (l in listeners) l(pe)
    }

    /**
     * 右键弹层：复制鼠标所指节点的路径（项目 path / 组 fullPath / 占位文案）
     */
    private fun showCopyPopup(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val uo = (path.lastPathComponent as DefaultMutableTreeNode).userObject
        val text: String? = when (uo) {
            is ProjectEntry -> uo.path
            is GroupEntry -> uo.fullPath
            is String -> uo
            else -> null
        }
        val menu = JPopupMenu()
        val copy = javax.swing.JMenuItem("复制路径")
        copy.isEnabled = text != null
        val copyText = text
        copy.addActionListener {
            if (copyText != null) {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(copyText), null)
            }
        }
        menu.add(copy)
        menu.show(tree, e.x, e.y)
    }

    private fun findCurrentProjectNode(): DefaultMutableTreeNode? {
        for (i in 0 until rootNode.childCount) {
            val n = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val uo = n.userObject
            if (uo is String && CURRENT_PROJECT_LABEL == uo) return n
        }
        return null
    }

    private fun service(): ProjectSelectionService = ProjectSelectionService.getInstance(ideaProject)

    companion object {
        private const val CURRENT_PROJECT_LABEL = "当前项目"
        private const val LOADING_LABEL = "加载中…"
        private const val EMPTY_LABEL = "（无内容）"
        private const val ERROR_LABEL = "（加载失败）"
    }
}
