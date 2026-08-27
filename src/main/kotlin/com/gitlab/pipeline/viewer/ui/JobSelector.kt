package com.gitlab.pipeline.viewer.ui

import com.gitlab.pipeline.viewer.model.JobInfo
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.NotNull
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Job 选择器（按 [ProjectTreeSelector] 的「展示框 + JBPopup」模式实现，绕开 JComboBox 在
 * IntelliJ 平台 L&F 下弹层宽度不可控的问题）：
 * - 展示框：不可编辑的 [JTextField] + 右侧下拉箭头，点击/箭头点击都弹出选择器
 * - 弹层：[JBPopupFactory] 创建的 [JBPopup]，里面是一个 [JBList]，
 *   弹层宽度由我们自己控制（默认 500 物理像素），JBList 自然填满弹层宽度，
 *   文字从最左边开始排版
 *
 * 对外接口：
 * - [setJobs] —— 替换 Job 列表（保持当前选中项，若 ID 还在新列表里）
 * - [selectJobById] —— 按 ID 程序化选中（不触发选择回调）
 * - [replaceSelectedJob] —— 用最新实体替换当前选中的 Job（不触发选择回调）
 * - [selectedJob] —— 获取当前选中的 Job
 * - [addJobSelectionListener] —— 监听用户选择事件
 */
class JobSelector(ideaProject: com.intellij.openapi.project.Project) : JPanel(BorderLayout()) {

    private val displayField = JTextField()
    private var popup: JBPopup? = null
    private val listModel: DefaultListModel<JobInfo> = DefaultListModel()
    private val jobList: JBList<JobInfo> = JBList(listModel)
    private val listeners = mutableListOf<(JobInfo) -> Unit>()

    /**
     * 程序化选中期间为 true，期间不触发选择回调（避免批量加载时反复回调 onJobSelected）
     */
    private var suppressSelectionEvent = false

    var selectedJob: JobInfo? = null
        private set

    init {
        buildUi()
    }

    private fun buildUi() {
        // 展示框：固定宽度，不可编辑，鼠标移上去不变 I-beam 光标（点击才弹下拉）
        displayField.isEditable = false
        displayField.isFocusable = false
        displayField.preferredSize = Dimension(JBUI.scale(220), JBUI.scale(26))
        displayField.toolTipText = "点击选择 Job"
        val arrow = JLabel(" ▾")
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

        // 列表：自定义渲染器（Job 名可能很长，加 tooltip 悬停看完整文字）
        jobList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (c is JLabel) {
                    val full = value?.toString() ?: ""
                    c.text = full
                    val w = c.getFontMetrics(c.font).stringWidth(full)
                    c.toolTipText = if (w > c.width) full else null
                }
                return c
            }
        }
        // 用户选中条目时关闭弹层并通知外部
        jobList.addListSelectionListener { e ->
            if (e.valueIsAdjusting || suppressSelectionEvent) return@addListSelectionListener
            val picked = jobList.selectedValue ?: return@addListSelectionListener
            selectedJob = picked
            updateDisplay()
            popup?.takeIf { it.isVisible }?.closeOk(null)
            fireSelection(picked)
        }
    }

    // ---------------------------------------------------------------- 对外接口

    /**
     * 替换 Job 列表。尽量保持当前选中项（按 ID 匹配）。
     */
    fun setJobs(@NotNull jobs: List<JobInfo>) {
        suppressSelectionEvent = true
        try {
            listModel.clear()
            for (j in jobs) {
                listModel.addElement(j)
            }
            // 尝试保持当前选中
            val current = selectedJob
            if (current != null) {
                for (i in 0 until listModel.size) {
                    if (listModel.get(i).id == current.id) {
                        selectedJob = listModel.get(i)
                        jobList.setSelectedIndex(i)
                        updateDisplay()
                        return
                    }
                }
            }
            // 当前选中不在新列表里 → 清空
            selectedJob = null
            jobList.clearSelection()
            updateDisplay()
        } finally {
            suppressSelectionEvent = false
        }
    }

    /**
     * 按 ID 程序化选中（不触发选择回调）。
     */
    fun selectJobById(jobId: Long) {
        suppressSelectionEvent = true
        try {
            for (i in 0 until listModel.size) {
                if (listModel.get(i).id == jobId) {
                    selectedJob = listModel.get(i)
                    jobList.setSelectedIndex(i)
                    jobList.ensureIndexIsVisible(i)
                    updateDisplay()
                    return
                }
            }
        } finally {
            suppressSelectionEvent = false
        }
    }

    /**
     * 用最新实体替换当前选中的 Job（保持选中位置，不触发选择回调）。
     * 当前没选中时静默 no-op。
     */
    fun replaceSelectedJob(@NotNull fresh: JobInfo) {
        val current = selectedJob ?: return
        suppressSelectionEvent = true
        try {
            for (i in 0 until listModel.size) {
                if (listModel.get(i).id == current.id) {
                    listModel.set(i, fresh)
                    selectedJob = fresh
                    jobList.setSelectedIndex(i)
                    updateDisplay()
                    return
                }
            }
        } finally {
            suppressSelectionEvent = false
        }
    }

    fun addJobSelectionListener(@NotNull listener: (JobInfo) -> Unit) {
        listeners.add(listener)
    }

    private fun fireSelection(@NotNull job: JobInfo) {
        for (l in listeners) l(job)
    }

    private fun updateDisplay() {
        val current = selectedJob
        if (current != null) {
            val text = current.toString()
            displayField.text = text
            displayField.toolTipText = text
        } else {
            displayField.text = ""
            displayField.toolTipText = null
        }
    }

    // ---------------------------------------------------------------- 弹层

    private fun togglePopup() {
        val visible = popup?.isVisible == true
        if (visible) {
            popup?.closeOk(null)
            return
        }
        if (listModel.isEmpty) return
        val width = popupWidth()
        val height = popupHeight()
        val scroll = JBScrollPane(jobList).apply {
            border = BorderFactory.createEmptyBorder()
            setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED)
            setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
            preferredSize = Dimension(width, height)
        }
        // 高亮当前选中项（程序化选中只在 setJobs/selectJobById 中调过，
        // 用户中途切换也会被 ListSelectionListener 同步）
        val current = selectedJob
        if (current != null) {
            for (i in 0 until listModel.size) {
                if (listModel.get(i).id == current.id) {
                    jobList.setSelectedIndex(i)
                    jobList.ensureIndexIsVisible(i)
                    break
                }
            }
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, jobList)
            .setResizable(true)
            .setMovable(false)
            .setRequestFocus(true)
            .setFocusable(true)
            .setShowBorder(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMinSize(Dimension(JBUI.scale(220), JBUI.scale(120)))
            .createPopup()
        popup?.showUnderneathOf(this)
        SwingUtilities.invokeLater { jobList.requestFocusInWindow() }
    }

    /**
     * 弹层可用宽度：目标 500 物理像素（可设），不超出最外层容器可用宽度
     */
    private fun popupWidth(): Int {
        val target = JBUI.scale(DEFAULT_POPUP_WIDTH)
        val min = JBUI.scale(220)
        var c: Component? = this
        while (c?.parent != null) c = c.parent
        val avail = (c?.width ?: 0) - JBUI.scale(20)
        return maxOf(min, minOf(target, maxOf(avail, min)))
    }

    /**
     * 弹层可用高度：目标 320，不超过本组件下方到屏幕底部的可用空间
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

    companion object {
        private const val DEFAULT_POPUP_WIDTH = 500
    }
}
