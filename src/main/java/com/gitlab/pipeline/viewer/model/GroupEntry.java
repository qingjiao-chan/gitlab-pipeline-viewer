package com.gitlab.pipeline.viewer.model;

import com.gitlab.pipeline.viewer.services.GitLabFieldNames;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * GitLab 中的一个项目组（来自 GET /groups），用于项目级联选择的第一级
 */
public class GroupEntry {
    public final long id;
    public final String fullPath;
    public final String name;

    private GroupEntry(long id, String fullPath, String name) {
        this.id = id;
        this.fullPath = fullPath;
        this.name = name;
    }

    /**
     * 工厂方法：由 GitLab API 返回的 JSON 对象构建实体（缺失字段取安全默认值）
     */
    public static GroupEntry from(JsonObject o) {
        return new GroupEntry(
                JsonUtil.longValue(o, GitLabFieldNames.ID, 0),
                JsonUtil.stringValue(o, GitLabFieldNames.FULL_PATH, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.NAME, "")
        );
    }

    @Override
    public String toString() {
        return fullPath.isEmpty() ? name : fullPath;
    }
}
