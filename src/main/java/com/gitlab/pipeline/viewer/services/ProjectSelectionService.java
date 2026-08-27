package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.GitLabProject;
import com.gitlab.pipeline.viewer.model.GroupChildrenView;
import com.gitlab.pipeline.viewer.model.GroupEntry;
import com.gitlab.pipeline.viewer.model.ProjectEntry;
import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 项目选择与上下文管理（project 级 service）。
 * <p>
 * 提供：
 * - 树形选择所需的懒加载接口（[loadRootGroups] / [loadChildren]）
 * - ChooseByNamePopup 风格平铺选择所需的全量加载接口（[loadAllProjects]）
 * <p>
 * 用 {@code @Service} 轻量注册（规范 §2），经 {@code project.getService()} 获取。
 */
@Service
public final class ProjectSelectionService {

    private static final Logger LOG = Logger.getInstance(ProjectSelectionService.class);

    /**
     * 并发加载组子节点时的最大线程数。GitLab 默认 rate-limit 是 600 req/min/token，
     * 并发 8 在大多数自建/官方实例上不会被限流；同时 N=200 个组的全量加载耗时 < 3s。
     */
    private static final int MAX_CONCURRENCY = 8;

    /**
     * 全量加载结果缓存：[loadAllProjects] 调用之间复用；[clearCache] 时清空。
     * 用 volatile 保证多线程可见性，{@code null} 表示"未加载"或"已清空"。
     */
    private volatile List<ProjectEntry> cachedAllProjects;

    public ProjectSelectionService() {
    }

    public static ProjectSelectionService getInstance(@NotNull Project project) {
        return project.getService(ProjectSelectionService.class);
    }

    private GitLabApiService api() {
        GitLabSettings s = GitLabSettings.getInstance();
        return new GitLabApiService(s.getGitlabUrl(), s.getToken(), s.getRequestTimeoutSeconds());
    }

    /**
     * 加载第一层顶级项目组（用于树形选择的根级懒加载）
     */
    public List<GroupEntry> loadRootGroups() throws Exception {
        return api().listRootGroups(200);
    }

    /**
     * 展开某个项目组时加载其直接子组与直接项目。
     *
     * @param parentGroupName 直接父组的名称，用作子项目的显示归属
     */
    public GroupChildrenView loadChildren(long groupId, @NotNull String parentGroupName) throws Exception {
        GitLabApiService api = api();
        List<GroupEntry> subGroups = api.listSubGroups(groupId, 200);
        List<ProjectEntry> projects = new ArrayList<>();
        for (GitLabProject gp : api.listDirectProjects(groupId, 200)) {
            if (gp.pathWithNamespace == null || gp.pathWithNamespace.isEmpty()) {
                continue;
            }
            projects.add(new ProjectEntry(parentGroupName, "", "", gp.pathWithNamespace));
        }
        return new GroupChildrenView(subGroups, projects);
    }

    /**
     * 一次性递归加载 GitLab 上所有可访问的组与项目（用于 ChooseByNamePopup 风格平铺选择器）。
     *
     * 实现：BFS 遍历组树（先顶级组，再逐层下钻），并发 fetch 子组与直接项目。
     * - 单个组加载失败被 try/catch 隔离，不影响其他组（最终结果可能"少了"那个分支）
     * - 并发受 [MAX_CONCURRENCY] 限制，避免触发 GitLab rate-limit
     * - 同一调用栈内共享一个 [GitLabApiService] 实例（每个 fetch 任务复用同一 HttpClient，
     *   HttpClient 是线程安全的；不再每次 new 一个，避免 N 次 TCP 连接建立）
     *
     * @return 所有项目的扁平列表（顺序为 BFS 遍历顺序），不会为 null；网络/解析失败时可能为空列表
     */
    @NotNull
    public List<ProjectEntry> loadAllProjects() {
        // 命中缓存直接返回（避免重复拉）
        List<ProjectEntry> cached = cachedAllProjects;
        if (cached != null) return cached;

        // 共享一个 GitLabApiService 给所有 fetch 任务复用（HttpClient 线程安全）
        GitLabApiService sharedApi = api();

        ExecutorService pool = new ThreadPoolExecutor(
                MAX_CONCURRENCY, MAX_CONCURRENCY,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "gitlab-project-loader");
                    t.setDaemon(true);
                    return t;
                }
        );

        try {
            // BFS 队列：用 GroupEntry.fullPath 作为去重 key（GitLab 内 fullPath 唯一）
            LinkedBlockingQueue<GroupEntry> queue = new LinkedBlockingQueue<>();
            // 收集结果（线程安全：synchronizedList）
            List<ProjectEntry> result = Collections.synchronizedList(new ArrayList<>());
            AtomicLong processed = new AtomicLong(0);
            // 异常计数器（用于日志）
            AtomicLong failed = new AtomicLong(0);

            // 1) 加载顶级组
            List<GroupEntry> roots;
            try {
                roots = sharedApi.listRootGroups(200);
            } catch (Exception e) {
                LOG.warn("loadAllProjects: failed to load root groups", e);
                cachedAllProjects = Collections.emptyList();
                return cachedAllProjects;
            }
            for (GroupEntry g : roots) queue.add(g);

            // 2) BFS + 并发：每次从队列取一个组，并发 fetch 它的子组与项目
            // 维护一组活跃 Future；每完成一个就尝试从队列取下一个继续（直到队列空且所有 Future 完成）
            java.util.List<CompletableFuture<Void>> inflight = new ArrayList<>();

            while (!queue.isEmpty() || !inflight.isEmpty()) {
                // 取出当前可启动的批次（最多 MAX_CONCURRENCY 个）
                GroupEntry g;
                while (inflight.size() < MAX_CONCURRENCY && (g = queue.poll()) != null) {
                    GroupEntry group = g;
                    CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                        try {
                            GroupChildrenView view = loadChildrenInto(sharedApi, group, result);
                            // 子组入队继续 BFS（record 访问器：subGroups() 无 get 前缀）
                            for (GroupEntry sub : view.subGroups()) {
                                queue.add(sub);
                            }
                            processed.incrementAndGet();
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            LOG.warn("loadAllProjects: failed to load children for group " + group.fullPath, e);
                        }
                    }, pool);
                    inflight.add(f);
                }
                // 等待至少一个完成，然后从 inflight 移除
                if (!inflight.isEmpty()) {
                    CompletableFuture<?> done = CompletableFuture.anyOf(
                            inflight.toArray(new CompletableFuture<?>[0])
                    );
                    try {
                        done.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOG.warn("loadAllProjects: timeout waiting for fetch", e);
                    }
                    // 移除已完成的 future
                    inflight.removeIf(CompletableFuture::isDone);
                }
            }

            LOG.info("loadAllProjects: processed=" + processed.get()
                    + " failed=" + failed.get()
                    + " total=" + result.size());
            // 缓存：返回不可变副本
            List<ProjectEntry> immutable = Collections.unmodifiableList(new ArrayList<>(result));
            cachedAllProjects = immutable;
            return immutable;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 把一个组下的直接项目追加到 [result]，并返回其 [GroupChildrenView]（含子组）。
     * 子组由外层 BFS 循环入队。
     */
    private GroupChildrenView loadChildrenInto(
            @NotNull GitLabApiService api,
            @NotNull GroupEntry group,
            @NotNull List<ProjectEntry> result
    ) throws Exception {
        List<GroupEntry> subGroups = api.listSubGroups(group.id, 200);
        List<ProjectEntry> projects = new ArrayList<>();
        for (GitLabProject gp : api.listDirectProjects(group.id, 200)) {
            if (gp.pathWithNamespace == null || gp.pathWithNamespace.isEmpty()) continue;
            // 用 group.fullPath 作为父组路径（保留完整层级，供弹窗副行渲染）
            projects.add(new ProjectEntry(group.name, "", "", gp.pathWithNamespace));
        }
        result.addAll(projects);
        return new GroupChildrenView(subGroups, projects);
    }

    /**
     * 清空底层 API 缓存 + 全量加载结果缓存
     */
    public void clearCache() {
        GitLabApiService.clearCache();
        cachedAllProjects = null;
    }
}