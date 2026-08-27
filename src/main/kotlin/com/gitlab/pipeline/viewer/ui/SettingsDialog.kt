package com.gitlab.pipeline.viewer.ui

import com.gitlab.pipeline.viewer.settings.GitLabSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.Nullable
import javax.swing.JComponent

/**
 * GitLab 连接与刷新设置对话框。
 *
 * 与原 Java 版的等价语义（业务行为 100% 不变）：
 * - 顶部 "GitLab 地址" 文本框
 * - "访问令牌 (api)" 密码框
 * - "运行中的 Job 日志自动刷新" 复选框
 * - "日志刷新间隔(秒)" / "每页流水线条数" / "请求超时(秒)" 三个数字输入框
 *
 * UI 改造点：GridBagLayout 替换为 [panel] DSL；JTextField/JPasswordField/JCheckBox
 * 替换为主题感知的 JB 系列，浅色 / 深色主题都自动跟随。
 */
class SettingsDialog(@Nullable project: Project?) : DialogWrapper(project) {

    private val urlField = JBTextField()
    private val tokenField = JBPasswordField()
    private val autoRefreshCheck = JBCheckBox("运行中的 Job 日志自动刷新")
    private val intervalField = JBTextField()
    private val pageSizeField = JBTextField()
    private val timeoutField = JBTextField()

    init {
        title = "GitLab 设置"
        val s = GitLabSettings.getInstance()
        urlField.text = s.gitlabUrl
        tokenField.text = s.token
        autoRefreshCheck.isSelected = s.isAutoRefresh
        intervalField.text = s.refreshIntervalSeconds.toString()
        pageSizeField.text = s.pipelinePageSize.toString()
        timeoutField.text = s.requestTimeoutSeconds.toString()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        // rows {} 内部：每行一个 label + 控件；自动对齐、间距、主题感知。
        // 复选框独占一行（无 label）。
        row("GitLab 地址:") {
            cell(urlField)
        }
        row("访问令牌 (api):") {
            cell(tokenField)
        }
        row {
            cell(autoRefreshCheck)
        }
        row("日志刷新间隔(秒):") {
            cell(intervalField)
        }
        row("每页流水线条数:") {
            cell(pageSizeField)
        }
        row("请求超时(秒):") {
            cell(timeoutField)
        }
    }

    override fun doOKAction() {
        val s = GitLabSettings.getInstance()
        s.gitlabUrl = urlField.text.trim()
        s.token = String(tokenField.password)
        s.isAutoRefresh = autoRefreshCheck.isSelected
        s.refreshIntervalSeconds = intervalField.text.parseInt(10)
        s.pipelinePageSize = pageSizeField.text.parseInt(10)
        s.requestTimeoutSeconds = timeoutField.text.parseInt(15)
        super.doOKAction()
    }

    /**
     * 容错数字解析：空白 / 非数字 / 越界统一回退到 fallback，
     * 与原 Java 实现的 [Integer.parseInt] 失败兜底语义一致。
     */
    private fun String.parseInt(fallback: Int): Int = try {
        trim().toInt()
    } catch (_: NumberFormatException) {
        fallback
    }
}
