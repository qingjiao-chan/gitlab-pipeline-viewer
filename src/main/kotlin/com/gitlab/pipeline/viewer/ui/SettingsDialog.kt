package com.gitlab.pipeline.viewer.ui

import com.gitlab.pipeline.viewer.model.Account
import com.gitlab.pipeline.viewer.services.GitLabApiException
import com.gitlab.pipeline.viewer.services.GitLabApiService
import com.gitlab.pipeline.viewer.settings.GitLabSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nullable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * GitLab 多账号与刷新设置对话框（v1.1）。
 *
 * 结构：
 * - 账号区：左侧账号列表（✓ 标记当前账号）+「设为当前 / 添加 / 删除」；
 *   右侧编辑选中账号（名称、地址、令牌 + 显示/隐藏、测试连接）。
 * - 偏好区：自动刷新 / 刷新间隔 / 每页数 / 请求超时（语义与 v1.0 一致）。
 *
 * 安全：令牌全程只经 [com.gitlab.pipeline.viewer.services.TokenStore] 写入 IDE 密码库，
 * 不落配置 XML；异常消息不回显令牌。
 *
 * 交互约定：「测试连接」不关闭弹窗，按钮旁 spinner + 就地结果（成功显示用户名，
 * 失败按 401/403/网络/非 GitLab 分类提示）。
 */
class SettingsDialog(@Nullable project: Project?) : DialogWrapper(project) {

    /** 对话框内的账号工作副本；id=null 表示尚未保存的新账号 */
    private class EditAccount(
        var id: String?,
        var name: String,
        var url: String,
        var token: String,
    )

    private val settings = GitLabSettings.getInstance()

    private val edits = mutableListOf<EditAccount>()
    private var activeIndex = -1
    private var syncingList = false

    /**
     * 表单当前正在展示/编辑的账号下标。
     * 列表选择事件触发时 [accountList] 的 selectedIndex 已指向「新行」，
     * 但表单里还是「旧行」的内容；提交必须给到旧行（本字段），
     * 否则空白新账号的表单内容会覆盖被点击的已有账号（数据被清空）。
     */
    private var formIndex = -1

    private val accountList = JBList<EditAccount>()
    // 固定列数：JTextField columns=0 时 preferredSize 随内容变化，
    // 会造成「选中有数据账号 / 空账号」时表单整体宽度左右抖动
    private val nameField = JBTextField(FIELD_COLUMNS)
    private val urlField = JBTextField(FIELD_COLUMNS)
    private val tokenField = JBPasswordField().apply { columns = FIELD_COLUMNS }
    private val showTokenCheck = JBCheckBox("显示令牌")
    private val testButton = JButton("测试连接")
    private val testResultLabel = JBLabel(" ")
    private val testSpinnerLabel = JBLabel()

    private val autoRefreshCheck = JBCheckBox("运行中的 Job 日志自动刷新")
    private val intervalField = JBTextField()
    private val pageSizeField = JBTextField()
    private val timeoutField = JBTextField()

    init {
        title = "GitLab 设置"
        val savedActiveId = settings.activeAccountId
        for (a in settings.accounts) {
            if (a.id == savedActiveId) activeIndex = edits.size
            edits.add(EditAccount(a.id, a.name, a.url, settings.getToken(a.id)))
        }
        if (activeIndex < 0 && edits.isNotEmpty()) activeIndex = 0

        autoRefreshCheck.isSelected = settings.isAutoRefresh
        intervalField.text = settings.refreshIntervalSeconds.toString()
        pageSizeField.text = settings.pipelinePageSize.toString()
        timeoutField.text = settings.requestTimeoutSeconds.toString()

        init()
        // init() 之后再绑定与加载，避免回调打到尚未初始化的对话框控件
        accountList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        accountList.cellRenderer = AccountRenderer()
        reloadList()
        accountList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) onAccountSelectionChanged()
        }
        if (edits.isNotEmpty()) accountList.selectedIndex = activeIndex
        showTokenCheck.addActionListener {
            tokenField.echoChar = if (showTokenCheck.isSelected) 0.toChar() else defaultEchoChar
        }
        testButton.addActionListener { testConnection() }
    }

    private val defaultEchoChar: Char get() = '\u25CF'

    override fun createCenterPanel(): JComponent {
        val accounts = buildAccountsPanel()
        return panel {
            group("账号") {
                row {
                    cell(accounts)
                }
            }
            group("刷新与性能") {
                row {
                    cell(autoRefreshCheck)
                }
                row("日志刷新间隔(秒):") {
                    cell(intervalField).applyToComponent { columns = 8 }
                    comment("最小 5 秒")
                }
                row("每页流水线条数:") {
                    cell(pageSizeField).applyToComponent { columns = 8 }
                    comment("最小 5")
                }
                row("请求超时(秒):") {
                    cell(timeoutField).applyToComponent { columns = 8 }
                    comment("最小 5")
                }
            }
        }.also { it.preferredSize = Dimension(JBUI.scale(620), JBUI.scale(430)) }
    }

    private fun buildAccountsPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(10), 0))

        // 左：账号列表
        accountList.visibleRowCount = 6
        val listScroll = JBScrollPane(accountList)
        listScroll.preferredSize = Dimension(JBUI.scale(210), JBUI.scale(190))
        panel.add(listScroll, BorderLayout.WEST)

        // 右：账号编辑表单（原生 GridBag 以获得标签对齐，嵌入 UI DSL 行内）
        val form = JPanel(GridBagLayout())
        form.border = JBUI.Borders.empty()
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(2)
            anchor = GridBagConstraints.WEST
        }
        gbc.gridx = 0; gbc.gridy = 0
        form.add(JBLabel("名称:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        gbc.gridwidth = 2
        form.add(nameField, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridx = 0; gbc.gridy = 1
        form.add(JBLabel("GitLab 地址:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        gbc.gridwidth = 2
        form.add(urlField, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridx = 0; gbc.gridy = 2
        form.add(JBLabel("访问令牌 (api):"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        form.add(tokenField, gbc)
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        form.add(showTokenCheck, gbc)

        gbc.gridx = 1; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE
        form.add(testButton, gbc)

        gbc.gridx = 1; gbc.gridy = 4
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        val resultLine = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(testSpinnerLabel, BorderLayout.WEST)
            add(testResultLabel, BorderLayout.CENTER)
        }
        form.add(resultLine, gbc)
        panel.add(form, BorderLayout.CENTER)

        // 左下：账号操作按钮
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        val setActiveBtn = JButton("设为当前").apply {
            addActionListener {
                val i = accountList.selectedIndex
                if (i >= 0) {
                    activeIndex = i
                    reloadList()
                }
            }
        }
        val addBtn = JButton("添加").apply {
            addActionListener { addAccount() }
        }
        val removeBtn = JButton("删除").apply {
            addActionListener { removeSelectedAccount() }
        }
        buttons.add(setActiveBtn)
        buttons.add(Box.createHorizontalStrut(JBUI.scale(6)))
        buttons.add(addBtn)
        buttons.add(Box.createHorizontalStrut(JBUI.scale(6)))
        buttons.add(removeBtn)
        val south = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            add(buttons, BorderLayout.WEST)
        }
        panel.add(south, BorderLayout.SOUTH)
        return panel
    }

    // ---------------------------------------------------------------- 账号编辑

    private fun reloadList() {
        selectIndexQuietly(activeIndex)
    }

    /**
     * 在抑制选择回调的状态下重建列表模型并选中 [i]，最后一次性加载表单。
     * 避免「选中旧行 → 再切到目标行」的中间选择事件把表单旧值提交进目标行
     *（「添加」时表现为新账号被复制成当前账号的数据）。
     */
    private fun selectIndexQuietly(i: Int) {
        syncingList = true
        try {
            accountList.model = DefaultListModel<EditAccount>().apply {
                for (e in edits) addElement(e)
            }
            accountList.selectedIndex = i
        } finally {
            syncingList = false
        }
        loadFieldsFromSelected()
    }

    private fun onAccountSelectionChanged() {
        if (syncingList) return
        // 切到另一个账号前，先把表单内容提交给「表单原来展示的行」（formIndex）。
        // 此刻 selectedIndex 已是新点击的行，绝不能用它，否则会用旧表单内容覆盖新行。
        commitFormFields()
        loadFieldsFromSelected()
    }

    private fun loadFieldsFromSelected() {
        val i = accountList.selectedIndex
        val e = edits.getOrNull(i)
        val enabled = e != null
        nameField.isEnabled = enabled
        urlField.isEnabled = enabled
        tokenField.isEnabled = enabled
        showTokenCheck.isEnabled = enabled
        testButton.isEnabled = enabled
        if (e == null) {
            nameField.text = ""
            urlField.text = ""
            tokenField.text = ""
            setTestResult(null, null)
            formIndex = -1
            return
        }
        nameField.text = e.name
        urlField.text = e.url
        tokenField.text = e.token
        setTestResult(null, null)
        formIndex = i
    }

    /**
     * 把表单当前内容提交给 [formIndex]（表单正在编辑的行），
     * 而不是列表当前选中行 —— 选择变更事件中两者可能不同。
     */
    private fun commitFormFields() {
        val e = edits.getOrNull(formIndex) ?: return
        e.name = nameField.text.trim()
        e.url = urlField.text.trim()
        e.token = String(tokenField.password)
    }

    private fun addAccount() {
        commitFormFields()
        val e = EditAccount(id = null, name = "", url = "", token = "")
        edits.add(e)
        // 新增不等于切换：✓ 当前账号保持不变，仅把编辑目标切到新行。
        // 必须静默选中（抑制中间选择事件），否则表单里仍是旧账号的值，
        // 会在切换事件中被提交给刚添加的空白账号，表现为「添加 = 复制一份当前账号」。
        selectIndexQuietly(edits.lastIndex)
        if (urlField.isEnabled) urlField.requestFocusInWindow()
    }

    private fun removeSelectedAccount() {
        val i = accountList.selectedIndex
        val e = edits.getOrNull(i) ?: return
        val what = e.name.ifBlank { e.url.ifBlank { "该账号" } }
        val answer = MessagesCompat.showYesNo(
            "确定删除账号「$what」吗？\n该账号保存的访问令牌将同时从 IDE 密码库移除。",
            "删除账号"
        )
        if (answer != JOptionPane.YES_OPTION) return
        edits.removeAt(i)
        activeIndex = when {
            edits.isEmpty() -> -1
            i <= activeIndex -> maxOf(0, activeIndex - 1)
            else -> activeIndex
        }
        reloadList()
    }

    // ---------------------------------------------------------------- 测试连接

    private fun testConnection() {
        commitFormFields()
        val e = edits.getOrNull(formIndex) ?: return
        val url = e.url
        if (url.isBlank()) {
            setTestResult(false, "请先填写 GitLab 地址")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            setTestResult(false, "地址需以 http:// 或 https:// 开头")
            return
        }
        testButton.isEnabled = false
        testSpinnerLabel.icon = AnimatedIcon.Default()
        setTestResult(null, "正在连接…")
        val timeout = timeoutField.text.parseInt(15)
        // 关键：必须在 EDT（弹窗模态上下文内）捕获模态状态。后台线程里默认状态是
        // NON_MODAL，单参 invokeLater 会把回调推迟到所有模态弹窗关闭后才派发，
        // 表现为「正在连接…」永不结束。
        val modality = ModalityState.defaultModalityState()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result: Pair<Boolean, String> = try {
                val user = GitLabApiService("__connection_test__", url, e.token, timeout).currentUser
                true to "连接成功：${user.name}（${user.username}）"
            } catch (ex: GitLabApiException) {
                false to (ex.message ?: "连接失败")
            } catch (t: Throwable) {
                false to ("连接失败：" + (t.message ?: t.javaClass.simpleName))
            }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposedSafe()) return@invokeLater
                testButton.isEnabled = true
                testSpinnerLabel.icon = null
                setTestResult(result.first, result.second)
            }, modality)
        }
    }

    private fun isDisposedSafe(): Boolean = !isShowing

    private fun setTestResult(ok: Boolean?, text: String?) {
        val raw = text?.takeIf { it.isNotBlank() }
        // 长错误文案（如服务器返回的 JSON）用固定宽度 HTML 换行，
        // 否则 JLabel preferredSize 被长文本撑大，整个弹窗会变宽并挤压左侧列表
        testResultLabel.text = if (raw != null) {
            "<html><body style='width: ${JBUI.scale(RESULT_WRAP_WIDTH)}px'>" +
                raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") +
                "</body></html>"
        } else {
            " "
        }
        when (ok) {
            true -> testResultLabel.foreground = JBColor.GREEN
            false -> testResultLabel.foreground = JBColor.RED
            null -> testResultLabel.foreground = null // 恢复 Label 默认前景，跟随主题
        }
    }

    // ---------------------------------------------------------------- 保存

    override fun doOKAction() {
        commitFormFields()

        // 校验：地址必填且名称为空时以 host 兜底（GitLabSettings.addAccount/updateAccount
        // 也有兜底，这里只做地址阻断）
        for (e in edits) {
            if (e.url.isBlank()) {
                accountList.selectedIndex = edits.indexOf(e)
                setTestResult(false, "GitLab 地址不能为空（删除该账号或填写地址后再保存）")
                return
            }
        }

        // 删除：设置中存在、工作副本中不存在
        val remainingIds = edits.mapNotNull { it.id }.toSet()
        for (existing in settings.accounts) {
            if (existing.id !in remainingIds) {
                settings.removeAccount(existing.id)
            }
        }
        // 新增 / 更新
        var newActiveId: String? = null
        for ((index, e) in edits.withIndex()) {
            val id = e.id
            if (id == null) {
                val created = settings.addAccount(e.name, e.url)
                settings.setToken(created.id, e.token)
                e.id = created.id
                if (index == activeIndex) newActiveId = created.id
            } else {
                settings.updateAccount(id, e.name.ifBlank { e.url }, e.url)
                settings.setToken(id, e.token)
                if (index == activeIndex) newActiveId = id
            }
        }
        if (newActiveId != null) {
            settings.setActiveAccount(newActiveId)
        }

        settings.isAutoRefresh = autoRefreshCheck.isSelected
        settings.refreshIntervalSeconds = intervalField.text.parseInt(10)
        settings.pipelinePageSize = pageSizeField.text.parseInt(10)
        settings.requestTimeoutSeconds = timeoutField.text.parseInt(15)
        super.doOKAction()
    }

    private fun String.parseInt(fallback: Int): Int = try {
        trim().toInt()
    } catch (_: NumberFormatException) {
        fallback
    }

    // ---------------------------------------------------------------- 渲染

    private inner class AccountRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val c = super.getListCellRendererComponent(list, value, index, selected, cellHasFocus)
            val e = value as? EditAccount
            if (e != null) {
                val mark = if (index == activeIndex) "✓ " else "    "
                val title = e.name.ifBlank { e.url.ifBlank { "新账号" } }
                val host = runCatching {
                    e.url.replaceFirst("^https?://".toRegex(), "").replaceFirst("/.*$".toRegex(), "")
                }.getOrDefault("")
                text = mark + title + if (host.isNotBlank() && host != title) "  ($host)" else ""
            }
            return c
        }
    }

    companion object {
        /** 名称/地址/令牌输入框固定列数（preferredSize 不随内容抖动） */
        private const val FIELD_COLUMNS = 32

        /** 测试结果文案排版宽度（物理像素），长错误信息在此宽度内换行，不撑宽弹窗 */
        private const val RESULT_WRAP_WIDTH = 380
    }
}

/** 避免在可空 project 下反复处理 Messages 的平台签名差异 */
private object MessagesCompat {
    fun showYesNo(message: String, title: String): Int =
        JOptionPane.showConfirmDialog(null, message, title, JOptionPane.YES_NO_OPTION)
}
