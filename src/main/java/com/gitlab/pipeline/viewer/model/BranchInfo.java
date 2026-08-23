package com.gitlab.pipeline.viewer.model;

import com.gitlab.pipeline.viewer.services.GitLabFieldNames;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * GitLab 分支实体（来自 GET /projects/:id/repository/branches），仅需分支名
 */
public class BranchInfo {
    public final String name;

    private BranchInfo(String name) {
        this.name = name;
    }

    /**
     * 工厂方法：由 GitLab API 返回的 JSON 对象构建实体（缺失字段取空串）
     */
    public static BranchInfo from(JsonObject o) {
        return new BranchInfo(JsonUtil.stringValue(o, GitLabFieldNames.NAME, ""));
    }
}