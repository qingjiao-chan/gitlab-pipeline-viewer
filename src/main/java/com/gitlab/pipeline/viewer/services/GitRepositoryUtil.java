package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.ProjectEntry;
import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.gitlab.pipeline.viewer.util.GitUrlUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收集当前 IDEA 实例中所有打开项目（含附加到同一窗口的项目）的 Git 远程仓库地址。
 * 通过 git4idea 官方 API 获取，无需解析 .git/config。
 * <p>
 * 对应规范中的「项目检测」职责，作为下层工具被 {@link ProjectSelectionService} 复用。
 */
public final class GitRepositoryUtil {
    private GitRepositoryUtil() {
    }

    /**
     * 扫描当前 IDEA 窗口所有打开项目（含附加项目），提取每个 Git 远程仓库的
     * GitLab host + 项目路径，去重后返回。
     * 多账号下保留与「任意已配置账号」host+端口一致的远程仓库（条目的 host 字段
     * 供 UI 标注 @host 归属）；未配置任何账号时不过滤（沿用 v1.0 行为）。
     * <p>
     * 线程模型：全程在 IDE 读动作（{@link ReadAction}）内执行，因此既可在 EDT 调用，
     * 也可在后台线程调用（启动期的项目自动检测即运行在 pooled 线程上），
     * 不会与索引/VFS 写动作竞争。
     */
    public static @NotNull List<ProjectEntry> collectProjects() {
        return ReadAction.compute(() -> {
            GitLabSettings settings = GitLabSettings.getInstance();
            Set<String> allowedHosts = new HashSet<>();
            for (com.gitlab.pipeline.viewer.model.Account account : settings.getAccounts()) {
                String hp = GitUrlUtil.extractHostWithPort(account.url());
                if (!hp.isEmpty()) {
                    allowedHosts.add(hp.toLowerCase());
                }
            }
            Map<String, ProjectEntry> map = new LinkedHashMap<>();
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed()) {
                    continue;
                }
                GitRepositoryManager manager;
                try {
                    manager = GitRepositoryManager.getInstance(project);
                } catch (Exception ignored) {
                    continue;
                }
                for (GitRepository repo : manager.getRepositories()) {
                    for (GitRemote remote : repo.getRemotes()) {
                        for (String url : remote.getUrls()) {
                            String hostPort = GitUrlUtil.extractHostWithPort(url);
                            String host = GitUrlUtil.extractHost(url);
                            String path = GitUrlUtil.extractPath(url);
                            if (host.isEmpty() || path.isEmpty()) {
                                continue;
                            }
                            // 已配置账号时，只保留属于任一账号 host+端口的远程仓库
                            if (!allowedHosts.isEmpty()
                                    && !allowedHosts.contains(hostPort.toLowerCase())) {
                                continue;
                            }
                            String key = host + "/" + path;
                            map.putIfAbsent(key, new ProjectEntry(project.getName(), url, host, path));
                        }
                    }
                }
            }
            return new ArrayList<>(map.values());
        });
    }
}