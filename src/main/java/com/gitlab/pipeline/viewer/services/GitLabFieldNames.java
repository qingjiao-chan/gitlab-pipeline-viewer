package com.gitlab.pipeline.viewer.services;

/**
 * GitLab API JSON 字段名常量。
 * <p>
 * 把散落在各模型 / UI 中的魔法字段字符串（"id"、"path_with_namespace"、"parent_id" 等）
 * 统一集中在此，字段名写错时 IDE 能即时标错，且一次修改全局生效，杜绝笔误引入的隐晦 Bug。
 * 命名按资源分组，方便定位与维护。
 */
public final class GitLabFieldNames {
    private GitLabFieldNames() {
    }

    // ------------------------- 通用 / 用户 -------------------------
    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String WEB_URL = "web_url";
    public static final String USER = "user";

    // ------------------------- 项目 -------------------------
    public static final String PATH = "path";
    public static final String PATH_WITH_NAMESPACE = "path_with_namespace";
    public static final String DEFAULT_BRANCH = "default_branch";

    // ------------------------- 流水线 -------------------------
    public static final String IID = "iid";
    public static final String REF = "ref";
    public static final String SHA = "sha";
    public static final String STATUS = "status";
    public static final String SOURCE = "source";
    public static final String CREATED_AT = "created_at";
    public static final String UPDATED_AT = "updated_at";
    public static final String DURATION = "duration";

    // ------------------------- Job -------------------------
    public static final String STAGE = "stage";
    public static final String STARTED_AT = "started_at";
    public static final String FINISHED_AT = "finished_at";

    // ------------------------- 项目组 -------------------------
    public static final String FULL_PATH = "full_path";
    public static final String PARENT_ID = "parent_id";
}