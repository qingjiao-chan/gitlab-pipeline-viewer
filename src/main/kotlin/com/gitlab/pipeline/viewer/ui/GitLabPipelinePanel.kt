package com.gitlab.pipeline.viewer.ui

import com.gitlab.pipeline.viewer.model.JobInfo
import com.gitlab.pipeline.viewer.model.PipelineInfo
import com.gitlab.pipeline.viewer.model.PipelineStatus
import com.gitlab.pipeline.viewer.model.ProjectEntry
import com.gitlab.pipeline.viewer.services.GitLabApiException
import com.gitlab.pipeline.viewer.services.GitRepositoryUtil
import com.gitlab.pipeline.viewer.services.JobTraceResult
import com.gitlab.pipeline.viewer.services.NotificationService
import com.gitlab.pipeline.viewer.services.PipelineDataService
import com.gitlab.pipeline.viewer.settings.GitLabSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.NotNull
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableColumnModel

/**
 * 主面板：项目下拉 -> 流水线表格 -> Job 选择 -> 日志查看（ConsoleView，自带 Ctrl+F 查找）。
 *
 * —— IDEA 插件开发核心概念（对标 Spring Boot）——
 * 1. EDT（Event Dispatch Thread）：Swing 的 UI 主线程。所有控件操作必须在 EDT 上做。
 * 2. Task.Backgroundable：后台任务，对标 @Async / CompletableFuture。
 * 3. invokeLater：把一段代码交回 EDT 执行，对标 @Async 后回主线程刷新页面。
 * 4. generation（乐观锁）：每次加载 +1；异步结果回来时若版本号已变则丢弃。
 * 5. Disposable：生命周期管理，窗口关闭时清理定时器等资源。
 *
 * —— 改造要点（与原 Java 版对比）——
 * - 13 个按钮操作抽成 [GitLabAction] 的内部类，update() 驱动 enabled 状态
 *   取代原 loadGateButtons / throttleAt 手写门控。
 * - 顶部 / 流水表 / 日志 三条工具条改用 [ActionToolbar]，自动主题感知 +
 *   overflow 菜单。
 * - 调接口的 action 在 isLoading 期间用 [AnimatedIcon.Default] 旋转图标替换按钮
 *   图标（对应前端 loading 效果），本地瞬时操作（设置弹窗/日志翻页）除外。
 * - 日志区加「正在加载…」占位层：点击项目/流水线/Job 后立即显示 spinner + 文案，
 *   数据到达或出错时自动隐藏，避免"点了没反应、旧内容停留"的生硬感。
 * - JSplitPane 分隔条统一为 1px 主题色细线。
 * - 控件全部从 J* 升级到 JB*，跟随 Light/Darcula 主题。
 * - 所有线程安全模式（generation、uiUpdating、disposed、Task.Backgroundable +
 *   invokeLater）原样保留。
 */
class GitLabPipelinePanel(private val ideaProject: Project, private val toolWindow: ToolWindow?) :
    JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(GitLabPipelinePanel::class.java)

    // ============================================================ 状态（线程安全模式原样保留）
    private val generation = AtomicLong(0)
    private val autoRefreshTimer: Timer
    private val layoutTimer: Timer
    private var preferredAnchor: ToolWindowAnchor? = null
    private var detectTimer: Timer? = null

    @Volatile
    private var currentProject: ProjectEntry? = null

    @Volatile
    private var currentProjectId: Long = -1

    @Volatile
    private var selectedPipelineId: Long = -1

    @Volatile
    private var selectedJobId: Long = -1

    @Volatile
    private var lastPipelines: List<PipelineInfo> = emptyList()

    @Volatile
    private var currentWindowProjects: List<ProjectEntry> = emptyList()

    @Volatile
    private var pipelinePage: Int = 1

    @Volatile
    private var pipelineHasNext: Boolean = false

    @Volatile
    private var disposed: Boolean = false
    private var uiUpdating: Boolean = false

    // Job 日志增量拉取状态（配合 Range 请求头只拉新增部分）：
    // 切换 Job 时重置；刷新时按 traceOffset 只取增量，traceCarry 保存上块末尾未完成的多字节 UTF-8 尾部
    private var traceJobId: Long = -1
    private var traceOffset: Long = 0
    private var traceCarry: ByteArray = ByteArray(0)

    /**
     * 日志刷新防叠加：上一次刷新（后台请求）尚未结束前，跳过本次定时器触发的刷新。
     * 请求已在跑，偏移推进后由下个周期继续；避免自动刷新与手动刷新、周期重叠时重复发请求，
     * 也避免"任务刚结束"边界上 200 全量响应被 206 部分响应覆盖的竞态。
     */
    private val refreshInFlight = AtomicBoolean(false)

    // ============================================================ Action 集合（13 个）
    private val refreshProjectsAction = RefreshProjectsAction()
    private val refreshListAction = RefreshListAction()
    private val prevPageAction = PrevPageAction()
    private val nextPageAction = NextPageAction()
    private val triggerAction = TriggerPipelineAction()
    private val cancelPipelineAction = CancelPipelineAction()
    private val retryPipelineAction = RetryPipelineAction()
    private val cancelJobAction = CancelJobAction()
    private val retryJobAction = RetryJobAction()
    private val refreshLogAction = RefreshLogAction()
    private val settingsAction = SettingsAction()

    // ============================================================ 控件
    private val projectSelector: ProjectTreeSelector = ProjectTreeSelector(ideaProject)

    // 必须传 ideaProject：JobSelector 内部的 ChooseByNamePopup 需要非空 project 计算 searchScope
    private val jobSelector: JobSelector = JobSelector(ideaProject)
    private val logViewer: LogViewer = LogViewer(ideaProject)

    private val pipelineModel: DefaultTableModel = object : DefaultTableModel(
        arrayOf("流水线", "状态", "Ref", "SHA", "来源", "创建时间", "耗时", "触发人"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val pipelineTable: JTable = JTable(pipelineModel)

    // 流水表分页：分页按钮（图标）也由 action 驱动，但保留页码标签作为 JLabel
    private val pipelinePageLabel: JBLabel = JBLabel("第 1 页").also {
        it.horizontalAlignment = SwingConstants.CENTER
    }

    private var split: JSplitPane? = null

    /** 三条 ActionToolbar，refreshActions 时统一 update；用 lateinit 避免 ActionToolbar? 类型推断丢失 updateActions() 方法 */
    private lateinit var topToolbar: ActionToolbar
    private lateinit var pipelineToolbar: ActionToolbar
    private lateinit var logToolbar: ActionToolbar

    init {
        buildUi()
        setMinimumSize(Dimension(JBUI.scale(340), JBUI.scale(280)))
        val initial = refreshCurrentProjects()
        if (initial != null && GitLabSettings.getInstance().token.isNotEmpty()) {
            onProjectSelected(initial)
        }
        startProjectDetection()

        val interval = maxOf(5, GitLabSettings.getInstance().refreshIntervalSeconds)
        autoRefreshTimer = Timer(interval * 1000) {
            if (disposed) return@Timer
            val s = GitLabSettings.getInstance()
            if (!s.isAutoRefresh || currentProject == null || currentProjectId == -1L || s.token.isEmpty()) {
                return@Timer
            }
            val p = selectedPipeline() ?: return@Timer
            if (!PipelineStatus.isActive(p.status)) return@Timer
            val job = jobSelector.selectedJob
            if (job != null && PipelineStatus.isActive(job.status)) {
                // 自动刷新 = Range 增量轮询：静默拉取新增日志，失败不弹窗不通知，下个周期自动重试
                refreshTrace(silent = true)
            }
        }
        autoRefreshTimer.start()

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (disposed || split == null || split!!.isShowing) {
                    syncOrientationByAspect()
                    applyDividerProportion()
                }
            }
        })
        layoutTimer = Timer(400) {
            applyDefaultDockSize()
            syncOrientationByAspect()
            applyDividerProportion()
        }
        layoutTimer.start()
        applyDefaultDockSize()
        syncOrientationByAspect()
        applyDividerProportion()
    }

    // ============================================================ UI 构建

    private fun buildUi() {
        // -------------------- 顶部工具条（项目 + 触发/取消/重试/设置）
        val topGroup = DefaultActionGroup().apply {
            add(refreshProjectsAction)
            addSeparator()
            add(triggerAction)
            add(cancelPipelineAction)
            add(retryPipelineAction)
            addSeparator()
            add(settingsAction)
        }
        val topTb = ActionManager.getInstance()
            .createActionToolbar("GitLab.Pipeline.TopToolbar", topGroup, true)
        topTb.setReservePlaceAutoPopupIcon(false)
        topTb.setTargetComponent(this)
        topToolbar = topTb
        val topPanel = wrapToolbar(topTb.component)
        topPanel.add(JBLabel("项目:"))
        projectSelector.preferredSize = Dimension(JBUI.scale(300), JBUI.scale(26))
        topPanel.add(projectSelector)
        add(topPanel, BorderLayout.NORTH)

        // -------------------- 流水表 + 工具条（刷新列表 / 上一页 / 下一页 / 页码）
        pipelineTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        pipelineTable.tableHeader.reorderingAllowed = false
        pipelineTable.rowHeight = JBUI.scale(22)
        pipelineTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val tooltipRenderer = TooltipCellRenderer()
        val colModel: TableColumnModel = pipelineTable.columnModel
        for (i in 0 until colModel.columnCount) {
            if (i != 1) {
                colModel.getColumn(i).cellRenderer = tooltipRenderer
            }
        }
        colModel.getColumn(0).preferredWidth = JBUI.scale(45)
        colModel.getColumn(0).maxWidth = JBUI.scale(45)
        colModel.getColumn(1).preferredWidth = JBUI.scale(50)
        colModel.getColumn(1).maxWidth = JBUI.scale(60)
        colModel.getColumn(1).cellRenderer = StatusCellRenderer()
        colModel.getColumn(2).preferredWidth = JBUI.scale(60)
        colModel.getColumn(3).preferredWidth = JBUI.scale(70)
        colModel.getColumn(3).maxWidth = JBUI.scale(90)
        colModel.getColumn(4).preferredWidth = JBUI.scale(40)
        colModel.getColumn(4).maxWidth = JBUI.scale(50)
        colModel.getColumn(5).preferredWidth = JBUI.scale(135)
        colModel.getColumn(6).preferredWidth = JBUI.scale(50)
        colModel.getColumn(6).maxWidth = JBUI.scale(60)
        colModel.getColumn(7).preferredWidth = JBUI.scale(80)
        val tableScroll = JBScrollPane(pipelineTable)

        val pipelineGroup = DefaultActionGroup().apply {
            add(refreshListAction)
            addSeparator()
            add(prevPageAction)
            add(nextPageAction)
        }
        val pipelineTb = ActionManager.getInstance()
            .createActionToolbar("GitLab.Pipeline.ListToolbar", pipelineGroup, true)
        pipelineTb.setReservePlaceAutoPopupIcon(false)
        pipelineTb.setTargetComponent(this)
        pipelineToolbar = pipelineTb
        val pipelineToolbarPanel = wrapToolbar(pipelineTb.component)
        pipelineToolbarPanel.add(pipelinePageLabel)

        val pipelinePanel = JPanel(BorderLayout())
        // 同 logPanel 的修复：Y_AXIS BoxLayout 在某些场景会把工具条撑高，BorderLayout 稳得多
        pipelineToolbarPanel.maximumSize = Dimension(Int.MAX_VALUE, pipelineToolbarPanel.preferredSize.height)
        tableScroll.minimumSize = Dimension(0, JBUI.scale(60))
        pipelinePanel.add(tableScroll, BorderLayout.CENTER)
        pipelinePanel.add(pipelineToolbarPanel, BorderLayout.SOUTH)

        // -------------------- 日志工具条（Job 选择 + 取消/重试/刷新）
        val logGroup = DefaultActionGroup().apply {
            add(cancelJobAction)
            add(retryJobAction)
            addSeparator()
            add(refreshLogAction)
        }
        val logTb = ActionManager.getInstance()
            .createActionToolbar("GitLab.Pipeline.LogToolbar", logGroup, true)
        logTb.setReservePlaceAutoPopupIcon(false)
        logTb.setTargetComponent(this)
        logToolbar = logTb

        // 顶部行：Job 标签 + 选择器 + 工具条 + 搜索框 + 匹配数标签
        val logTop = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        logTop.alignmentX = Component.LEFT_ALIGNMENT
        logTop.add(JBLabel("Job:"))
        jobSelector.addJobSelectionListener { onJobSelected() }
        logTop.add(jobSelector)
        logTop.add(logTb.component)

        // 关键：Y_AXIS BoxLayout 在 logViewer preferredSize 很小时（如空日志）会把多余空间分给
        // 其它"可扩展"组件，导致 logTop 被撑高；改用 BorderLayout 即可让 NORTH 工具条保持
        // preferredSize 高度、CENTER 日志区自动撑满。
        val logPanel = JPanel(BorderLayout())
        logViewer.alignmentX = Component.LEFT_ALIGNMENT
        // 锁死工具条最大高度 = 自身偏好高度，避免任何容器在某些极端情况下把它撑高
        logTop.maximumSize = Dimension(Int.MAX_VALUE, logTop.preferredSize.height)
        // 给日志区一个最小高度，避免被其他组件把空间吃光导致内容看不见
        logViewer.minimumSize = Dimension(0, JBUI.scale(60))
        logPanel.add(logTop, BorderLayout.NORTH)
        logPanel.add(logViewer, BorderLayout.CENTER)

        // -------------------- 分栏
        val sp = JSplitPane(JSplitPane.VERTICAL_SPLIT, pipelinePanel, logPanel).apply {
            resizeWeight = LIST_PROPORTION
            isContinuousLayout = true
            // 1px 主题色细线分隔条：符合"1px 细线"偏好
            dividerSize = JBUI.scale(1)
            background = JBColor.border()
        }
        split = sp
        pipelinePanel.minimumSize = Dimension(0, JBUI.scale(60))
        logPanel.minimumSize = Dimension(0, JBUI.scale(60))
        add(sp, BorderLayout.CENTER)

        // -------------------- 事件绑定
        projectSelector.addProjectSelectionListener { onProjectSelected(it) }
        // RefreshProjects 内部节流由 action 自己管理
        // 其他 action 直接由 ActionToolbar 绑定，无需手动 addActionListener
        pipelineTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) onPipelineSelected()
        }

        updateActionButtons()
        updatePageControls()
    }

    /**
     * 把 ActionToolbar 嵌入可换行 FlowLayout：窗口变窄时工具条不溢出，
     * 而是按行往下排。保留原版 [flowToolbar] 的"换行后正确报告高度"逻辑。
     */
    private fun wrapToolbar(toolbar: JComponent): JPanel {
        val p: JPanel = object : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {
            override fun getPreferredSize(): Dimension = wrappedPreferredSize()
            override fun getMinimumSize(): Dimension = preferredSize
            override fun getMaximumSize(): Dimension {
                val d = super.getMaximumSize()
                d.height = preferredSize.height
                return d
            }

            private fun wrappedPreferredSize(): Dimension {
                val single = super.getPreferredSize()
                val avail = availableWidth()
                val contentW = avail - insets.left - insets.right
                if (contentW <= 0 || contentW >= single.width) return single
                val fl = layout as FlowLayout
                val hgap = fl.hgap
                val vgap = fl.vgap
                var rowWidth = 0
                var rowHeight = 0
                var maxRowWidth = 0
                var totalHeight = 0
                for (i in 0 until componentCount) {
                    val c = getComponent(i)
                    if (!c.isVisible) continue
                    val d = c.preferredSize
                    if (rowWidth + d.width > contentW) {
                        maxRowWidth = maxOf(maxRowWidth, rowWidth)
                        if (totalHeight > 0) totalHeight += vgap
                        totalHeight += rowHeight
                        rowWidth = d.width
                        rowHeight = d.height
                    } else {
                        rowWidth += d.width + hgap
                        rowHeight = maxOf(rowHeight, d.height)
                    }
                }
                maxRowWidth = maxOf(maxRowWidth, rowWidth)
                if (totalHeight > 0) totalHeight += vgap
                totalHeight += rowHeight
                return Dimension(maxRowWidth + insets.left + insets.right, totalHeight + insets.top + insets.bottom)
            }

            private fun availableWidth(): Int {
                var p: java.awt.Container? = parent
                while (p != null) {
                    val pw = p.width
                    if (pw > 0) return pw
                    p = p.parent
                }
                return maxOf(width, 0)
            }
        }
        p.add(toolbar)
        return p
    }

    // ============================================================ 项目

    private fun refreshCurrentProjects(): ProjectEntry? {
        currentWindowProjects = GitRepositoryUtil.collectProjects()
        projectSelector.setCurrentWindowProjects(currentWindowProjects)
        return projectSelector.selectedProject
    }

    private fun refreshProjectsManually() {
        service().clearCache()
        refreshCurrentProjects()
        projectSelector.reload()
    }

    private fun onProjectSelected(entry: ProjectEntry) {
        if (disposed || entry == null) return
        currentProject = entry
        GitLabSettings.getInstance().lastProjectPath = entry.path
        if (GitLabSettings.getInstance().token.isEmpty()) {
            setStatus("未配置访问令牌，请点击【设置】填写 GitLab 访问令牌（需勾选 api 权限）。")
            return
        }
        refreshAll(entry, 1)
    }

    // ============================================================ 数据加载

    private fun service(): PipelineDataService = PipelineDataService.getInstance(ideaProject)

    private fun refreshAll(entry: ProjectEntry) = refreshAll(entry, pipelinePage)

    private fun refreshAll(entry: ProjectEntry, page: Int) {
        val gen = generation.incrementAndGet()
        val targetPage = maxOf(1, page)
        val keepPipelineId = selectedPipelineId
        val keepJobId = selectedJobId
        logViewer.showLoading("正在加载 ${entry.path} 的流水线…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "加载 ${entry.path}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    val snap = service().loadPage(entry.path, targetPage, keepPipelineId, keepJobId)
                    applyRefresh(
                        gen, entry, snap.projectId, snap.pipelines, targetPage, snap.hasNext,
                        snap.target, snap.jobs, snap.targetJobId, snap.trace
                    )
                } catch (t: Throwable) {
                    handleLoadError(gen, "加载失败: ${entry.path}", t)
                }
            }
        })
    }

    private fun applyRefresh(
        gen: Long, entry: ProjectEntry, projectId: Long, pipelines: List<PipelineInfo>,
        page: Int, hasNext: Boolean, target: PipelineInfo?, jobs: List<JobInfo>,
        targetJobId: Long, trace: String?
    ) {
        ApplicationManager.getApplication().invokeLater {
            resetLoadingFlags()
            if (gen != generation.get() || disposed) return@invokeLater
            // 不再在此提前隐藏 loading：由下面 setLog 真正渲染日志时隐藏，
            // 避免"日志还没显示出来 loading 就关了"的生硬感。
            refreshActions()
            currentProject = entry
            currentProjectId = projectId
            lastPipelines = pipelines
            pipelinePage = page
            pipelineHasNext = hasNext
            updatePageControls()

            uiUpdating = true
            try {
                pipelineModel.rowCount = 0
                for (p in pipelines) {
                    pipelineModel.addRow(
                        arrayOf(
                            "#${p.iid}", p.status, p.ref, shortSha(p.sha), p.source,
                            formatCreatedAt(p.createdAt), fmtDuration(p.durationSeconds), p.user
                        )
                    )
                }
                if (target != null) {
                    val row = findPipelineRow(target.id)
                    if (row >= 0) {
                        pipelineTable.selectionModel.setSelectionInterval(row, row)
                    }
                }
                jobSelector.setJobs(jobs)
                selectJobById(targetJobId)
                updateActionButtons()
            } finally {
                uiUpdating = false
            }

            if (trace != null) {
                logViewer.setLog(trace)
                // 记录增量拉取起点：下次刷新从该字节偏移只取新增部分
                traceJobId = targetJobId
                traceOffset = trace.toByteArray(StandardCharsets.UTF_8).size.toLong()
                traceCarry = ByteArray(0)
            } else if (jobs.isEmpty()) {
                logViewer.setLog("该流水线暂无 Job 或无法获取日志。")
            } else {
                // 有 Job 但未取到日志（异常路径，之后不会再有 setLog 渲染日志）：
                // 兜底隐藏加载占位，避免一直转圈
                logViewer.hideLoading()
            }
        }
    }

    private fun onPipelineSelected() {
        if (uiUpdating || disposed) return
        val row = pipelineTable.selectedRow
        val p = if (row >= 0 && row < lastPipelines.size) lastPipelines[row] else null
        selectedPipelineId = p?.id ?: -1
        updateActionButtons()
        if (p == null) return
        loadJobsAndTrace(p)
    }

    private fun loadJobsAndTrace(p: PipelineInfo) {
        val gen = generation.incrementAndGet()
        val keepJobId = selectedJobId
        logViewer.showLoading("正在加载流水线 #${p.iid} 的 Job 与日志…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "加载流水线 #${p.iid}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    val view = service().loadJobs(currentProjectId, p.id, keepJobId)
                    ApplicationManager.getApplication().invokeLater {
                        if (gen != generation.get() || disposed) return@invokeLater
                        uiUpdating = true
                        try {
                            jobSelector.setJobs(view.jobs)
                            selectJobById(view.targetJobId)
                            updateActionButtons()
                        } finally {
                            uiUpdating = false
                        }
                        val t = view.trace
                        if (t != null) {
                            logViewer.setLog(t)
                            // 记录增量拉取起点：下次刷新从该字节偏移只取新增部分
                            traceJobId = view.targetJobId
                            traceOffset = t.toByteArray(StandardCharsets.UTF_8).size.toLong()
                            traceCarry = ByteArray(0)
                        } else {
                            logViewer.setLog("（该流水线无 Job 或无法获取日志）")
                        }
                    }
                } catch (t: Throwable) {
                    handleLoadError(gen, "加载流水线 Job 失败", t)
                }
            }
        })
    }

    private fun onJobSelected() {
        if (uiUpdating || disposed) return
        val job = jobSelector.selectedJob
        selectedJobId = job?.id ?: -1
        updateActionButtons()
        if (job == null) return
        loadTrace(job)
    }

    private fun loadTrace(job: JobInfo) {
        val gen = generation.incrementAndGet()
        logViewer.showLoading("正在加载 ${job.name} 的日志…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "加载日志 ${job.name}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    // 切换 Job：从 0 全量拉取，并重置增量状态
                    val result: JobTraceResult = service().loadTraceChunk(currentProjectId, job.id, 0, ByteArray(0))
                    ApplicationManager.getApplication().invokeLater {
                        if (gen != generation.get() || disposed) return@invokeLater
                        traceJobId = job.id
                        traceOffset = result.nextOffset
                        traceCarry = result.carry
                        logViewer.setLog(result.content)
                    }
                } catch (t: Throwable) {
                    handleLoadError(gen, "加载日志失败: ${job.name}", t)
                }
            }
        })
    }

    private fun refreshTrace(silent: Boolean = false) {
        val job = jobSelector.selectedJob
        if (job == null) {
            if (!silent) Messages.showInfoMessage("请先选择要刷新日志的 Job。", "提示")
            refreshLogAction.complete()
            return
        }
        // 防叠加：上一次刷新尚未结束前跳过本次（请求已在跑，偏移推进后由下个周期继续）
        if (!refreshInFlight.compareAndSet(false, true)) {
            refreshLogAction.complete()
            return
        }
        // 注意：这里只读、不 +1 全局 generation。日志刷新只影响日志区，
        // 若也 +1 会把并发在途的其它请求（如「刷新列表」）误判为过期而整体丢弃，
        // 导致用户点刷新看不到结果。旧响应防护交给下方 traceOffset 校验。
        val gen = generation.get()
        val pid = currentProjectId
        val jobId = job.id
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "刷新日志 ${job.name}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    val fresh = service().loadJob(pid, jobId)
                    val freshPipeline: PipelineInfo? =
                        if (!PipelineStatus.isActive(fresh.status) && selectedPipelineId != -1L) {
                            try {
                                service().loadPipelineDetail(pid, selectedPipelineId)
                            } catch (_: Throwable) {
                                null
                            }
                        } else null
                    // 增量拉取：只取上次偏移之后的新增内容，追加到控制台
                    //（不重建控制台、不打断滚动与阅读位置，呈现"运行日志不断追加"的效果）
                    val offset = if (traceJobId == jobId) traceOffset else 0L
                    val carry = if (traceJobId == jobId) traceCarry else ByteArray(0)
                    val result: JobTraceResult = service().loadTraceChunk(pid, jobId, offset, carry)
                    ApplicationManager.getApplication().invokeLater {
                        resetLoadingFlags()
                        if (gen != generation.get() || disposed) return@invokeLater
                        // 增量（206）结果在请求期间若日志被整体重建（刷新列表/切换 Job 会 setLog
                        // 并重置 traceOffset），本次基于旧偏移拉到的内容已包含在重建后的日志里，
                        // 丢弃避免重复追加；全量（200）结果本身是完整替换，直接应用即可。
                        if (!result.full && (traceJobId != jobId || traceOffset != offset)) {
                            return@invokeLater
                        }
                        traceJobId = jobId
                        traceOffset = result.nextOffset
                        traceCarry = result.carry
                        if (result.full) {
                            logViewer.setLog(result.content)
                        } else {
                            logViewer.appendLog(result.content)
                        }
                        val cur = jobSelector.selectedJob
                        if (cur != null && cur.id == fresh.id) {
                            // 无条件同步同一 Job 的最新实体（含最新状态）：任务完成后立即把缓存态
                            // 置为非 active，autoRefreshTimer 下个周期即据此停止轮询，避免"已完成还在取日志"
                            jobSelector.replaceSelectedJob(fresh)
                        }
                        if (freshPipeline != null) {
                            updatePipelineStatus(freshPipeline)
                        }
                        updateActionButtons()
                    }
                } catch (t: Throwable) {
                    if (t is InterruptedException) {
                        // 用户主动取消后台任务：不是错误，静默返回（traceOffset 未推进，无副作用）
                        return@run
                    }
                    if (silent) {
                        // 自动刷新失败不打扰用户：仅记日志，traceOffset 未推进，下个周期从同一偏移重试
                        log.warn("自动刷新日志失败（下个周期自动重试）: ${job.name}", t)
                    } else {
                        handleActionError(gen, "刷新日志失败", t)
                    }
                } finally {
                    refreshInFlight.set(false)
                }
            }
        })
    }

    private fun updatePipelineStatus(fresh: PipelineInfo) {
        val row = findPipelineRow(fresh.id)
        if (row < 0) return
        val old = lastPipelines[row]
        if (old.status == fresh.status) return
        lastPipelines = lastPipelines.toMutableList().also { it[row] = fresh }
        pipelineModel.setValueAt(fresh.status, row, 1)
    }

    private fun selectJobById(jobId: Long) = jobSelector.selectJobById(jobId)

    // ============================================================ 触发 / 取消 / 重试

    private fun triggerPipeline() {
        if (currentProject == null || currentProjectId == -1L) {
            Messages.showWarningDialog("请先选择一个项目。", "提示")
            triggerAction.complete()
            return
        }
        if (GitLabSettings.getInstance().token.isEmpty()) {
            Messages.showWarningDialog("请先在【设置】中配置访问令牌。", "提示")
            triggerAction.complete()
            return
        }
        val gen = generation.incrementAndGet()
        val entry = currentProject!!
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "加载分支列表", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                val branches = try {
                    service().loadBranches(currentProjectId)
                } catch (t: Throwable) {
                    log.warn("加载分支列表失败", t)
                    emptyList()
                }
                ApplicationManager.getApplication().invokeLater {
                    if (gen != generation.get() || disposed) return@invokeLater
                    showTriggerDialog(entry, branches)
                }
            }
        })
    }

    private fun showTriggerDialog(entry: ProjectEntry, branches: List<String>) {
        val dlg = TriggerPipelineDialog(ideaProject, currentPipelineRef(), branches)
        if (!dlg.showAndGet()) {
            triggerAction.complete()
            return
        }
        val gen = generation.incrementAndGet()
        val ref = dlg.ref
        val variables = dlg.variables
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "新建流水线", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    service().triggerPipeline(currentProjectId, ref, variables)
                    ApplicationManager.getApplication().invokeLater {
                        resetLoadingFlags()
                        if (gen != generation.get() || disposed) return@invokeLater
                        setStatus("已新建流水线（ref=$ref）")
                        refreshAll(entry, 1)
                    }
                } catch (t: GitLabApiException) {
                    val hint = "新建流水线失败（ref=$ref）："
                    if (t.statusCode == 404) {
                        handleActionError(gen, "$hint 找不到该分支/标签，或该 ref 下没有 .gitlab-ci.yml 配置", t)
                    } else {
                        handleActionError(gen, hint, t)
                    }
                } catch (t: Throwable) {
                    handleActionError(gen, "新建流水线失败（ref=$ref）", t)
                }
            }
        })
    }

    private fun cancelSelectedPipeline() {
        val row = pipelineTable.selectedRow
        if (row < 0 || row >= lastPipelines.size) {
            Messages.showWarningDialog("请先在列表中选择要取消的流水线。", "提示")
            cancelPipelineAction.complete()
            return
        }
        val p = lastPipelines[row]
        if (!PipelineStatus.isActive(p.status)) {
            Messages.showInfoMessage("只有运行中/等待中的流水线可以取消。", "提示")
            cancelPipelineAction.complete()
            return
        }
        val answer = Messages.showYesNoDialog(
            "确认取消流水线 #${p.iid}（${p.ref}）？", "取消流水线", Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) {
            cancelPipelineAction.complete()
            return
        }
        val gen = generation.incrementAndGet()
        val entry = currentProject
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "取消流水线 #${p.iid}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    service().cancelPipeline(currentProjectId, p.id)
                    ApplicationManager.getApplication().invokeLater {
                        resetLoadingFlags()
                        if (gen != generation.get() || disposed) return@invokeLater
                        setStatus("已取消流水线 #${p.iid}")
                        if (entry != null) refreshAll(entry)
                    }
                } catch (t: Throwable) {
                    handleActionError(gen, "取消流水线失败", t)
                }
            }
        })
    }

    private fun cancelSelectedJob() {
        val job = jobSelector.selectedJob
        if (job == null) {
            Messages.showWarningDialog("请先选择要取消的 Job。", "提示")
            cancelJobAction.complete()
            return
        }
        if (!PipelineStatus.isActive(job.status)) {
            Messages.showInfoMessage("只有运行中/等待中的 Job 可以取消。", "提示")
            cancelJobAction.complete()
            return
        }
        val answer = Messages.showYesNoDialog("确认取消 Job ${job.name}？", "取消Job", Messages.getQuestionIcon())
        if (answer != Messages.YES) {
            cancelJobAction.complete()
            return
        }
        val gen = generation.incrementAndGet()
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "取消 Job ${job.name}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    service().cancelJob(currentProjectId, job.id)
                    ApplicationManager.getApplication().invokeLater {
                        resetLoadingFlags()
                        if (gen != generation.get() || disposed) return@invokeLater
                        setStatus("已取消 Job ${job.name}")
                        currentProject?.let { refreshAll(it) }
                    }
                } catch (t: Throwable) {
                    handleActionError(gen, "取消 Job 失败", t)
                }
            }
        })
    }

    private fun openSettings() {
        val dlg = SettingsDialog(ideaProject)
        if (dlg.showAndGet()) {
            restartAutoRefreshTimer()
            val cur = currentProject
            refreshCurrentProjects()
            if (cur != null && GitLabSettings.getInstance().token.isNotEmpty()) {
                refreshAll(cur)
            }
        }
    }

    private fun restartAutoRefreshTimer() {
        if (disposed) return
        val interval = maxOf(5, GitLabSettings.getInstance().refreshIntervalSeconds)
        if (autoRefreshTimer.delay != interval * 1000) {
            autoRefreshTimer.delay = interval * 1000
            autoRefreshTimer.restart()
        }
    }

    private fun retrySelectedPipeline() {
        val p = selectedPipeline()
        if (p == null) {
            Messages.showWarningDialog("请先在列表中选择要重试的流水线。", "提示")
            retryPipelineAction.complete()
            return
        }
        if (!PipelineStatus.isRetryablePipeline(p.status)) {
            Messages.showInfoMessage("只有失败/已取消的流水线可以重试。", "提示")
            retryPipelineAction.complete()
            return
        }
        val answer = Messages.showYesNoDialog(
            "确认重试流水线 #${p.iid}（${p.ref}）？", "重试流水线", Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) {
            retryPipelineAction.complete()
            return
        }
        val gen = generation.incrementAndGet()
        val entry = currentProject
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "重试流水线 #${p.iid}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    service().retryPipeline(currentProjectId, p.id)
                    ApplicationManager.getApplication().invokeLater {
                        resetLoadingFlags()
                        if (gen != generation.get() || disposed) return@invokeLater
                        setStatus("已重试流水线 #${p.iid}")
                        if (entry != null) refreshAll(entry)
                    }
                } catch (t: Throwable) {
                    handleActionError(gen, "重试流水线失败", t)
                }
            }
        })
    }

    private fun retrySelectedJob() {
        val job = jobSelector.selectedJob
        if (job == null) {
            Messages.showWarningDialog("请先选择要执行/重试的 Job。", "提示")
            retryJobAction.complete()
            return
        }
        if (!PipelineStatus.isRetryableJob(job.status)) {
            Messages.showInfoMessage("只有手动（含手动已跳过）/失败/已取消的 Job 可以执行或重试。", "提示")
            retryJobAction.complete()
            return
        }
        val s = PipelineStatus.of(job.status)
        val play = s == PipelineStatus.MANUAL || s == PipelineStatus.SKIPPED
        val action = if (play) "执行" else "重试"
        val answer = Messages.showYesNoDialog(
            "确认$action Job ${job.name}？", "$action Job", Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) {
            retryJobAction.complete()
            return
        }
        val gen = generation.incrementAndGet()
        val isManual = play
        ProgressManager.getInstance().run(object : Task.Backgroundable(ideaProject, "$action Job ${job.name}", true) {
            override fun run(@NotNull indicator: ProgressIndicator) {
                try {
                    if (isManual) service().playJob(currentProjectId, job.id)
                    else service().retryJob(currentProjectId, job.id)
                    ApplicationManager.getApplication().invokeLater {
                        resetLoadingFlags()
                        if (gen != generation.get() || disposed) return@invokeLater
                        setStatus("已$action Job ${job.name}")
                        currentProject?.let { refreshAll(it) }
                    }
                } catch (t: Throwable) {
                    handleActionError(gen, "$action Job 失败", t)
                }
            }
        })
    }

    // ============================================================ 项目自动检测

    private fun startProjectDetection() {
        val attempts = intArrayOf(0)
        var timer: Timer? = null
        val listener = java.awt.event.ActionListener {
            // 通过外层可空变量持有引用（Timer lambda 内不能引用正在声明的 val）
            val t = timer ?: return@ActionListener
            if (disposed) {
                t.stop(); return@ActionListener
            }
            if (currentProject != null) {
                t.stop(); return@ActionListener
            }
            val entry = refreshCurrentProjects()
            if (entry != null && GitLabSettings.getInstance().token.isNotEmpty()) {
                t.stop()
                onProjectSelected(entry)
            } else if (++attempts[0] >= 15) {
                t.stop()
            }
        }
        val t = Timer(2000, listener)
        timer = t
        detectTimer = t
        t.start()
    }

    // ============================================================ 工具方法

    private fun handleLoadError(gen: Long, message: String, t: Throwable) {
        log.warn(message, t)
        ApplicationManager.getApplication().invokeLater {
            resetLoadingFlags()
            if (gen != generation.get() || disposed) return@invokeLater
            logViewer.hideLoading()
            refreshActions()
            setError("$message：${if (t is GitLabApiException) t.message else t}")
        }
    }

    private fun handleActionError(gen: Long, message: String, t: Throwable) {
        log.warn(message, t)
        ApplicationManager.getApplication().invokeLater {
            resetLoadingFlags()
            if (gen != generation.get() || disposed) return@invokeLater
            logViewer.hideLoading()
            refreshActions()
            setError("$message：${t.message}")
            Messages.showErrorDialog("$message：${t.message}", "错误")
        }
    }

    private fun setStatus(text: String) = notify(text, NotificationType.INFORMATION)

    private fun setError(text: String) = notify(text, NotificationType.ERROR)

    private fun notify(text: String, type: NotificationType) {
        NotificationService.notify(ideaProject, text, type)
    }

    private fun currentPipelineRef(): String {
        val row = pipelineTable.selectedRow
        if (row >= 0 && row < lastPipelines.size) {
            return lastPipelines[row].ref
        }
        return "main"
    }

    private fun findPipelineRow(pipelineId: Long): Int =
        lastPipelines.indexOfFirst { it.id == pipelineId }

    private fun selectedPipeline(): PipelineInfo? {
        val row = pipelineTable.selectedRow
        return if (row >= 0 && row < lastPipelines.size) lastPipelines[row] else null
    }

    private fun selectedJob(): JobInfo? = jobSelector.selectedJob

    /**
     * 刷新所有 action 的 enabled 状态（与原版 `updateActionButtons` 同等语义）。
     * 改名是因为现在它触发的是 ActionToolbar 的整体刷新而非按钮置灰。
     */
    private fun updateActionButtons() {
        cancelPipelineAction.refreshEnabled()
        retryPipelineAction.refreshEnabled()
        cancelJobAction.refreshEnabled()
        retryJobAction.refreshEnabled()
        refreshLogAction.refreshEnabled()
    }

    private fun updatePageControls() {
        pipelinePageLabel.text = "第 $pipelinePage 页"
        prevPageAction.refreshEnabled()
        nextPageAction.refreshEnabled()
    }

    /**
     * 让所有 ActionToolbar 重新查询其内 action 的 presentation。
     * 调用时机：异步任务完成、状态变化、用户操作导致 enabled 改变时。
     */
    private fun refreshActions() {
        if (::topToolbar.isInitialized) topToolbar.updateActionsImmediately()
        if (::pipelineToolbar.isInitialized) pipelineToolbar.updateActionsImmediately()
        if (::logToolbar.isInitialized) logToolbar.updateActionsImmediately()
        updatePageControls()
    }

    /**
     * 复位所有可能因异步任务 / 同步弹窗而残留的 loading 标志。
     * 任何后台任务结束（成功 / 失败 / 被更新的请求覆盖）都必须复位，
     * 否则对应按钮会一直是禁用（置灰）状态，导致无法再次点击。
     * 注意：isLoading 置回 false 的 setter 当值未变化时不触发额外刷新，开销可忽略。
     */
    private fun resetLoadingFlags() {
        refreshProjectsAction.complete()
        refreshListAction.complete()
        prevPageAction.complete()
        nextPageAction.complete()
        triggerAction.complete()
        cancelPipelineAction.complete()
        retryPipelineAction.complete()
        cancelJobAction.complete()
        retryJobAction.complete()
        refreshLogAction.complete()
        settingsAction.complete()
    }

    // ============================================================ 停靠 / 分栏

    private fun syncOrientationByAspect() {
        val sp = split ?: return
        if (disposed) return
        val w = sp.width
        val h = sp.height
        if (w <= 0 || h <= 0) return
        val portrait = h > w
        val target = if (portrait) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
        if (sp.orientation != target) sp.orientation = target
    }

    private fun applyDefaultDockSize() {
        if (disposed || toolWindow == null || toolWindow.isDisposed) return
        val anchor = toolWindow.anchor
        if (anchor == preferredAnchor) return
        preferredAnchor = anchor
        val sideDocked = anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.RIGHT
        preferredSize = if (sideDocked) {
            Dimension(SIDE_DEF_WIDTH, preferredSize.height)
        } else {
            Dimension(preferredSize.width, BOTTOM_DEF_HEIGHT)
        }
    }

    private fun applyDividerProportion() {
        split?.setDividerLocation(LIST_PROPORTION)
    }

    // ============================================================ 格式化

    private fun formatCreatedAt(iso: String?): String {
        if (iso.isNullOrEmpty()) return ""
        return try {
            OffsetDateTime.parse(iso)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(CREATED_AT_OUT)
        } catch (_: Throwable) {
            iso
        }
    }

    private fun shortSha(sha: String?): String =
        if (sha != null && sha.length > 8) sha.substring(0, 8) else sha ?: ""

    private fun fmtDuration(seconds: Long): String {
        if (seconds <= 0) return "-"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m${s}s" else "${s}s"
    }

    // ============================================================ 表格渲染器

    /** 状态列：中文文案 + 主题感知颜色 */
    private class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val status = value?.toString() ?: ""
            text = PipelineStatus.display(status)
            toolTipText = PipelineStatus.display(status)
            if (!isSelected) {
                foreground = PipelineStatus.displayColor(status)
            }
            return c
        }
    }

    /** 通用悬浮提示：单元格被截断时悬停看完整文字 */
    private class TooltipCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            toolTipText = value?.toString() ?: ""
            return c
        }
    }

    // ============================================================ AnAction 基类

    /**
     * 主面板内所有 action 的基类：
     * - 继承 [AnAction] + [DumbAware]：IDE 索引/重构期间也能用
     * - 维护 [isLoading] 标志位：网络请求期间自动 disable（替代原 loadGateButtons 手写门控）
     * - 暴露 [refreshEnabled] 供外部在状态变化时触发 update()
     * - 内置 [run] 入口：被定时器等"非用户点击"场景直接触发（不经过 ActionEvent）
     * - [complete] 在异步任务结束（成功/失败/取消）时调用，恢复 enabled
     *
     * 注意：所有 action 都通过 update() 决定 enabled；isLoading 仅在异步过程中置 true。
     */
    private abstract inner class GitLabAction(
        text: String,
        description: String? = null,
        icon: javax.swing.Icon? = null
    ) : AnAction(text, description, icon), DumbAware {

        /** 按钮静态图标：loading 结束后恢复用 */
        private val defaultIcon: javax.swing.Icon? = icon

        /**
         * 网络请求期间是否用旋转 loading 图标替换按钮图标（对应前端 loading 效果）。
         * 本地瞬时操作（弹窗、日志翻页）置 false，避免图标一闪而过显得卡顿。
         */
        protected open val loadingSpinnerEnabled: Boolean = true

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        var isLoading: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                refreshEnabled()
            }

        final override fun update(e: AnActionEvent) {
            val loading = isLoading
            e.presentation.isEnabled = !loading && computeEnabled()
            e.presentation.icon = if (loading && loadingSpinnerEnabled && defaultIcon != null) {
                AnimatedIcon.Default.INSTANCE
            } else {
                defaultIcon
            }
        }

        final override fun actionPerformed(e: AnActionEvent) {
            if (isLoading) return
            run()
        }

        /**
         * 供内部 / 定时器等"非 ActionEvent 触发"场景直接调用。
         * 默认行为：标记 loading 后调用 [doPerform]。
         * 子类如需要在 loading 期间仍可手动重入可重写。
         */
        open fun run() {
            if (isLoading) return
            isLoading = true
            try {
                doPerform()
            } catch (t: Throwable) {
                isLoading = false
                throw t
            }
        }

        /**
         * 异步任务结束（成功/失败/取消）时调用，恢复按钮可用。
         * ActionToolbar 自身的 updateActions 也会被 refreshActions 触发。
         */
        fun complete() {
            isLoading = false
        }

        /** 当前是否可点击（除去 loading 状态外） */
        protected abstract fun computeEnabled(): Boolean

        /** 实际执行逻辑（异步开始时调用） */
        protected abstract fun doPerform()

        /** 主动触发 update，让 presentation 重新计算 enabled */
        fun refreshEnabled() {
            if (this@GitLabPipelinePanel::topToolbar.isInitialized) this@GitLabPipelinePanel.topToolbar.updateActionsImmediately()
            if (this@GitLabPipelinePanel::pipelineToolbar.isInitialized) this@GitLabPipelinePanel.pipelineToolbar.updateActionsImmediately()
            if (this@GitLabPipelinePanel::logToolbar.isInitialized) this@GitLabPipelinePanel.logToolbar.updateActionsImmediately()
        }
    }

    // ============================================================ 13 个具体 Action

    private inner class RefreshProjectsAction :
        GitLabAction("刷新项目", "重新加载 GitLab 项目组树", AllIcons.Actions.Refresh) {
        override fun computeEnabled(): Boolean = !isLoading
        override fun doPerform() {
            refreshProjectsManually()
        }

        override fun run() {
            if (isLoading) return
            super.run()       // 置 isLoading=true 并同步执行 doPerform
            complete()        // doPerform 为同步操作，执行完立即恢复按钮可用
        }
    }

    private inner class RefreshListAction :
        GitLabAction("刷新列表", "重新加载当前项目的流水线", AllIcons.Actions.Refresh) {
        override fun computeEnabled(): Boolean {
            val cur = currentProject ?: return false
            return GitLabSettings.getInstance().token.isNotEmpty()
        }

        override fun doPerform() {
            val cur = currentProject
            if (cur == null || GitLabSettings.getInstance().token.isEmpty()) {
                complete()
                return
            }
            refreshAll(cur)
            // refreshAll 走 generation + invokeLater 回调；此处 complete 由回调统一处理
        }
    }

    private inner class PrevPageAction :
        GitLabAction("上一页", null, AllIcons.General.ArrowLeft) {
        override fun computeEnabled(): Boolean = !isLoading && pipelinePage > 1
        override fun doPerform() {
            val cur = currentProject
            if (cur != null) refreshAll(cur, pipelinePage - 1)
        }
    }

    private inner class NextPageAction :
        GitLabAction("下一页", null, AllIcons.General.ArrowRight) {
        override fun computeEnabled(): Boolean = !isLoading && pipelineHasNext
        override fun doPerform() {
            val cur = currentProject
            if (cur != null) refreshAll(cur, pipelinePage + 1)
        }
    }

    private inner class TriggerPipelineAction :
        GitLabAction("新建流水线", "新建一条流水线", AllIcons.General.Add) {
        override fun computeEnabled(): Boolean {
            val cur = currentProject ?: return false
            return GitLabSettings.getInstance().token.isNotEmpty()
        }

        override fun doPerform() = triggerPipeline()
    }

    private inner class CancelPipelineAction :
        GitLabAction("取消流水线", "取消选中的运行中流水线", AllIcons.Actions.Cancel) {
        override fun computeEnabled(): Boolean {
            val p = selectedPipeline() ?: return false
            return PipelineStatus.isActive(p.status)
        }

        override fun doPerform() = cancelSelectedPipeline()
    }

    private inner class RetryPipelineAction :
        GitLabAction("重试流水线", "重试失败/已取消的流水线", AllIcons.Actions.Restart) {
        override fun computeEnabled(): Boolean {
            val p = selectedPipeline() ?: return false
            return PipelineStatus.isRetryablePipeline(p.status)
        }

        override fun doPerform() = retrySelectedPipeline()
    }

    private inner class CancelJobAction :
        GitLabAction("取消Job", "取消选中的运行中 Job", AllIcons.Actions.Cancel) {
        override fun computeEnabled(): Boolean {
            val j = jobSelector.selectedJob ?: return false
            return PipelineStatus.isActive(j.status)
        }

        override fun doPerform() = cancelSelectedJob()
    }

    private inner class RetryJobAction :
        GitLabAction("执行/重试", "执行手动 Job 或重试失败 Job", AllIcons.Actions.Restart) {
        override fun computeEnabled(): Boolean {
            val j = jobSelector.selectedJob ?: return false
            return PipelineStatus.isRetryableJob(j.status)
        }

        override fun doPerform() = retrySelectedJob()
    }

    private inner class RefreshLogAction :
        GitLabAction("刷新日志", "重新加载当前 Job 的日志", AllIcons.Actions.Refresh) {
        override fun computeEnabled(): Boolean = !isLoading && jobSelector.selectedJob != null
        override fun doPerform() = refreshTrace()
    }

    private inner class SettingsAction :
        GitLabAction("设置", "配置 GitLab 地址、令牌和刷新参数", AllIcons.General.Settings) {
        override val loadingSpinnerEnabled: Boolean = false
        override fun computeEnabled(): Boolean = !isLoading
        override fun doPerform() = openSettings()
        override fun run() {
            if (isLoading) return
            super.run()       // 置 isLoading=true 并同步打开模态弹窗
            complete()        // 弹窗为同步模态，关闭后立即恢复按钮可用
        }
    }

    // ============================================================ Disposable

    override fun dispose() {
        disposed = true
        generation.incrementAndGet()
        autoRefreshTimer.stop()
        layoutTimer.stop()
        detectTimer?.stop()
        logViewer.dispose()
    }

    companion object {
        private const val SIDE_DEF_WIDTH = 190
        private const val BOTTOM_DEF_HEIGHT = 190
        private const val LIST_PROPORTION = 0.35
        private val CREATED_AT_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
