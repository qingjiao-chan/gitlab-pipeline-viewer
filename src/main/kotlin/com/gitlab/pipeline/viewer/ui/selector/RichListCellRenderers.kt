package com.gitlab.pipeline.viewer.ui.selector

import com.gitlab.pipeline.viewer.model.JobInfo
import com.gitlab.pipeline.viewer.model.ProjectEntry
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.UIManager

/**
 * 通用多行 ListCellRenderer：主行（粗体） + 副行（灰色）。
 *
 * 为什么不直接用 IDEA 2023.2 的 [com.intellij.ui.dsl.listCellRenderer] DSL：
 * 该 DSL 的 [com.intellij.ui.dsl.listCellRenderer.LcrRow] 只暴露
 * `text` / `icon` / `cell` / `renderer` 四个槽位，没有 secondaryText 概念。
 * 想要两行只能嵌入 JComponent 子组件，反而更绕。
 *
 * 这里用 [SimpleColoredComponent] 经典 API 直接堆叠两行：所有 IDEA 版本都支持、
 * 支持 fragment（粗体/颜色混排）、滚动流畅（共享 component 实例）。
 */
fun <T> twoLineRenderer(
    main: (T) -> String?,
    secondary: (T) -> String?,
    iconProvider: ((T) -> javax.swing.Icon?)? = null
): ListCellRenderer<T> = TwoLineListCellRenderer(main, secondary, iconProvider)

/**
 * 多行 ListCellRenderer 共享实现：每次渲染只重置 SimpleColoredComponent 内容，
 * 不 new Component —— 避免长列表滚动时频繁分配对象导致卡顿。
 */
private class TwoLineListCellRenderer<T>(
    private val mainExtractor: (T) -> String?,
    private val secondaryExtractor: (T) -> String?,
    private val iconProvider: ((T) -> javax.swing.Icon?)?
) : ListCellRenderer<T>, JPanel(BorderLayout()) {

    private val mainLine = SimpleColoredComponent()
    private val subLine = SimpleColoredComponent()

    // 选区背景/前景色缓存（每次 paint 前用 UIManager 取，保证主题切换时跟随）
    private val rowPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(mainLine)
        add(subLine)
    }

    init {
        // 透明背景：外层 JPanel 由 setBackground 接管选中态底色
        isOpaque = false
        rowPanel.isOpaque = false
        add(rowPanel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out T>, value: T?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        // 1) 选区背景/前景色（跟随主题）
        val bg: Color = if (isSelected) {
            UIManager.getColor("List.selectionBackground") ?: JBColor.namedColor("List.selectionBackground", 0x2675BF)
        } else {
            UIManager.getColor("List.background") ?: JBColor.background()
        }
        val fg: Color = if (isSelected) {
            UIManager.getColor("List.selectionForeground") ?: JBColor.namedColor("List.selectionForeground", 0xFFFFFF)
        } else {
            UIManager.getColor("List.foreground") ?: JBColor.foreground()
        }
        val subFg: Color = if (isSelected) fg else JBColor.namedColor("Label.infoForeground", 0x787878)

        // 2) 重置文本（保留 SimpleColoredComponent 实例，避免 new）
        mainLine.clear()
        subLine.clear()
        mainLine.background = bg
        subLine.background = bg
        if (value != null) {
            val mainText = mainExtractor(value)
            if (!mainText.isNullOrEmpty()) {
                // 主行：粗体 + fg
                mainLine.append(
                    mainText,
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg)
                )
            }
            val subText = secondaryExtractor(value)
            if (!subText.isNullOrEmpty()) {
                // 副行：常规字重 + 灰色
                subLine.append(
                    subText,
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, subFg)
                )
            }
            iconProvider?.invoke(value)?.let { mainLine.icon = it }
        }
        background = bg
        return this
    }
}

// ==================== 业务专用 Renderer（项目 / Job） ====================

/**
 * 项目下拉渲染器：
 * - 主行：项目最后一级名（粗体）
 * - 副行：父组路径（剥掉最后一级，灰色）
 * - 当前窗口项目 vs 远程项目：icon 不同
 */
fun projectRenderer(currentWindowIcon: javax.swing.Icon, remoteIcon: javax.swing.Icon): ListCellRenderer<ProjectItem> =
    twoLineRenderer(
        main = { it.entry.toString() },                 // ProjectEntry.toString() 只显示最后一级
        secondary = { parentPathOf(it.entry.path) },
        iconProvider = { if (it.source == ProjectItem.Source.CURRENT_WINDOW) currentWindowIcon else remoteIcon }
    )

/**
 * Job 下拉渲染器：
 * - 主行：Job 名（粗体）
 * - 副行：stage · status · 耗时（灰色）
 */
fun jobRenderer(): ListCellRenderer<JobInfo> =
    twoLineRenderer(
        main = { it.name },
        secondary = { formatJobSubLine(it) }
    )

/** 把 "group/subgroup/project" 变成 "group/subgroup"（父组路径） */
private fun parentPathOf(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i < 0) "" else path.substring(0, i)
}

/** Job 副行：stage · status · 持续时间 */
private fun formatJobSubLine(job: JobInfo): String {
    val parts = mutableListOf<String>()
    if (!job.stage.isNullOrBlank()) parts += job.stage
    if (!job.status.isNullOrBlank()) parts += job.status
    if (job.durationSeconds > 0) parts += formatDuration(job.durationSeconds)
    return parts.joinToString("  ·  ")
}

/** 秒数 → "1m23s" / "12s" / "1h02m" */
private fun formatDuration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    if (m < 60) return "${m}m${"%02d".format(s)}s"
    val h = m / 60
    val mm = m % 60
    return "${h}h${"%02d".format(mm)}m"
}
