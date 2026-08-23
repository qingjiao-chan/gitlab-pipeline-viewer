package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.GitLabProject;
import com.gitlab.pipeline.viewer.model.GroupChildrenView;
import com.gitlab.pipeline.viewer.model.GroupEntry;
import com.gitlab.pipeline.viewer.model.ProjectEntry;
import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目选择与上下文管理（project 级 service）。
 * <p>
 * 把项目树形选择所需的「顶级项目组 / 子组与直接项目」的数据获取从 {@code ProjectTreeSelector}
 * 抽离：UI 组件只在后台任务中取数据快照并渲染树，不再直接接触 {@link GitLabApiService}。
 * 树形选择不缓存连接，每次按最新设置构建底层客户端，设置改动即时生效。
 * <p>
 * 用 {@code @Service} 轻量注册（规范 §2），经 {@code project.getService()} 获取。
 */
@Service
public final class ProjectSelectionService {

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
     * 清空底层 API 缓存（刷新项目时用）
     */
    public void clearCache() {
        GitLabApiService.clearCache();
    }
}