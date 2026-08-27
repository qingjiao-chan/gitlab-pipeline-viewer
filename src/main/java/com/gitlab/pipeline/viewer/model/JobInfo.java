package com.gitlab.pipeline.viewer.model;

import com.gitlab.pipeline.viewer.services.GitLabFieldNames;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * 流水线中的一个 Job（构建任务），日志来自 Job 的 trace 接口
 */
public class JobInfo {
    public final long id;
    public final String name;
    public final String stage;
    public final String status;
    public final String ref;
    public final String startedAt;
    public final String finishedAt;
    public final long durationSeconds;

    private JobInfo(long id, String name, String stage, String status, String ref,
                    String startedAt, String finishedAt, long durationSeconds) {
        this.id = id;
        this.name = name;
        this.stage = stage;
        this.status = status;
        this.ref = ref;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationSeconds = durationSeconds;
    }

    /**
     * 工厂方法：由 GitLab API 返回的 JSON 对象构建实体（缺失字段取安全默认值）
     */
    public static JobInfo from(JsonObject o) {
        return new JobInfo(
                JsonUtil.longValue(o, GitLabFieldNames.ID, 0),
                JsonUtil.stringValue(o, GitLabFieldNames.NAME, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.STAGE, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.STATUS, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.REF, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.STARTED_AT, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.FINISHED_AT, ""),
                JsonUtil.longValue(o, GitLabFieldNames.DURATION, 0)
        );
    }

    @Override
    public String toString() {
        return name + " (" + status + ")";
    }
}
