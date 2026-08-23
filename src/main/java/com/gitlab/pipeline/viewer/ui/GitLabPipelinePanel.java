package com.gitlab.pipeline.viewer.ui;

import com.gitlab.pipeline.viewer.model.*;
import com.gitlab.pipeline.viewer.services.GitLabApiException;
import com.gitlab.pipeline.viewer.services.GitRepositoryUtil;
import com.gitlab.pipeline.viewer.services.NotificationService;
import com.gitlab.pipeline.viewer.services.PipelineDataService;
import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 主面板：项目下拉 -> 流水线表格 -> Job 选择 -> 可搜索日志。
 * 相当于 Spring Boot 里的「一个 Controller + 一个主页面视图」：
 * 控件（JComboBox/JTable/按钮）就是页面元素，点击事件就是接口请求。
 * <p>
 * —— IDEA 插件开发必懂的几个概念（对标 Spring Boot）——
 * 1. EDT（Event Dispatch Thread）：Swing 的 UI 主线程，相当于 Spring 里的主线程。
 * 所有控件操作（改文本、加行、弹窗）必须在 EDT 上做，否则会卡界面或报错。
 * 2. Task.Backgroundable：后台任务，相当于 Spring 的 @Async / CompletableFuture。
 * service().xxx() 这些网络请求（经 {@link PipelineDataService} 编排）都必须放这里，避免阻塞 EDT。
 * 3. invokeLater(...)：把一段代码交回 EDT 执行，相当于异步回调里
 * 「回到主线程更新 UI」，和 @Async 之后在主线程刷新页面同理。
 * 4. generation（版本号/乐观锁）：每发起一次加载就 +1；异步结果回来时若版本号已变，
 * 说明用户已经切走了，直接丢弃这次结果 —— 解决「快速切换导致的竞态」。
 * 5. Disposable：生命周期管理，等价于 Spring 的 @PreDestroy，窗口关闭时清理定时器等资源。
 */
public class GitLabPipelinePanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(GitLabPipelinePanel.class);

    private final Project ideaProject;
    private final ToolWindow toolWindow;
    /**
     * 请求版本号（乐观锁）：每次加载 +1，过期结果直接丢弃
     */
    private final AtomicLong generation = new AtomicLong(0);
    /**
     * 自动刷新定时器：仅当选中运行中的流水线时，自动刷新所选 Job 的日志（列表不自动刷新）
     */
    private final Timer autoRefreshTimer;
    /**
     * 停靠位置检测定时器：侧边/底部切换时自动调整分栏方向
     */
    private final Timer layoutTimer;
    /**
     * 已按停靠方向设置过首选尺寸的锚点：避免每次都覆盖用户手动调整后的尺寸
     */
    private ToolWindowAnchor preferredAnchor;
    /**
     * 默认停靠尺寸：侧边停靠默认宽度、底部/顶部停靠默认高度（即「贴边方向」的尺寸）。
     * IDE 用面板 preferredSize 计算工具窗口默认停靠大小，故按方向设置以缩短默认占用
     */
    private static final int SIDE_DEF_WIDTH = 190;
    private static final int BOTTOM_DEF_HEIGHT = 190;
    /**
     * 分栏中「流水表」所占比例（剩余给日志区）。0.35 = 日志默认更大
     */
    private static final double LIST_PROPORTION = 0.35;
    /**
     * 首次打开时 git4idea 可能尚未映射仓库，用定时器重试检测当前项目
     */
    private Timer detectTimer;
    /**
     * 刷新/导航按钮集合：点击置灰进入 loading，数据返回后统一恢复，防止连点导致重复请求
     */
    private final Set<Component> loadGateButtons = new HashSet<>();
    /**
     * 本地导航按钮的时间节流记录：key=按钮, value=上次触发毫秒
     */
    private final Map<Component, Long> throttleAt = new HashMap<>();

    /**
     * 项目树形选择：单个下拉，含「当前项目」与 GitLab 项目组，逐级懒加载 + 已加载项目模糊搜索
     */
    private final ProjectTreeSelector projectSelector;
    private final JButton refreshProjectsButton = new JButton("刷新项目");
    private final JButton refreshListButton = new JButton("刷新列表");
    private final JButton triggerButton = new JButton("触发流水线");
    private final JButton cancelButton = new JButton("取消流水线");
    private final JButton retryPipelineButton = new JButton("重试流水线");
    private final JButton settingsButton = new JButton("设置");
    // 上一页/下一页/上一处/下一处：扁平图标按钮，依赖 IDE 自带 LAF 的悬停高亮，不用内部 API，保证跨版本可编译
    private final JButton pipelinePrevButton = iconOnlyButton(AllIcons.General.ArrowLeft, "上一页");
    private final JButton pipelineNextButton = iconOnlyButton(AllIcons.General.ArrowRight, "下一页");
    private final JLabel pipelinePageLabel = new JLabel("第 1 页");
    private final JButton refreshLogButton = new JButton("刷新");

    private final DefaultTableModel pipelineModel = new DefaultTableModel(
            new String[]{"流水线", "状态", "Ref", "SHA", "来源", "创建时间", "耗时", "触发人"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable pipelineTable = new JTable(pipelineModel);

    private final JComboBox<JobInfo> jobCombo = new JComboBox<>();
    private final JButton cancelJobButton = new JButton("取消Job");
    private final JButton retryJobButton = new JButton("执行/重试");
    private final SearchTextField searchField = new SearchTextField();
    private final JButton prevButton = iconOnlyButton(AllIcons.General.ArrowUp, "上一处");
    private final JButton nextButton = iconOnlyButton(AllIcons.General.ArrowDown, "下一处");
    private final JLabel matchLabel = new JLabel("");
    private final LogViewer logViewer = new LogViewer();
    /**
     * 上下/左右分栏，根据工具窗口停靠位置动态切换
     */
    private JSplitPane split;

    private volatile ProjectEntry currentProject;
    private volatile long currentProjectId = -1;
    private volatile long selectedPipelineId = -1;
    private volatile long selectedJobId = -1;
    private volatile List<PipelineInfo> lastPipelines = new ArrayList<>();
    /**
     * 当前窗口（含附加项目）检测到的 GitLab 项目
     */
    private volatile List<ProjectEntry> currentWindowProjects = new ArrayList<>();
    /**
     * 当前流水线分页页码（从 1 开始）与是否还有下一页
     */
    private volatile int pipelinePage = 1;
    private volatile boolean pipelineHasNext = false;
    private volatile boolean disposed = false;
    private boolean uiUpdating = false;

    public GitLabPipelinePanel(@NotNull Project project, ToolWindow toolWindow) {
        super(new BorderLayout());
        this.ideaProject = project;
        this.toolWindow = toolWindow;
        this.projectSelector = new ProjectTreeSelector(project);
        buildUi();
        // 面板最小尺寸：侧边停靠时窗口不至于被压到控件重叠/不可用
        setMinimumSize(new Dimension(JBUI.scale(340), JBUI.scale(280)));
        ProjectEntry initial = refreshCurrentProjects();
        if (initial != null && !GitLabSettings.getInstance().getToken().isEmpty()) {
            onProjectSelected(initial);
        }
        startProjectDetection();

        int interval = Math.max(5, GitLabSettings.getInstance().getRefreshIntervalSeconds());
        autoRefreshTimer = new Timer(interval * 1000, e -> {
            if (disposed) {
                return;
            }
            GitLabSettings s = GitLabSettings.getInstance();
            if (!s.isAutoRefresh() || currentProject == null || currentProjectId == -1
                    || s.getToken().isEmpty()) {
                return;
            }
            // 自动刷新只刷新日志，且仅在选中运行中的流水线时才生效；列表一律手动/被动刷新
            PipelineInfo p = getSelectedPipeline();
            if (p == null || !PipelineStatus.isActive(p.status)) {
                return;
            }
            JobInfo job = getSelectedJob();
            if (job != null) {
                refreshTrace();
            }
        });
        autoRefreshTimer.start();

        // 布局适配：停靠在侧边时上下分栏、底部/顶部时左右分栏。
        // 不用慢轮询，而是监听自身尺寸变化（停靠切换必然触发本面板 resize）——在 resize
        // 回调里同步翻转分栏方向，避免"先按旧方向拉伸、再切换"的两段式顿挫感。
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (disposed || split == null || split.isShowing()) {
                    syncOrientationByAspect();
                    applyDividerProportion();
                }
            }
        });
        // 兜底轮询：某些场景（如首次创建、外部拖动）可能不触发本面板 resize，交给定时器补一次
        layoutTimer = new Timer(400, ev -> {
            applyDefaultDockSize();
            syncOrientationByAspect();
            applyDividerProportion();
        });
        layoutTimer.start();
        applyDefaultDockSize();
        syncOrientationByAspect();
        applyDividerProportion();
    }

    // ---------------------------------------------------------------- UI 构建

    /**
     * 首次打开时 git4idea 可能尚未异步映射仓库（collectProjects 为空），
     * 用定时器轮询重试：识别到当前项目后自动加载流水线并停止，最多重试约 30 秒。
     */
    private void startProjectDetection() {
        final int[] attempts = {0};
        detectTimer = new Timer(2000, e -> {
            if (disposed) {
                detectTimer.stop();
                return;
            }
            if (currentProject != null) {
                detectTimer.stop();
                return;
            }
            ProjectEntry entry = refreshCurrentProjects();
            if (entry != null && !GitLabSettings.getInstance().getToken().isEmpty()) {
                detectTimer.stop();
                onProjectSelected(entry);
            } else if (++attempts[0] >= 15) {
                detectTimer.stop();
            }
        });
        detectTimer.start();
    }

    private void buildUi() {
        // 顶部工具条用可换行的 FlowLayout：窗口变窄时按钮自动换行，避免重叠/被遮挡
        JPanel top = flowToolbar();
        top.add(new JBLabel("项目:"));
        projectSelector.setPreferredSize(new Dimension(JBUI.scale(300), JBUI.scale(26)));
        top.add(projectSelector);
        top.add(refreshProjectsButton);
        top.add(triggerButton);
        top.add(cancelButton);
        top.add(retryPipelineButton);
        top.add(settingsButton);
        add(top, BorderLayout.NORTH);

        pipelineTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pipelineTable.getTableHeader().setReorderingAllowed(false);
        pipelineTable.setRowHeight(JBUI.scale(22));
        // 列宽固定，窗口过窄时表格横向滚动，而不是把列挤压变形
        pipelineTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        // 各列按信息量分配不同宽度：流水线/来源/耗时较窄，Ref/SHA/创建时间/触发人更宽
        TableColumnModel colModel = pipelineTable.getColumnModel();
        // 鼠标悬浮显示完整内容提示（类似网页 title），列内文字被截断时也能看到全文
        TooltipCellRenderer tooltipRenderer = new TooltipCellRenderer();
        for (int i = 0; i < colModel.getColumnCount(); i++) {
            if (i != 1) { // 状态列保留专用着色渲染
                colModel.getColumn(i).setCellRenderer(tooltipRenderer);
            }
        }
        colModel.getColumn(0).setPreferredWidth(JBUI.scale(45));   // 流水线 #xx
        colModel.getColumn(0).setMaxWidth(JBUI.scale(45));
        colModel.getColumn(1).setPreferredWidth(JBUI.scale(50));   // 状态
        colModel.getColumn(1).setMaxWidth(JBUI.scale(60));
        colModel.getColumn(1).setCellRenderer(new StatusCellRenderer());
        colModel.getColumn(2).setPreferredWidth(JBUI.scale(60));  // Ref
        colModel.getColumn(3).setPreferredWidth(JBUI.scale(70));  // SHA
        colModel.getColumn(3).setMaxWidth(JBUI.scale(90));
        colModel.getColumn(4).setPreferredWidth(JBUI.scale(40));   // 来源
        colModel.getColumn(4).setMaxWidth(JBUI.scale(50));
        colModel.getColumn(5).setPreferredWidth(JBUI.scale(135));  // 创建时间
        colModel.getColumn(6).setPreferredWidth(JBUI.scale(50));   // 耗时
        colModel.getColumn(6).setMaxWidth(JBUI.scale(60));
        colModel.getColumn(7).setPreferredWidth(JBUI.scale(80));   // 触发人
        JBScrollPane tableScroll = new JBScrollPane(pipelineTable);

        // 流水线区域：表格 + 底部工具条（刷新列表 / 上一页 / 下一页 / 页码，与列表在一起）。
        // 用垂直 BoxLayout：表格占剩余高度、工具条始终保持换行后的完整高度，窗口变窄换行不遮挡。
        JPanel pipelineToolbar = flowToolbar();
        pipelineToolbar.add(refreshListButton);
        pipelineToolbar.add(pipelinePrevButton);
        pipelineToolbar.add(pipelineNextButton);
        pipelineToolbar.add(pipelinePageLabel);
        JPanel pipelinePanel = new JPanel();
        pipelinePanel.setLayout(new BoxLayout(pipelinePanel, BoxLayout.Y_AXIS));
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        pipelineToolbar.setAlignmentX(Component.LEFT_ALIGNMENT);
        pipelinePanel.add(tableScroll);
        pipelinePanel.add(pipelineToolbar);

        JPanel logPanel = new JPanel();
        logPanel.setLayout(new BoxLayout(logPanel, BoxLayout.Y_AXIS));
        JPanel logToolbar = flowToolbar();
        logToolbar.setAlignmentX(Component.LEFT_ALIGNMENT);
        logToolbar.add(new JBLabel("Job:"));
        jobCombo.setPreferredSize(new java.awt.Dimension(JBUI.scale(220), JBUI.scale(26)));
        logToolbar.add(jobCombo);
        logToolbar.add(cancelJobButton);
        logToolbar.add(retryJobButton);
        logToolbar.add(refreshLogButton);
        logToolbar.add(new JLabel("  搜索:"));
        searchField.setPreferredSize(new java.awt.Dimension(JBUI.scale(180), JBUI.scale(26)));
        logToolbar.add(searchField);
        logToolbar.add(prevButton);
        logToolbar.add(nextButton);
        logToolbar.add(matchLabel);
        logViewer.setAlignmentX(Component.LEFT_ALIGNMENT);
        logPanel.add(logToolbar);
        logPanel.add(logViewer);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pipelinePanel, logPanel);
        // 默认分配更多空间给日志区（resizeWeight=0.35：额外空间约 35% 给上方流水表，65% 给下方日志）
        split.setResizeWeight(0.35);
        // 显式初始分隔比例：光靠 resizeWeight 不会改变首次布局，初始分隔点仍按组件
        // preferredSize 决定。此处直接按比例定位，保证日志区默认更大。构造阶段尺寸可能为 0，
        // 完善定位在面板 resize 回调里按比例校准，见 applyDividerProportion 与 syncOrientationByAspect。
        split.setContinuousLayout(true);
        // 分隔条改成细横线：用 1px 的 divider 替代默认大块的白色分隔条，视觉上更清爽
        split.setDividerSize(JBUI.scale(1));
        // 分栏两侧最小高度设小，窗口变窄/变矮时优先压缩表格与日志区域，而不是挤压工具条
        pipelinePanel.setMinimumSize(new Dimension(0, JBUI.scale(60)));
        logPanel.setMinimumSize(new Dimension(0, JBUI.scale(60)));
        add(split, BorderLayout.CENTER);

        projectSelector.addProjectSelectionListener(this::onProjectSelected);
        throttle(refreshProjectsButton, 2000, this::refreshProjectsManually);
        bindLoadButton(refreshListButton, () -> {
            ProjectEntry cur = currentProject;
            if (cur == null || GitLabSettings.getInstance().getToken().isEmpty()) {
                releaseLoadButtons(); // 未发起请求，立即恢复按钮可用
                return;
            }
            refreshAll(cur); // 只刷新当前页的流水线列表
        });
        triggerButton.addActionListener(e -> triggerPipeline());
        cancelButton.addActionListener(e -> cancelSelectedPipeline());
        retryPipelineButton.addActionListener(e -> retrySelectedPipeline());
        settingsButton.addActionListener(e -> openSettings());
        bindLoadButton(pipelinePrevButton, () -> {
            if (pipelinePage > 1 && currentProject != null) {
                refreshAll(currentProject, pipelinePage - 1);
            }
        });
        bindLoadButton(pipelineNextButton, () -> {
            if (pipelineHasNext && currentProject != null) {
                refreshAll(currentProject, pipelinePage + 1);
            }
        });

        pipelineTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onPipelineSelected();
            }
        });

        jobCombo.addActionListener(e -> onJobSelected());
        cancelJobButton.addActionListener(e -> cancelSelectedJob());
        retryJobButton.addActionListener(e -> retrySelectedJob());
        bindLoadButton(refreshLogButton, this::refreshTrace);

        searchField.getTextEditor().addActionListener(e -> {
            logViewer.findNext();
            updateMatchLabel();
        });
        // 上一处/下一处：日志内搜索上下导航（本地操作），用时间节流防止连点高频触发
        throttle(prevButton, 250, () -> {
            logViewer.findPrev();
            updateMatchLabel();
        });
        throttle(nextButton, 250, () -> {
            logViewer.findNext();
            updateMatchLabel();
        });
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onSearchChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onSearchChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onSearchChanged();
            }
        });

        // 初始按钮可用状态（随选择变化）
        updateActionButtons();
        // 初始分页状态：第 1 页，上一页不可点、下一页待数据加载后再定
        updatePageControls();
    }

    /**
     * 扁平图标导航按钮（上一页/下一页/上一处/下一处）：
     * 去掉默认内边距与边框，只留图标；悬停/按下高亮由 IDE 自带 LAF 渲染（保留 contentAreaFilled 默认值）。
     * 不使用 {@code com.intellij.ui.IconButton}（内部 API，部分构建解析不到），保证跨版本可编译。
     */
    private static JButton iconOnlyButton(Icon icon, String tooltip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(0, 0, 0, 0));
        b.setBorderPainted(false);
        b.setPreferredSize(new Dimension(JBUI.scale(20), JBUI.scale(20)));
        return b;
    }

    /**
     * 把会联网的按钮接入「加载中置灰」（loading 防抖）：
     * 点击后立即置灰，加载期间忽略一切点击（防止连点触发重复接口请求），
     * 数据返回/出错后由 {@link #releaseLoadButtons()} 统一恢复可点。
     * 适用于触发 refreshAll / refreshTrace 的按钮，因为它们一定走过上述完成回调。
     */
    private void bindLoadButton(JButton button, Runnable action) {
        bindLoadButtonInternal(button, button::addActionListener, action);
    }

    private void bindLoadButtonInternal(Component button, Consumer<ActionListener> addListener, Runnable action) {
        loadGateButtons.add(button);
        addListener.accept(e -> {
            if (!button.isEnabled()) {
                return; // 加载中：忽略连点
            }
            button.setEnabled(false); // 进入 loading：置灰反馈
            action.run();
        });
    }

    /**
     * 时间节流（用于本地即时操作按钮，如日志内“上一处/下一处”搜索导航）：
     * intervalMs 内的重复点击被忽略，触发后先置灰一小段再恢复，避免高频连点。
     * 这类操作不联网，用单纯时间窗即可，无需等待网络完成。
     */
    private void throttle(JButton button, long intervalMs, Runnable action) {
        throttleInternal(button, button::addActionListener, intervalMs, action);
    }

    private void throttleInternal(Component button, Consumer<ActionListener> addListener, long intervalMs, Runnable action) {
        addListener.accept(e -> {
            long now = System.currentTimeMillis();
            Long last = throttleAt.get(button);
            if (last != null && now - last < intervalMs) {
                return;
            }
            throttleAt.put(button, now);
            button.setEnabled(false);
            Timer timer = new Timer((int) intervalMs, ev -> button.setEnabled(true));
            timer.setRepeats(false);
            timer.start();
            action.run();
        });
    }

    /**
     * 加载结束后恢复“刷新/导航”按钮可点；流水线导航按钮的可用性仍由分页状态决定
     */
    private void releaseLoadButtons() {
        updatePageControls(); // 恢复 上一页/下一页 的可用性（受分页与“有无下一页”约束）
        for (Component b : loadGateButtons) {
            if (b != pipelinePrevButton && b != pipelineNextButton) {
                b.setEnabled(true);
            }
        }
    }

    /**
     * 可换行的工具条：窗口变窄时按钮自动换到下一行。
     * 关键点：FlowLayout 的 getPreferredSize() 只按「单行、宽度无限」计算高度，
     * 窗口变窄发生换行后上报的高度仍是单行，第二行会被父容器（BorderLayout/BoxLayout）裁剪。
     * 这里按「当前可用宽度」重新计算换行后的完整高度；
     * 最小/最大高度都取该换行后高度，放入垂直 BoxLayout 时优先压缩表格/日志区域。
     */
    private static JPanel flowToolbar() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2)) {
            @Override
            public Dimension getPreferredSize() {
                return wrappedPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }

            @Override
            public Dimension getMaximumSize() {
                Dimension d = super.getMaximumSize();
                d.height = getPreferredSize().height;
                return d;
            }

            /** 按当前可用宽度计算换行后的尺寸；宽度足够时退化为单行 */
            private Dimension wrappedPreferredSize() {
                Dimension single = super.getPreferredSize();
                int avail = availableWidth();
                int contentW = avail - getInsets().left - getInsets().right;
                if (contentW <= 0 || contentW >= single.width) {
                    return single;
                }
                FlowLayout fl = (FlowLayout) getLayout();
                int hgap = fl.getHgap();
                int vgap = fl.getVgap();
                int rowWidth = 0;
                int rowHeight = 0;
                int maxRowWidth = 0;
                int totalHeight = 0;
                for (int i = 0; i < getComponentCount(); i++) {
                    Component c = getComponent(i);
                    if (!c.isVisible()) {
                        continue;
                    }
                    Dimension d = c.getPreferredSize();
                    if (rowWidth + d.width > contentW) {
                        maxRowWidth = Math.max(maxRowWidth, rowWidth);
                        if (totalHeight > 0) {
                            totalHeight += vgap;
                        }
                        totalHeight += rowHeight;
                        rowWidth = d.width;
                        rowHeight = d.height;
                    } else {
                        rowWidth += d.width + hgap;
                        rowHeight = Math.max(rowHeight, d.height);
                    }
                }
                maxRowWidth = Math.max(maxRowWidth, rowWidth);
                if (totalHeight > 0) {
                    totalHeight += vgap;
                }
                totalHeight += rowHeight;
                return new Dimension(maxRowWidth + getInsets().left + getInsets().right,
                        totalHeight + getInsets().top + getInsets().bottom);
            }

            /**
             * 可用宽度：优先取父容器的当前宽度，而不是自身宽度。
             * 为什么：工具条在 BoxLayout/BorderLayout 里宽度会被拉满（=父容器宽度），
             * 布局查询发生在本组件 getWidth() 还是「上一次」的值时（窗口刚缩小时偏大），
             * 会误判「单行放得下」，把换行高度算成单行，第二行被父容器裁剪、内容看不见。
             * 父容器在本组件布局前已完成新一轮尺寸更新，其宽度才是真实可用宽度。
             */
            private int availableWidth() {
                for (Container p = getParent(); p != null; p = p.getParent()) {
                    int pw = p.getWidth();
                    if (pw > 0) {
                        return pw;
                    }
                }
                int w = getWidth();
                return Math.max(w, 0);
            }
        };
    }

    private void onSearchChanged() {
        logViewer.setSearch(searchField.getText());
        updateMatchLabel();
    }

    private void updateMatchLabel() {
        int count = logViewer.getMatchCount();
        if (count == 0) {
            matchLabel.setText(searchField.getText().isEmpty() ? "" : "  0 处匹配");
        } else {
            matchLabel.setText("  " + logViewer.getCurrentIndex() + "/" + count);
        }
    }

    // ---------------------------------------------------------------- 项目

    /**
     * 重新收集当前窗口项目并填入树形选择器顶部「当前项目」节点（不自动刷新，由调用方触发），返回选中项
     */
    private ProjectEntry refreshCurrentProjects() {
        currentWindowProjects = GitRepositoryUtil.collectProjects();
        projectSelector.setCurrentWindowProjects(currentWindowProjects);
        return projectSelector.getSelectedProject();
    }

    /**
     * 手动刷新项目：清掉缓存，重填当前窗口项目并重置组树后重新懒加载
     */
    private void refreshProjectsManually() {
        service().clearCache();
        refreshCurrentProjects();
        projectSelector.reload();
    }

    private void onProjectSelected(ProjectEntry entry) {
        if (disposed || entry == null) {
            return;
        }
        currentProject = entry;
        GitLabSettings.getInstance().setLastProjectPath(entry.getPath());
        if (GitLabSettings.getInstance().getToken().isEmpty()) {
            setStatus("未配置访问令牌，请点击【设置】填写 GitLab 访问令牌（需勾选 api 权限）。");
            return;
        }
        refreshAll(entry, 1); // 切换项目默认展示最新一页
    }

    // ---------------------------------------------------------------- 数据加载

    /**
     * 数据编排服务：项目 -> 流水线列表(补详情) -> Jobs -> 日志 的完整数据流统一由该服务承担，
     * 面板只在后台任务中取数并按快照刷新 UI，不再直接接触 GitLab API 客户端。
     */
    private PipelineDataService service() {
        return PipelineDataService.getInstance(ideaProject);
    }

    /**
     * 完整刷新（保持当前页码）：项目 -> 流水线列表 -> 选中流水线的 Jobs -> 选中 Job 的日志
     */
    private void refreshAll(ProjectEntry entry) {
        refreshAll(entry, pipelinePage);
    }

    /**
     * 完整刷新指定页：最新 10 条为第 1 页，支持上一页/下一页翻页浏览更早的流水线
     */
    private void refreshAll(ProjectEntry entry, int page) {
        final long gen = generation.incrementAndGet();
        final int targetPage = Math.max(1, page);
        final long keepPipelineId = selectedPipelineId;
        final long keepJobId = selectedJobId;
        // Task.Backgroundable = 后台异步任务（对标 @Async）：
        // run() 里的网络请求跑在后台线程，绝不阻塞 EDT；进度框显示 loading 提示。
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "加载 " + entry.getPath(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    PipelineSnapshot snap = service().loadPage(entry.getPath(), targetPage, keepPipelineId, keepJobId);
                    applyRefresh(gen, entry, snap.projectId(), snap.pipelines(), targetPage,
                            snap.hasNext(), snap.target(), snap.jobs(), snap.targetJobId(), snap.trace());
                } catch (Exception t) {
                    handleLoadError(gen, "加载失败: " + entry.getPath(), t);
                }
            }
        });
    }

    private void applyRefresh(long gen, ProjectEntry entry, long projectId, List<PipelineInfo> pipelines,
                              int page, boolean hasNext, PipelineInfo target, List<JobInfo> jobs,
                              long targetJobId, String trace) {
        // invokeLater = 回到 UI 线程（EDT）更新控件（对标 Spring 里 @Async 后回主线程刷新页面）。
        // 后台线程严禁直接操作 Swing 控件，必须先切回 EDT。
        ApplicationManager.getApplication().invokeLater(() -> {
            if (gen != generation.get() || disposed) {
                return;
            }
            releaseLoadButtons();
            currentProject = entry;
            currentProjectId = projectId;
            lastPipelines = pipelines;
            pipelinePage = page;
            pipelineHasNext = hasNext;
            updatePageControls();

            uiUpdating = true;
            try {
                pipelineModel.setRowCount(0);
                for (PipelineInfo p : pipelines) {
                    pipelineModel.addRow(new Object[]{
                            "#" + p.iid, p.status, p.ref, shortSha(p.sha), p.source, formatCreatedAt(p.createdAt),
                            fmtDuration(p.durationSeconds), p.user
                    });
                }
                if (target != null) {
                    int row = findPipelineRow(target.id);
                    if (row >= 0) {
                        pipelineTable.getSelectionModel().setSelectionInterval(row, row);
                    }
                }
                jobCombo.removeAllItems();
                for (JobInfo j : jobs) {
                    jobCombo.addItem(j);
                }
                selectJobById(targetJobId);
                updateActionButtons();
            } finally {
                uiUpdating = false;
            }

            if (trace != null) {
                logViewer.setLog(trace);
            } else if (jobs.isEmpty()) {
                logViewer.setLog("该流水线暂无 Job 或无法获取日志。");
            }
        });
    }

    private void onPipelineSelected() {
        if (uiUpdating || disposed) {
            return;
        }
        int row = pipelineTable.getSelectedRow();
        PipelineInfo p = (row >= 0 && row < lastPipelines.size()) ? lastPipelines.get(row) : null;
        selectedPipelineId = p == null ? -1 : p.id;
        updateActionButtons();
        if (p == null) {
            return;
        }
        loadJobsAndTrace(p);
    }

    private void loadJobsAndTrace(PipelineInfo p) {
        final long gen = generation.incrementAndGet();
        final long keepJobId = selectedJobId;
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "加载流水线 #" + p.iid, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    JobsView view = service().loadJobs(currentProjectId, p.id, keepJobId);
                    final List<JobInfo> jobs = view.jobs();
                    final long loadedJobId = view.targetJobId();
                    final String loadedTrace = view.trace();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        uiUpdating = true;
                        try {
                            jobCombo.removeAllItems();
                            for (JobInfo j : jobs) {
                                jobCombo.addItem(j);
                            }
                            selectJobById(loadedJobId);
                            updateActionButtons();
                        } finally {
                            uiUpdating = false;
                        }
                        logViewer.setLog(Objects.requireNonNullElse(loadedTrace, "（该流水线无 Job 或无法获取日志）"));
                    });
                } catch (Exception t) {
                    handleLoadError(gen, "加载流水线 Job 失败", t);
                }
            }
        });
    }

    private void onJobSelected() {
        if (uiUpdating || disposed) {
            return;
        }
        Object item = jobCombo.getSelectedItem();
        JobInfo job = item instanceof JobInfo ? (JobInfo) item : null;
        selectedJobId = job == null ? -1 : job.id;
        updateActionButtons();
        if (job == null) {
            return;
        }
        loadTrace(job);
    }

    private void loadTrace(JobInfo job) {
        final long gen = generation.incrementAndGet();
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "加载日志 " + job.name, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    String trace = service().loadTrace(currentProjectId, job.id);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        logViewer.setLog(trace);
                    });
                } catch (Exception t) {
                    handleLoadError(gen, "加载日志失败: " + job.name, t);
                }
            }
        });
    }

    /**
     * 只刷新选中 Job 的日志，不重新拉取流水线列表
     */
    private void refreshTrace() {
        JobInfo job = getSelectedJob();
        if (job == null) {
            Messages.showInfoMessage("请先选择要刷新日志的 Job。", "提示");
            releaseLoadButtons(); // 未发起请求，立即恢复按钮可用，避免一直置灰
            return;
        }
        final long gen = generation.incrementAndGet();
        final long pid = currentProjectId;
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "刷新日志 " + job.name, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    String trace = service().loadTrace(pid, job.id);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        releaseLoadButtons();
                        logViewer.setLog(trace);
                    });
                } catch (Exception t) {
                    handleActionError(gen, "刷新日志失败", t);
                }
            }
        });
    }

    private void selectJobById(long jobId) {
        for (int i = 0; i < jobCombo.getItemCount(); i++) {
            JobInfo j = jobCombo.getItemAt(i);
            if (j.id == jobId) {
                jobCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 触发 / 取消

    private void triggerPipeline() {
        if (currentProject == null || currentProjectId == -1) {
            Messages.showWarningDialog("请先选择一个项目。", "提示");
            return;
        }
        if (GitLabSettings.getInstance().getToken().isEmpty()) {
            Messages.showWarningDialog("请先在【设置】中配置访问令牌。", "提示");
            return;
        }
        final long gen = generation.incrementAndGet();
        final ProjectEntry entry = currentProject;
        // 后台先拉取分支列表，避免阻塞 UI；随后弹出带分支下拉框的对话框
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "加载分支列表", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> branches = loadBranches();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (gen != generation.get() || disposed) {
                        return;
                    }
                    showTriggerDialog(entry, branches);
                });
            }
        });
    }

    private List<String> loadBranches() {
        try {
            return service().loadBranches(currentProjectId);
        } catch (Exception t) {
            // 分支加载失败：对话框回退为可手动输入的编辑框，保证功能可用
            LOG.warn("加载分支列表失败", t);
            return new ArrayList<>();
        }
    }

    private void showTriggerDialog(ProjectEntry entry, List<String> branches) {
        TriggerPipelineDialog dlg = new TriggerPipelineDialog(ideaProject, currentPipelineRef(), branches);
        if (!dlg.showAndGet()) {
            return;
        }
        final long gen = generation.incrementAndGet();
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "触发流水线", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String ref = dlg.getRef();
                try {
                    service().triggerPipeline(currentProjectId, ref, dlg.getVariables());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        setStatus("已触发流水线（ref=" + ref + "）");
                        if (entry != null) {
                            refreshAll(entry, 1); // 触发后回到最新一页，方便看到新建的流水线
                        }
                    });
                } catch (GitLabApiException t) {
                    // POST /projects/:id/pipelines 返回 404 通常是「该 ref 不存在」或「该 ref 下没有 .gitlab-ci.yml」。
                    // 端点/令牌本身是好的（GET 能正常返回），给用户明确提示而不是笼统的 404。
                    final String hint = "触发流水线失败（ref=" + ref + "）：";
                    if (t.statusCode == 404) {
                        handleActionError(gen, hint + "找不到该分支/标签，或该 ref 下没有 .gitlab-ci.yml 配置", t);
                    } else {
                        handleActionError(gen, hint, t);
                    }
                } catch (Exception t) {
                    handleActionError(gen, "触发流水线失败（ref=" + ref + "）", t);
                }
            }
        });
    }

    private void cancelSelectedPipeline() {
        int row = pipelineTable.getSelectedRow();
        if (row < 0 || row >= lastPipelines.size()) {
            Messages.showWarningDialog("请先在列表中选择要取消的流水线。", "提示");
            return;
        }
        PipelineInfo p = lastPipelines.get(row);
        if (!PipelineStatus.isActive(p.status)) {
            Messages.showInfoMessage("只有运行中/等待中的流水线可以取消。", "提示");
            return;
        }
        int answer = Messages.showYesNoDialog("确认取消流水线 #" + p.iid + "（" + p.ref + "）？",
                "取消流水线", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        final long gen = generation.incrementAndGet();
        final ProjectEntry entry = currentProject;
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "取消流水线 #" + p.iid, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    service().cancelPipeline(currentProjectId, p.id);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        setStatus("已取消流水线 #" + p.iid);
                        if (entry != null) {
                            refreshAll(entry);
                        }
                    });
                } catch (Exception t) {
                    handleActionError(gen, "取消流水线失败", t);
                }
            }
        });
    }

    private void cancelSelectedJob() {
        Object item = jobCombo.getSelectedItem();
        if (!(item instanceof JobInfo)) {
            Messages.showWarningDialog("请先选择要取消的 Job。", "提示");
            return;
        }
        JobInfo job = (JobInfo) item;
        if (!PipelineStatus.isActive(job.status)) {
            Messages.showInfoMessage("只有运行中/等待中的 Job 可以取消。", "提示");
            return;
        }
        int answer = Messages.showYesNoDialog("确认取消 Job " + job.name + "？", "取消Job", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        final long gen = generation.incrementAndGet();
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "取消 Job " + job.name, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    service().cancelJob(currentProjectId, job.id);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        setStatus("已取消 Job " + job.name);
                        if (currentProject != null) {
                            refreshAll(currentProject);
                        }
                    });
                } catch (Exception t) {
                    handleActionError(gen, "取消 Job 失败", t);
                }
            }
        });
    }

    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog(ideaProject);
        if (dlg.showAndGet()) {
            restartAutoRefreshTimer(); // 刷新间隔可能被修改，让定时器立即生效
            ProjectEntry cur = currentProject;
            refreshCurrentProjects();
            if (cur != null && !GitLabSettings.getInstance().getToken().isEmpty()) {
                refreshAll(cur);
            }
        }
    }

    /**
     * 按最新设置的刷新间隔重启自动刷新定时器
     */
    private void restartAutoRefreshTimer() {
        if (disposed) {
            return;
        }
        int interval = Math.max(5, GitLabSettings.getInstance().getRefreshIntervalSeconds());
        if (autoRefreshTimer.getDelay() != interval * 1000L) {
            autoRefreshTimer.setDelay(interval * 1000);
            autoRefreshTimer.restart();
        }
    }

    /**
     * 重试已失败/已取消的流水线（生成一条新的流水线）
     */
    private void retrySelectedPipeline() {
        PipelineInfo p = getSelectedPipeline();
        if (p == null) {
            Messages.showWarningDialog("请先在列表中选择要重试的流水线。", "提示");
            return;
        }
        if (!PipelineStatus.isRetryablePipeline(p.status)) {
            Messages.showInfoMessage("只有失败/已取消的流水线可以重试。", "提示");
            return;
        }
        int answer = Messages.showYesNoDialog("确认重试流水线 #" + p.iid + "（" + p.ref + "）？",
                "重试流水线", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        final long gen = generation.incrementAndGet();
        final ProjectEntry entry = currentProject;
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "重试流水线 #" + p.iid, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    service().retryPipeline(currentProjectId, p.id);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        setStatus("已重试流水线 #" + p.iid);
                        if (entry != null) {
                            refreshAll(entry);
                        }
                    });
                } catch (Exception t) {
                    handleActionError(gen, "重试流水线失败", t);
                }
            }
        });
    }

    /**
     * 执行手动 Job（when: manual）或重试失败/已取消的 Job
     */
    private void retrySelectedJob() {
        JobInfo job = getSelectedJob();
        if (job == null) {
            Messages.showWarningDialog("请先选择要执行/重试的 Job。", "提示");
            return;
        }
        if (!PipelineStatus.isRetryableJob(job.status)) {
            Messages.showInfoMessage("只有手动/失败/已取消的 Job 可以执行或重试。", "提示");
            return;
        }
        boolean play = PipelineStatus.of(job.status) == PipelineStatus.MANUAL;
        String action = play ? "执行" : "重试";
        int answer = Messages.showYesNoDialog("确认" + action + " Job " + job.name + "？",
                action + " Job", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        final long gen = generation.incrementAndGet();
        final boolean isManual = play;
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, action + " Job " + job.name, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    if (isManual) {
                        service().playJob(currentProjectId, job.id);
                    } else {
                        service().retryJob(currentProjectId, job.id);
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != generation.get() || disposed) {
                            return;
                        }
                        setStatus("已" + action + " Job " + job.name);
                        if (currentProject != null) {
                            refreshAll(currentProject);
                        }
                    });
                } catch (Exception t) {
                    handleActionError(gen, action + " Job 失败", t);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 辅助

    private void handleLoadError(long gen, String message, Throwable t) {
        LOG.warn(message, t);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (gen != generation.get() || disposed) {
                return;
            }
            releaseLoadButtons();
            setError(message + (t instanceof GitLabApiException ? "：" + t.getMessage() : "：" + t));
        });
    }

    private void handleActionError(long gen, String message, Throwable t) {
        LOG.warn(message, t);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (gen != generation.get() || disposed) {
                return;
            }
            releaseLoadButtons();
            setError(message + "：" + t.getMessage());
            Messages.showErrorDialog(message + "：" + t.getMessage(), "错误");
        });
    }

    private void setStatus(String text) {
        notify(text, NotificationType.INFORMATION);
    }

    private void setError(String text) {
        notify(text, NotificationType.ERROR);
    }

    private void notify(String text, NotificationType type) {
        NotificationService.notify(ideaProject, text, type);
    }

    private String currentPipelineRef() {
        int row = pipelineTable.getSelectedRow();
        if (row >= 0 && row < lastPipelines.size()) {
            return lastPipelines.get(row).ref;
        }
        return "main";
    }

    private int findPipelineRow(long pipelineId) {
        for (int i = 0; i < lastPipelines.size(); i++) {
            if (lastPipelines.get(i).id == pipelineId) {
                return i;
            }
        }
        return -1;
    }

    private PipelineInfo getSelectedPipeline() {
        int row = pipelineTable.getSelectedRow();
        if (row >= 0 && row < lastPipelines.size()) {
            return lastPipelines.get(row);
        }
        return null;
    }

    private JobInfo getSelectedJob() {
        Object item = jobCombo.getSelectedItem();
        return item instanceof JobInfo ? (JobInfo) item : null;
    }

    /**
     * 根据当前选择刷新操作按钮的可用状态（取消/重试与作业状态联动）
     */
    private void updateActionButtons() {
        PipelineInfo p = getSelectedPipeline();
        cancelButton.setEnabled(p != null && PipelineStatus.isActive(p.status));
        retryPipelineButton.setEnabled(p != null && PipelineStatus.isRetryablePipeline(p.status));

        JobInfo job = getSelectedJob();
        cancelJobButton.setEnabled(job != null && PipelineStatus.isActive(job.status));
        retryJobButton.setEnabled(job != null && PipelineStatus.isRetryableJob(job.status));
    }

    /**
     * 刷新分页按钮与页码标签：第 1 页不可点上一页，无更多数据时不可点下一页
     */
    private void updatePageControls() {
        pipelinePrevButton.setEnabled(pipelinePage > 1);
        pipelineNextButton.setEnabled(pipelineHasNext);
        pipelinePageLabel.setText("第 " + pipelinePage + " 页");
    }

    /**
     * 依据面板宽高比推断当前停靠姿势并同步分栏方向：
     * 高 > 宽 -> 侧边竖屏（上下分栏）；高 <= 宽 -> 底部/顶横屏（左右分栏）。
     * 用宽高比而非读锚点，因为停靠切换瞬间锚点回调与本面板 resize 有先后差，
     * 直接按比例判断能与这次拉升在同一帧内完成，视觉更顺滑。
     */
    private void syncOrientationByAspect() {
        if (disposed || split == null) {
            return;
        }
        int w = split.getWidth();
        int h = split.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean portrait = h > w;
        int target = portrait ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
        if (split.getOrientation() != target) {
            split.setOrientation(target);
        }
    }

    /**
     * 设置默认停靠尺寸：底部/顶部希望高度小，侧边希望宽度小。
     * 工具窗口停靠大小（frame 分隔条比例）以面板 preferredSize 为默认值计算，
     * 故首次按方向收紧；用 preferredAnchor 防止后续反复覆盖用户手动调整过的尺寸。
     */
    private void applyDefaultDockSize() {
        if (disposed || toolWindow == null || toolWindow.isDisposed()) {
            return;
        }
        ToolWindowAnchor anchor = toolWindow.getAnchor();
        if (anchor == preferredAnchor) {
            return;
        }
        preferredAnchor = anchor;
        boolean sideDocked = anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.RIGHT;
        if (sideDocked) {
            setPreferredSize(new Dimension(SIDE_DEF_WIDTH, getPreferredSize().height));
        } else {
            setPreferredSize(new Dimension(getPreferredSize().width, BOTTOM_DEF_HEIGHT));
        }
    }

    /**
     * 按当前停靠方向应用「流水表 : 日志区」的初始分隔比例。
     * 流水表始终是 split 的第一个组件（竖排=上方、横排=左侧），所以无论方向如何，
     * 分隔点都取 LIST_PROPORTION，保证流水表占比小、日志区占比大。
     * PSplitPane.setDividerLocation(double) 的实参按比例(0~1)解释：第一个组件占该比例。
     */
    private void applyDividerProportion() {
        split.setDividerLocation(LIST_PROPORTION);
    }

    private static final DateTimeFormatter CREATED_AT_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * GitLab 返回的创建时间（ISO-8601，可能带时区/小数秒），统一格式化为本地时区的 yyyy-MM-dd HH:mm:ss。
     * 解析失败时原样返回，避免日志/表格崩溃。
     */
    private static String formatCreatedAt(String iso) {
        if (iso == null || iso.isEmpty()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(iso)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(CREATED_AT_OUT);
        } catch (Exception ignored) {
            return iso;
        }
    }

    private static String shortSha(String sha) {
        return sha != null && sha.length() > 8 ? sha.substring(0, 8) : (sha == null ? "" : sha);
    }

    private static String fmtDuration(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        long m = seconds / 60;
        long s = seconds % 60;
        return m > 0 ? m + "m" + s + "s" : s + "s";
    }

    /**
     * 状态列渲染：中文文案 + 颜色（文案/颜色规则统一由 PipelineStatus 内聚）
     */
    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String status = value == null ? "" : value.toString();
            setText(PipelineStatus.display(status));
            setToolTipText(PipelineStatus.display(status));
            if (!isSelected) {
                setForeground(PipelineStatus.displayColor(status));
            }
            return c;
        }
    }

    /**
     * 通用悬浮提示渲染器：鼠标悬停在任意单元格时显示该格完整文字（如 Ref/SHA 被截断仍可读全文）。
     */
    private static final class TooltipCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setToolTipText(value == null ? "" : value.toString());
            return c;
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        autoRefreshTimer.stop();
        if (layoutTimer != null) {
            layoutTimer.stop();
        }
        if (detectTimer != null) {
            detectTimer.stop();
        }
    }
}
