package com.gitlab.pipeline.viewer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nullable

/**
 * 新建流水线对话框：分支下拉选择 + 自定义变量（每行 key=value）。
 *
 * 与原 Java 版等价语义：
 * - 分支列表非空时用下拉选择；为空时回退为可编辑下拉（用户可手动输入分支名），
 *   初始值 = 入参 defaultRef。
 * - 变量区是多行文本框，每行 `key=value`，解析时 `=` 只分割一次，空行忽略。
 * - getRef() / getVariables() 公开 API 保持原签名（外部按 String 读）。
 *
 * UI 改造点：JComboBox → [ComboBox]（主题感知下拉），JTextArea → [JBTextArea]，
 * GridBagLayout → [panel] DSL。
 */
class TriggerPipelineDialog(
    @Nullable project: Project?,
    private val defaultRef: String?,
    branches: List<String>?
) : DialogWrapper(project) {

    private val refCombo: ComboBox<String> = ComboBox()
    private val variablesArea: JBTextArea = JBTextArea().apply {
        rows = 6
        columns = 40
        lineWrap = false
        isEditable = true
        isFocusable = true
        font = JBUI.Fonts.create("Monospaced", JBUI.scale(12))
    }

    init {
        title = "新建流水线"
        val safeBranches = branches.orEmpty()
        safeBranches.forEach { refCombo.addItem(it) }
        if (refCombo.itemCount == 0) {
            // 分支加载失败等异常情况：回退为可手动输入，保证功能可用
            refCombo.isEditable = true
            refCombo.editor.item = defaultRef.orEmpty()
        } else {
            val found = defaultRef?.let { want ->
                (0 until refCombo.itemCount).firstOrNull { refCombo.getItemAt(it) == want }
            }
            if (found != null) {
                refCombo.selectedIndex = found
            } else {
                refCombo.selectedIndex = 0
            }
        }
        init()
    }

    override fun createCenterPanel() = panel {
        row("分支 (ref):") {
            cell(refCombo)
                .align(AlignX.FILL)
                .resizableColumn()
        }
        row("变量 (每行 key=value):") {
            cell(JBScrollPane(variablesArea))
                .resizableColumn()
        }
    }.apply { preferredSize = JBUI.size(480, 260) }

    /**
     * 选中的分支（去空格），不可编辑回退时直接读编辑器内容。
     */
    val ref: String
        get() = refCombo.selectedItem?.toString()?.trim().orEmpty()

    /**
     * 解析变量文本：每行 `key=value`，`=` 只切一次，无 `=` 则 value 为空串。
     * 与原 Java [java.util.LinkedHashMap] 行为一致：保留插入顺序。
     */
    val variables: Map<String, String>
        get() {
            val map = linkedMapOf<String, String>()
            for (rawLine in variablesArea.text.split("\n")) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq > 0) {
                    map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                } else {
                    map[line] = ""
                }
            }
            return map
        }
}
