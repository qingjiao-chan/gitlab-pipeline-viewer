package com.gitlab.pipeline.viewer.ui;

import com.gitlab.pipeline.viewer.model.GroupChildrenView;
import com.gitlab.pipeline.viewer.model.GroupEntry;
import com.gitlab.pipeline.viewer.model.ProjectEntry;
import com.gitlab.pipeline.viewer.services.ProjectSelectionService;
import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 树形项目选择器（对标 Element Plus 的 tree-select）：
 * - 单个下拉：点击弹出「搜索框 + 树」；
 * - 树的第一项固定为「当前项目」（当前 IDEA 窗口检测到的项目），其后按 GitLab 项目组分层；
 * - 项目组/子组按需懒加载（展开时才拉取子组与直接项目），不一次性加载所有项目；
 * - 搜索框只对「已加载出来的项目」做模糊过滤（组/子组节点随命中祖先一起显示）；
 * - 只能选中项目（叶子），组节点不可选。
 */
public class ProjectTreeSelector extends JPanel {

    private static final String CURRENT_PROJECT_LABEL = "当前项目";
    private static final String LOADING_LABEL = "加载中…";
    private static final String EMPTY_LABEL = "（无内容）";
    private static final String ERROR_LABEL = "（加载失败）";

    private final Project ideaProject;
    private final JTextField displayField = new JTextField();
    private final JPopupMenu popup = new JPopupMenu();
    private final SearchTextField searchField = new SearchTextField();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);
    private JBScrollPane scroll;
    /**
     * 已加载过子内容的组节点（避免展开时重复请求）
     */
    private final Set<DefaultMutableTreeNode> loadedGroups = new HashSet<>();
    /**
     * 正在加载子内容的组节点（避免搜索展开/重复展开触发并发重复请求）
     */
    private final Set<DefaultMutableTreeNode> loadingGroups = new HashSet<>();
    /**
     * 搜索过滤树的克隆节点 -> 真实树节点 映射（展开克隆组时懒加载到真实节点上）
     */
    private final Map<DefaultMutableTreeNode, DefaultMutableTreeNode> liveNodes = new HashMap<>();

    private boolean groupsLoaded = false;
    private ProjectEntry selectedProject;
    /**
     * 重新加载的版本号：手动刷新后丢弃过期异步结果
     */
    private volatile long reloadGen = 0;
    private final List<Consumer<ProjectEntry>> listeners = new ArrayList<>();

    public ProjectTreeSelector(@NotNull Project project) {
        super(new BorderLayout());
        this.ideaProject = project;
        buildUi();
    }

    // ---------------------------------------------------------------- 构建 UI

    private void buildUi() {
        // 展示框：不可编辑，点击弹出下拉
        displayField.setEditable(false);
        displayField.setFocusable(false);
        displayField.setPreferredSize(new Dimension(JBUI.scale(400), JBUI.scale(26)));
        displayField.setToolTipText("点击选择项目");
        JBLabel arrow = new JBLabel(" ▾");
        arrow.setCursor(Cursor.getDefaultCursor());
        MouseAdapter toggle = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                togglePopup();
            }
        };
        displayField.addMouseListener(toggle);
        arrow.addMouseListener(toggle);
        JPanel fieldWrap = new JPanel(new BorderLayout());
        fieldWrap.add(displayField, BorderLayout.CENTER);
        fieldWrap.add(arrow, BorderLayout.EAST);
        add(fieldWrap, BorderLayout.CENTER);

        // 弹层：搜索框 + 树
        searchField.setPreferredSize(new Dimension(JBUI.scale(440), 26));
        searchField.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        // 单击组节点即展开（触发懒加载）；项目节点单击仍是选中
        tree.setToggleClickCount(1);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel,
                                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Component c = super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode node) {
                    Object uo = node.getUserObject();
                    if (uo instanceof ProjectEntry pe) {
                        // 只显示最后一级项目名，层级由树结构体现（path 放在 tooltip）
                        setText(pe.toString());
                        setToolTipText(pe.getPath());
                    } else if (uo instanceof GroupEntry g) {
                        // 只显示组名（单级），不显示带斜杠的完整路径
                        setText(g.name);
                        setToolTipText(g.fullPath);
                    } else if (uo instanceof String s) {
                        setText(s);
                    }
                }
                return c;
            }
        });
        // 展开组节点时懒加载其子组与直接项目（过滤树的克隆节点映射到真实节点后加载）
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode node) {
                    DefaultMutableTreeNode live = liveNodes.get(node);
                    if (live != null) {
                        node = live;
                    }
                    Object uo = node.getUserObject();
                    if (uo instanceof GroupEntry group) {
                        loadGroupChildren(node, group);
                    }
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
            }
        });
        // 只允许选中项目（叶子），组/当前项目等节点不可选
        tree.addTreeSelectionListener(e -> {
            Object last = tree.getLastSelectedPathComponent();
            if (last instanceof DefaultMutableTreeNode node) {
                Object uo = node.getUserObject();
                if (uo instanceof ProjectEntry pe) {
                    selectProject(pe);
                } else {
                    tree.clearSelection();
                }
            }
        });
        // 右键菜单：复制命中节点的路径（项目/组），树本身不支持文本选中，右键复制更方便
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showCopyPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showCopyPopup(e);
                }
            }
        });

        JPanel content = new JPanel(new BorderLayout(JBUI.scale(4), JBUI.scale(4)));
        content.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(4), JBUI.scale(4), JBUI.scale(4), JBUI.scale(4)));
        content.add(searchField, BorderLayout.NORTH);
        scroll = new JBScrollPane(tree);
        // 显式指定垂直滚动条策略：内容超过弹层高度时一定出现滚动条，保证可滚
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        // JPopupMenu 轻量弹层里，滚轮事件有时到不了 JBScrollPane，导致「滚动不生效」：
        // 在树（视口内容）上直接挂滚轮监听，手动驱动垂直滚动条，任何环境下都能滚。
        tree.addMouseWheelListener(this::scrollTreeByWheel);
        scroll.setPreferredSize(new Dimension(JBUI.scale(440), JBUI.scale(320)));
        content.add(scroll, BorderLayout.CENTER);
        popup.setLightWeightPopupEnabled(true);
        popup.add(content);
    }

    /**
     * 滚轮驱动树的垂直滚动条：手动累加滚动条值，绕开轻量弹层滚轮事件丢失的问题
     */
    private void scrollTreeByWheel(MouseWheelEvent e) {
        JScrollBar sb = scroll == null ? null : scroll.getVerticalScrollBar();
        if (sb == null) {
            return;
        }
        int units = e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL ? e.getUnitsToScroll() : 0;
        if (units == 0) {
            return;
        }
        int step = units * Math.max(1, sb.getUnitIncrement());
        sb.setValue(sb.getValue() + step);
        e.consume();
    }

    // ---------------------------------------------------------------- 对外接口

    /**
     * 设置「当前项目」下的项目列表（当前窗口检测到的 GitLab 项目）
     */
    public void setCurrentWindowProjects(@NotNull List<ProjectEntry> projects) {
        DefaultMutableTreeNode currentNode = findCurrentProjectNode();
        if (currentNode == null) {
            currentNode = new DefaultMutableTreeNode(CURRENT_PROJECT_LABEL);
            rootNode.insert(currentNode, 0);
        }
        currentNode.removeAllChildren();
        for (ProjectEntry p : projects) {
            currentNode.add(new DefaultMutableTreeNode(p));
        }
        if (projects.isEmpty()) {
            currentNode.add(new DefaultMutableTreeNode(EMPTY_LABEL));
        }
        treeModel.nodeStructureChanged(currentNode);
        // 尝试恢复上次选择的项目（仅限当前窗口项目，静默恢复，不触发回调）；
        // 未恢复成功时默认选中第一个当前窗口项目
        String last = GitLabSettings.getInstance().getLastProjectPath();
        ProjectEntry pick = null;
        for (ProjectEntry p : projects) {
            if (p.getPath().equals(last)) {
                pick = p;
                break;
            }
        }
        if (pick == null && !projects.isEmpty()) {
            pick = projects.get(0);
        }
        if (pick != null) {
            selectedProject = pick;
            displayField.setText(pick.getPath());
            displayField.setToolTipText(pick.getPath());
        }
        applyFilter();
    }

    @Nullable
    public ProjectEntry getSelectedProject() {
        return selectedProject;
    }

    public void addProjectSelectionListener(@NotNull Consumer<ProjectEntry> listener) {
        listeners.add(listener);
    }

    /**
     * 手动刷新：清缓存、重置组树，并立即重新加载顶级项目组
     */
    public void reload() {
        service().clearCache();
        reloadGen++;
        groupsLoaded = false;
        loadedGroups.clear();
        loadingGroups.clear();
        for (int i = rootNode.getChildCount() - 1; i >= 0; i--) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            Object uo = n.getUserObject();
            if (!(uo instanceof String s && CURRENT_PROJECT_LABEL.equals(s))) {
                rootNode.remove(i);
            }
        }
        treeModel.nodeStructureChanged(rootNode);
        loadTopLevelGroups();
    }

    // ---------------------------------------------------------------- 弹层与懒加载

    private void togglePopup() {
        if (popup.isVisible()) {
            popup.setVisible(false);
            return;
        }
        // 弹层宽度取目标宽度与当前窗口可用宽度的较小值，避免超出窗口被裁切
        int w = popupWidth();
        int h = popupHeight();
        searchField.setPreferredSize(new Dimension(w, 26));
        scroll.setPreferredSize(new Dimension(w, h));
        searchField.setText("");
        applyFilter();
        tree.clearSelection();
        popup.show(this, 0, getHeight());
        // 打开下拉时自动展开「当前项目」节点，直接展示当前窗口项目；
        // 项目组默认不加载，仅点击「刷新项目」后才会拉取并展示
        SwingUtilities.invokeLater(() -> {
            expandCurrentProjectNode();
            searchField.requestFocusInWindow();
        });
    }

    /**
     * 自动展开「当前项目」节点（存在且有子节点时）
     */
    private void expandCurrentProjectNode() {
        DefaultMutableTreeNode cur = findCurrentProjectNode();
        if (cur != null && cur.getChildCount() > 0) {
            tree.expandPath(new TreePath(cur.getPath()));
        }
    }

    /**
     * 弹层可用宽度：目标 440，但不超过最外层容器的可用宽度
     */
    private int popupWidth() {
        int target = JBUI.scale(440);
        int min = JBUI.scale(260);
        Component c = this;
        while (c.getParent() != null) {
            c = c.getParent();
        }
        int avail = c.getWidth() - JBUI.scale(20);
        return Math.max(min, Math.min(target, Math.max(avail, min)));
    }

    /**
     * 弹层可用高度：目标 320，但不超过本组件下方到屏幕底部的可用空间（避免弹出后底部被切掉）
     */
    private int popupHeight() {
        int target = JBUI.scale(320);
        int min = JBUI.scale(120);
        try {
            Point pos = getLocationOnScreen();
            int bottom = Toolkit.getDefaultToolkit().getScreenSize().height;
            int below = bottom - (pos.y + getHeight());
            return Math.max(min, Math.min(target, below - JBUI.scale(8)));
        } catch (Exception ignored) {
            return target;
        }
    }

    /**
     * 懒加载顶级项目组（GET /groups），成功后挂到根节点
     */
    private void loadTopLevelGroups() {
        if (groupsLoaded) {
            return;
        }
        groupsLoaded = true;
        final long gen = reloadGen;
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "加载项目组", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    List<GroupEntry> groups = service().loadRootGroups();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != reloadGen) {
                            return;
                        }
                        for (GroupEntry g : groups) {
                            rootNode.add(groupNode(g));
                        }
                        treeModel.nodeStructureChanged(rootNode);
                        applyFilter();
                    });
                } catch (Exception t) {
                    groupsLoaded = false; // 下次打开再重试
                }
            }
        });
    }

    /**
     * 展开组节点时懒加载其直接子组与直接项目
     */
    private void loadGroupChildren(DefaultMutableTreeNode node, GroupEntry group) {
        if (loadedGroups.contains(node) || loadingGroups.contains(node)) {
            return;
        }
        loadingGroups.add(node);
        final long gen = reloadGen;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(LOADING_LABEL));
        treeModel.nodeStructureChanged(node);
        ProgressManager.getInstance().run(new Task.Backgroundable(ideaProject, "加载项目组 " + group, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    GroupChildrenView view = service().loadChildren(group.id, group.name);
                    List<GroupEntry> subGroups = view.subGroups();
                    List<ProjectEntry> projects = view.projects();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != reloadGen) {
                            return;
                        }
                        node.removeAllChildren();
                        for (GroupEntry g : subGroups) {
                            node.add(groupNode(g));
                        }
                        for (ProjectEntry p : projects) {
                            node.add(new DefaultMutableTreeNode(p));
                        }
                        if (node.getChildCount() == 0) {
                            node.add(new DefaultMutableTreeNode(EMPTY_LABEL));
                        }
                        loadingGroups.remove(node);
                        loadedGroups.add(node);
                        treeModel.nodeStructureChanged(node);
                        // 仅在真实树可见时重新展开该组；搜索过滤态下由 applyFilter 统一展开
                        if (treeModel.getRoot() == rootNode) {
                            tree.expandPath(new TreePath(node.getPath()));
                        }
                        applyFilter();
                    });
                } catch (Exception t) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (gen != reloadGen) {
                            return;
                        }
                        loadingGroups.remove(node);
                        node.removeAllChildren();
                        node.add(new DefaultMutableTreeNode(ERROR_LABEL));
                        treeModel.nodeStructureChanged(node);
                    });
                }
            }
        });
    }

    /**
     * 组节点：带「加载中…」占位子节点，使其可展开以触发懒加载
     */
    private DefaultMutableTreeNode groupNode(GroupEntry g) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(g);
        node.add(new DefaultMutableTreeNode(LOADING_LABEL));
        return node;
    }

    // ---------------------------------------------------------------- 搜索过滤

    /**
     * 按搜索框关键字过滤树：只对已加载出来的节点做过滤；
     * 组/子组作为命中项目的祖先节点保留，命中组自身也保留（但不连带展示其全部子节点）。
     */
    private void applyFilter() {
        String q = searchField.getText().toLowerCase(Locale.ROOT).trim();
        liveNodes.clear();
        if (q.isEmpty()) {
            // 已在真实树时不要重复 setRoot：setRoot 会触发整树结构变更，导致已展开的组被收起
            if (treeModel.getRoot() != rootNode) {
                treeModel.setRoot(rootNode);
            }
            return;
        }
        DefaultMutableTreeNode filtered = new DefaultMutableTreeNode("root");
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            DefaultMutableTreeNode f = filterNode(child, q);
            if (f != null) {
                filtered.add(f);
            }
        }
        treeModel.setRoot(filtered);
        expandAll(filtered);
    }

    private DefaultMutableTreeNode filterNode(DefaultMutableTreeNode node, String q) {
        Object uo = node.getUserObject();
        boolean selfMatch = matches(uo, q);
        DefaultMutableTreeNode copy = new DefaultMutableTreeNode(uo);
        liveNodes.put(copy, node);
        boolean visible = selfMatch;
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (isPlaceholder(child)) {
                DefaultMutableTreeNode pc = new DefaultMutableTreeNode(child.getUserObject());
                liveNodes.put(pc, child);
                copy.add(pc);
                continue;
            }
            DefaultMutableTreeNode f = filterNode(child, q);
            if (f != null) {
                copy.add(f);
                visible = true;
            }
        }
        return visible ? copy : null;
    }

    private boolean matches(Object uo, String q) {
        if (uo == null) {
            return false;
        }
        if (q.isEmpty()) {
            return true;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        if (uo instanceof ProjectEntry pe) {
            return pe.getPath().toLowerCase(Locale.ROOT).contains(lower)
                    || pe.toString().toLowerCase(Locale.ROOT).contains(lower);
        }
        if (uo instanceof GroupEntry g) {
            return g.fullPath.toLowerCase(Locale.ROOT).contains(lower)
                    || g.name.toLowerCase(Locale.ROOT).contains(lower);
        }
        if (uo instanceof String s) {
            return s.toLowerCase(Locale.ROOT).contains(lower);
        }
        return false;
    }

    private boolean isPlaceholder(DefaultMutableTreeNode node) {
        Object uo = node.getUserObject();
        return uo instanceof String s
                && (LOADING_LABEL.equals(s) || EMPTY_LABEL.equals(s) || ERROR_LABEL.equals(s));
    }

    private void expandAll(DefaultMutableTreeNode root) {
        for (Enumeration<?> e = root.depthFirstEnumeration(); e.hasMoreElements(); ) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n != root && n.getChildCount() > 0) {
                tree.expandPath(new TreePath(n.getPath()));
            }
        }
    }

    // ---------------------------------------------------------------- 选择

    private void selectProject(ProjectEntry pe) {
        selectedProject = pe;
        displayField.setText(pe.getPath());
        displayField.setToolTipText(pe.getPath());
        popup.setVisible(false);
        for (Consumer<ProjectEntry> l : listeners) {
            l.accept(pe);
        }
    }

    /**
     * 右键弹层：复制鼠标所指节点的路径（项目 path / 组 fullPath / 占位文案）
     */
    private void showCopyPopup(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        Object uo = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        String text = null;
        if (uo instanceof ProjectEntry pe) {
            text = pe.getPath();
        } else if (uo instanceof GroupEntry g) {
            text = g.fullPath;
        } else if (uo instanceof String s) {
            text = s;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("复制路径");
        copy.setEnabled(text != null);
        final String copyText = text;
        copy.addActionListener(ev -> {
            if (copyText != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(copyText), null);
            }
        });
        menu.add(copy);
        menu.show(tree, e.getX(), e.getY());
    }

    private DefaultMutableTreeNode findCurrentProjectNode() {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (n.getUserObject() instanceof String s && CURRENT_PROJECT_LABEL.equals(s)) {
                return n;
            }
        }
        return null;
    }

    private ProjectSelectionService service() {
        return ProjectSelectionService.getInstance(ideaProject);
    }
}
