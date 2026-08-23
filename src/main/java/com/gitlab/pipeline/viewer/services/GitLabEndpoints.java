package com.gitlab.pipeline.viewer.services;

/**
 * GitLab REST API v4 端点与查询参数常量。
 * <p>
 * 将散落在 {@link GitLabApiService} 各方法里的魔法 URL 片段与查询参数
 * （"/projects/%s"、"per_page"、"top_level_only=true"…）统一收口到此处，
 * 一处修改全局生效，并避免路由写错导致接口调不通。
 */
public final class GitLabEndpoints {
    private GitLabEndpoints() {
    }

    /**
     * API 版本前缀
     */
    public static final String API_V4 = "/api/v4";

    // ---------------------------- 资源路径模板（%s 为 path 参数占位） ----------------------------
    /**
     * 项目，path 已 URL 编码
     */
    public static final String PROJECT = "/projects/%s";
    /**
     * 流水线列表 / 触发流水线（新版端点）
     */
    public static final String PIPELINES = "/projects/%s/pipelines";
    /**
     * 老版本 GitLab（8.14 起）的触发流水线端点（单数），17.0 起被移除；
     * 老实例没有复数端点，路由层直接回 404 {"error":"404 Not Found"}，需要降级走此端点
     */
    public static final String PIPELINE_CREATE_LEGACY = "/projects/%s/pipeline";
    /**
     * 单条流水线详情
     */
    public static final String PIPELINE = "/projects/%s/pipelines/%s";
    /**
     * 流水线下的 Job 列表
     */
    public static final String PIPELINE_JOBS = "/projects/%s/pipelines/%s/jobs";
    /**
     * 分支列表
     */
    public static final String BRANCHES = "/projects/%s/repository/branches";
    /**
     * 顶级 / 全量项目组列表
     */
    public static final String GROUPS = "/groups";
    /**
     * 某组的直接子组
     */
    public static final String GROUP_SUBGROUPS = "/groups/%s/subgroups";
    /**
     * 某组的直接项目
     */
    public static final String GROUP_PROJECTS = "/groups/%s/projects";
    /**
     * 单个 Job
     */
    public static final String JOB = "/projects/%s/jobs/%s";
    /**
     * Job 构建日志（纯文本）
     */
    public static final String JOB_TRACE = "/projects/%s/jobs/%s/trace";

    // ---------------------------- 动作后缀（POST 无请求体） ----------------------------
    public static final String ACTION_CANCEL = "/cancel";
    public static final String ACTION_RETRY = "/retry";
    public static final String ACTION_PLAY = "/play";

    // ---------------------------- 查询参数与取值 ----------------------------
    public static final String PARAM_PER_PAGE = "per_page";
    public static final String PARAM_PAGE = "page";
    public static final String PARAM_ORDER_BY = "order_by";
    public static final String PARAM_SORT = "sort";
    public static final String PARAM_TOP_LEVEL_ONLY = "top_level_only";

    public static final String ORDER_ID = "id";
    public static final String ORDER_NAME = "name";
    public static final String SORT_DESC = "desc";
    public static final String SORT_ASC = "asc";
    public static final String BRANCH_SORT = "name_asc";
    public static final String BOOL_TRUE = "true";

    // ---------------------------- 请求头与表单字段 ----------------------------
    public static final String HEADER_PRIVATE_TOKEN = "PRIVATE-TOKEN";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
    public static final String FORM_REF = "ref";
    /**
     * 表单变量前缀，形如 variables[key]，用于自定义变量开关
     */
    public static final String FORM_VAR_PREFIX = "variables[";
    public static final String FORM_VAR_SUFFIX = "]";
}