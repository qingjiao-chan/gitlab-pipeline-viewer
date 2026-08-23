package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.*;
import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据获取与缓存编排（project 级 service）。
 * <p>
 * 把「选项目 -> 拉流水线 -> 补详情 -> 选流水线 -> 拉 Jobs -> 拉日志」的完整数据流
 * 从 UI 抽离：UI 只在后台任务中调用本服务的纯数据方法，不再直接接触
 * {@link GitLabApiService} 与线程池。本服务不含任何 Swing / EDT 逻辑。
 * <p>
 * 每个方法都按最新设置构建底层 API 客户端，因此设置变更后立即生效。
 * 用 {@code @Service} 轻量注册（规范 §2），经 {@code project.getService()} 获取。
 */
@Service
public final class PipelineDataService {

    public PipelineDataService() {
    }

    public static PipelineDataService getInstance(@NotNull Project project) {
        return project.getService(PipelineDataService.class);
    }

    /**
     * 按最新设置构建底层 API 客户端（本服务不缓存连接，保证设置改动即刻生效）
     */
    private GitLabApiService api() {
        GitLabSettings s = GitLabSettings.getInstance();
        return new GitLabApiService(s.getGitlabUrl(), s.getToken(), s.getRequestTimeoutSeconds());
    }

    /**
     * 每页流水线条数（来自设置，默认 10）；列表接口会多取 1 条用于判断是否还有下一页
     */
    private static int pageSize() {
        return Math.max(5, GitLabSettings.getInstance().getPipelinePageSize());
    }

    /**
     * 完整加载第 {@code page} 页流水线：项目 -> 流水线列表(补详情) -> 选中流水线的 Jobs 与日志。
     *
     * @param path           项目路径（group/subgroup/project）
     * @param page           页码（从 1 开始）
     * @param keepPipelineId 期望优先选中的流水线 id，无则该页第一个运行中/首条
     * @param keepJobId      期望优先选中的 Job id，同上
     */
    public PipelineSnapshot loadPage(@NotNull String path, int page,
                                     long keepPipelineId, long keepJobId) throws Exception {
        GitLabApiService api = api();
        long projectId = api.getProject(path).id;
        List<PipelineInfo> pipelines = api.listPipelines(projectId, pageSize() + 1, Math.max(1, page));
        boolean hasNext = pipelines.size() > pageSize();
        if (hasNext) {
            pipelines = new ArrayList<>(pipelines.subList(0, pageSize()));
        }
        fillDetails(api, projectId, pipelines);

        PipelineInfo target = pickPipeline(pipelines, keepPipelineId);
        List<JobInfo> jobs = new ArrayList<>();
        long targetJobId = -1;
        String trace = null;
        if (target != null) {
            jobs = api.listJobs(projectId, target.id);
            JobInfo job = pickJob(jobs, keepJobId);
            if (job != null) {
                targetJobId = job.id;
                trace = api.getJobTrace(projectId, job.id);
            }
        }
        return new PipelineSnapshot(projectId, pipelines, hasNext, target, jobs, targetJobId, trace);
    }

    /**
     * 加载流水线下的 Jobs 及其选中 Job 的日志（不重新拉流水线列表）
     */
    public JobsView loadJobs(long projectId, long pipelineId, long keepJobId) throws Exception {
        GitLabApiService api = api();
        List<JobInfo> jobs = api.listJobs(projectId, pipelineId);
        JobInfo job = pickJob(jobs, keepJobId);
        long targetJobId = -1;
        String trace = null;
        if (job != null) {
            targetJobId = job.id;
            trace = api.getJobTrace(projectId, job.id);
        }
        return new JobsView(jobs, targetJobId, trace);
    }

    /**
     * 打开 / 刷新单个 Job 的日志
     */
    public String loadTrace(long projectId, long jobId) throws Exception {
        return api().getJobTrace(projectId, jobId);
    }

    /**
     * 加载项目分支名列表（供触发流水线对话框使用）
     */
    public List<String> loadBranches(long projectId) throws Exception {
        List<String> branches = new ArrayList<>();
        for (var b : api().listBranches(projectId)) {
            if (!b.name.isEmpty()) {
                branches.add(b.name);
            }
        }
        return branches;
    }

    /**
     * 触发流水线（支持自定义变量）
     */
    public void triggerPipeline(long projectId, @NotNull String ref, @Nullable Map<String, String> variables) throws Exception {
        api().triggerPipeline(projectId, ref, variables);
    }

    /**
     * 取消流水线
     */
    public void cancelPipeline(long projectId, long pipelineId) throws Exception {
        api().cancelPipeline(projectId, pipelineId);
    }

    /**
     * 重试流水线
     */
    public void retryPipeline(long projectId, long pipelineId) throws Exception {
        api().retryPipeline(projectId, pipelineId);
    }

    /**
     * 取消 Job
     */
    public void cancelJob(long projectId, long jobId) throws Exception {
        api().cancelJob(projectId, jobId);
    }

    /**
     * 执行手动 Job（when: manual）
     */
    public void playJob(long projectId, long jobId) throws Exception {
        api().playJob(projectId, jobId);
    }

    /**
     * 重试 Job
     */
    public void retryJob(long projectId, long jobId) throws Exception {
        api().retryJob(projectId, jobId);
    }

    /**
     * 清空底层 API 缓存（项目/列表刷新用）
     */
    public void clearCache() {
        GitLabApiService.clearCache();
    }

    // ---------------------------------------------------------------- 数据流内的领域选择逻辑

    /**
     * 并行拉取每条流水线详情，补齐"耗时/触发人"（列表接口不返回这两项）
     */
    private static void fillDetails(GitLabApiService api, long projectId, List<PipelineInfo> pipelines) {
        if (pipelines == null || pipelines.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, pipelines.size()));
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (PipelineInfo p : pipelines) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        p.fillDetail(api.getPipelineDetail(projectId, p.id));
                    } catch (Exception ignored) {
                        // 详情拉取失败不影响列表展示，耗时/触发人保持默认
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 优先选中 keepId 对应流水线；无则第一个运行中/等待中的，再退回首条
     */
    private static PipelineInfo pickPipeline(List<PipelineInfo> pipelines, long keepId) {
        if (keepId != -1) {
            for (PipelineInfo p : pipelines) {
                if (p.id == keepId) {
                    return p;
                }
            }
        }
        for (PipelineInfo p : pipelines) {
            if (PipelineStatus.isActive(p.status)) {
                return p;
            }
        }
        return pipelines.isEmpty() ? null : pipelines.get(0);
    }

    /**
     * 优先选中 keepId 对应 Job；无则第一个运行中/等待中的，再退回首个
     */
    private static JobInfo pickJob(List<JobInfo> jobs, long keepId) {
        if (keepId != -1) {
            for (JobInfo j : jobs) {
                if (j.id == keepId) {
                    return j;
                }
            }
        }
        for (JobInfo j : jobs) {
            if (PipelineStatus.isActive(j.status)) {
                return j;
            }
        }
        return jobs.isEmpty() ? null : jobs.get(0);
    }
}