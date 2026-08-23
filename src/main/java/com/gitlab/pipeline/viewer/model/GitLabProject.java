package com.gitlab.pipeline.viewer.model;

import com.gitlab.pipeline.viewer.services.GitLabFieldNames;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * GitLab 项目实体（来自 GET /projects/:path 或 /groups/:id/projects），用数字 id 进行后续操作
 */
public class GitLabProject {
    public final long id;
    /**
     * 项目路径（不含 host），形如 group/subgroup/project
     */
    public final String pathWithNamespace;
    /**
     * 项目名（最后一级）
     */
    public final String name;
    public final String defaultBranch;
    public final String webUrl;

    private GitLabProject(long id, String pathWithNamespace, String name, String defaultBranch, String webUrl) {
        this.id = id;
        this.pathWithNamespace = pathWithNamespace;
        this.name = name;
        this.defaultBranch = defaultBranch;
        this.webUrl = webUrl;
    }

    /**
     * 工厂方法：由 GitLab API 返回的 JSON 对象构建实体（缺失字段取安全默认值）
     */
    public static GitLabProject from(JsonObject o) {
        return new GitLabProject(
                JsonUtil.longValue(o, GitLabFieldNames.ID, 0),
                JsonUtil.stringValue(o, GitLabFieldNames.PATH_WITH_NAMESPACE, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.NAME, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.DEFAULT_BRANCH, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.WEB_URL, "")
        );
    }
}