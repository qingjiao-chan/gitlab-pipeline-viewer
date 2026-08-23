package com.gitlab.pipeline.viewer.model;

import com.gitlab.pipeline.viewer.services.GitLabFieldNames;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.JsonObject;

/**
 * 一条 GitLab 流水线信息（来自 GET /projects/:id/pipelines）
 */
public class PipelineInfo {
    public final long id;
    public final long iid;
    public final String ref;
    public final String sha;
    public final String status;
    public final String source;
    public final String createdAt;
    public final String updatedAt;
    /**
     * 耗时（秒）。列表接口通常不返回，需再拉详情补充，故非 final
     */
    public long durationSeconds;
    /**
     * 触发人姓名。同上，列表接口通常不返回
     */
    public String user;
    public final String webUrl;

    private PipelineInfo(long id, long iid, String ref, String sha, String status, String source,
                         String createdAt, String updatedAt, long durationSeconds, String user, String webUrl) {
        this.id = id;
        this.iid = iid;
        this.ref = ref;
        this.sha = sha;
        this.status = status;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.durationSeconds = durationSeconds;
        this.user = user;
        this.webUrl = webUrl;
    }

    /**
     * 工厂方法：由 GitLab API 返回的 JSON 对象构建实体（缺失字段取安全默认值）
     */
    public static PipelineInfo from(JsonObject o) {
        JsonObject userObj = JsonUtil.object(o, GitLabFieldNames.USER);
        String userName = userObj != null ? JsonUtil.stringValue(userObj, GitLabFieldNames.NAME, "") : "";
        return new PipelineInfo(
                JsonUtil.longValue(o, GitLabFieldNames.ID, 0),
                JsonUtil.longValue(o, GitLabFieldNames.IID, 0),
                JsonUtil.stringValue(o, GitLabFieldNames.REF, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.SHA, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.STATUS, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.SOURCE, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.CREATED_AT, ""),
                JsonUtil.stringValue(o, GitLabFieldNames.UPDATED_AT, ""),
                JsonUtil.longValue(o, GitLabFieldNames.DURATION, 0),
                userName,
                JsonUtil.stringValue(o, GitLabFieldNames.WEB_URL, "")
        );
    }

    /**
     * 用单条流水线详情实体（GET /pipelines/:id）补充耗时/触发人（列表接口不返回，需并行拉详情）
     */
    public void fillDetail(PipelineInfo detail) {
        if (detail == null) {
            return;
        }
        if (durationSeconds <= 0) {
            durationSeconds = detail.durationSeconds;
        }
        if (user == null || user.isEmpty()) {
            user = detail.user;
        }
    }

    @Override
    public String toString() {
        return "#" + iid + " " + ref + " (" + status + ")";
    }
}
