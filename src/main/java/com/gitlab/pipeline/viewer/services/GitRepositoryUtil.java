package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.ProjectEntry;
import com.gitlab.pipeline.viewer.util.GitUrlUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     */
    public static @NotNull List<ProjectEntry> collectProjects() {
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
                        String host = GitUrlUtil.extractHost(url);
                        String path = GitUrlUtil.extractPath(url);
                        if (host.isEmpty() || path.isEmpty()) {
                            continue;
                        }
                        String key = host + "/" + path;
                        map.putIfAbsent(key, new ProjectEntry(project.getName(), url, host, path));
                    }
                }
            }
        }
        return new ArrayList<>(map.values());
    }
}