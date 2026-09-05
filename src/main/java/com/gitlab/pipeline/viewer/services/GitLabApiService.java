package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.*;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * GitLab REST API v4 客户端（仓储层 / Repository，即规范中的 {@code GitLabApiService}）。
 * 使用 JDK 自带 java.net.http 与 IDE 内置的 gson，无额外第三方依赖。
 * 认证方式：PRIVATE-TOKEN（私有访问令牌，需勾选 api 权限）。
 * <p>
 * 设计要点：
 * - 对外只暴露「领域实体」（PipelineInfo / JobInfo / GroupEntry / GitLabProject / BranchInfo），
 * 调用方不再接触裸 {@code JsonObject}/{@code JsonArray}，也无需在 UI 里做 JSON 字段解析；
 * - 端点与查询参数、JSON 字段名统一收敛到 {@link GitLabEndpoints} / {@link GitLabFieldNames}，消灭魔法字符串；
 * - 实体经各模型的 {@code from(JsonObject)} 工厂方法解析，字段缺失自动取安全默认值。
 * <p>
 * 缓存策略（GET 只读接口做 TTL 缓存，写操作后自动清空）：
 * - 项目信息 / 分支列表 / 项目组：较长 TTL（几乎不变）
 * - 流水线详情：较短 TTL（耗时/触发人变化缓慢）
 * - 流水线列表 / Job 列表：短 TTL（兼顾自动刷新实时性与接口频率）
 * - Job 日志：不缓存（内容随构建实时变化）
 * 缓存用 static 共享，因为面板每次操作都会 new 一个客户端实例；
 * key 是完整请求 URL（含项目/流水线 id），多项目间不会串数据。
 */
public class GitLabApiService {
    private final String baseUrl;
    private final String token;
    private final HttpClient client;
    private final Duration timeout;

    /**
     * 共享 TTL 缓存：key = 完整请求 URL
     */
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // ---- 各类接口的缓存 TTL（毫秒） ----
    private static final long TTL_PROJECT_INFO = 300_000;   // 项目信息/分支/项目组：几乎不变
    private static final long TTL_PIPELINE_DETAIL = 60_000; // 流水线详情：耗时/触发人变化缓慢
    private static final long TTL_LIST_GROUP = 300_000;     // 项目组树状懒加载
    private static final long TTL_LIST_CACHE = 5_000;       // 流水线/Job 列表：兼顾实时性与频率

    private static final class CacheEntry {
        final JsonElement value;
        final long expiresAt;

        CacheEntry(JsonElement value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    public GitLabApiService(String baseUrl, String token, int timeoutSeconds) {
        this.baseUrl = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        long timeoutSec = Math.max(5, timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.timeout = Duration.ofSeconds(Math.max(15, timeoutSec * 6));
    }

    /**
     * 拼接 API 地址：baseUrl + /api/v4 + 渲染后的资源路径模板
     */
    private String api(String template, Object... args) {
        return baseUrl + GitLabEndpoints.API_V4 + String.format(template, args);
    }

    private String apiQuery(String template, String query, Object... args) {
        return api(template, args) + query;
    }

    /**
     * 追加一组查询参数，返回 query 串（约定参数名与取值来自 GitLabEndpoints，宽高均为已编码字符串）
     */
    private static String query(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header(GitLabEndpoints.HEADER_PRIVATE_TOKEN, token);
    }

    /**
     * 根据项目路径获取项目信息，返回项目实体（含数字 id）
     */
    public GitLabProject getProject(String projectPath) throws Exception {
        String url = apiQuery(GitLabEndpoints.PROJECT, "", encode(projectPath));
        JsonObject o = cachedGet(url, TTL_PROJECT_INFO).getAsJsonObject();
        return GitLabProject.from(o);
    }

    /**
     * 获取流水线列表（按 id 倒序，最新在前），支持分页；page 从 1 开始
     */
    public List<PipelineInfo> listPipelines(long projectId, int perPage, int page) throws Exception {
        String url = apiQuery(GitLabEndpoints.PIPELINES,
                query(Map.of(
                        GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(perPage),
                        GitLabEndpoints.PARAM_PAGE, String.valueOf(Math.max(1, page)),
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_ID,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_DESC)),
                projectId);
        return mapArray(cachedGet(url, TTL_LIST_CACHE));
    }

    /**
     * 获取流水线下的 Job 列表
     */
    public List<JobInfo> listJobs(long projectId, long pipelineId) throws Exception {
        String url = apiQuery(GitLabEndpoints.PIPELINE_JOBS,
                query(Map.of(GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(100))),
                projectId, pipelineId);
        return mapJobs(cachedGet(url, TTL_LIST_CACHE));
    }

    /**
     * 获取项目分支列表（按名称升序）
     */
    public List<BranchInfo> listBranches(long projectId) throws Exception {
        String url = apiQuery(GitLabEndpoints.BRANCHES,
                query(Map.of(
                        GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(100),
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.BRANCH_SORT)),
                projectId);
        return mapArray(cachedGet(url, TTL_PROJECT_INFO), BranchInfo::from);
    }

    /**
     * 获取顶级项目组（用于树形选择第一层；懒加载）。
     * 请求带 top_level_only=true 只返回顶层组；旧版 GitLab / 部分部署不支持该参数时会
     * 把子组也一并返回，这里再按 parent_id 兜底过滤一次，保证第一层只含顶级组。
     */
    public List<GroupEntry> listRootGroups(int perPage) throws Exception {
        String url = apiQuery(GitLabEndpoints.GROUPS,
                query(Map.of(
                        GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(perPage),
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_NAME,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_ASC,
                        GitLabEndpoints.PARAM_TOP_LEVEL_ONLY, GitLabEndpoints.BOOL_TRUE)));
        JsonElement elem = cachedGet(url, TTL_LIST_GROUP);
        List<GroupEntry> top = new ArrayList<>();
        if (elem instanceof JsonArray arr) {
            for (JsonElement el : arr) {
                if (el != null && el.isJsonObject() && JsonUtil.isRootLevel(el.getAsJsonObject())) {
                    top.add(GroupEntry.from(el.getAsJsonObject()));
                }
            }
        }
        return top;
    }

    /**
     * 获取指定组的直接子组（GET /groups/:id/subgroups），用于树形选择按层懒加载
     */
    public List<GroupEntry> listSubGroups(long groupId, int perPage) throws Exception {
        String url = apiQuery(GitLabEndpoints.GROUP_SUBGROUPS,
                query(Map.of(
                        GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(perPage),
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_NAME,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_ASC)),
                groupId);
        return mapArray(cachedGet(url, TTL_LIST_GROUP), GroupEntry::from);
    }

    /**
     * 获取指定组的直接项目（不含 include_subgroups，即不含子组项目），用于树形选择展开时懒加载
     */
    public List<GitLabProject> listDirectProjects(long groupId, int perPage) throws Exception {
        String url = apiQuery(GitLabEndpoints.GROUP_PROJECTS,
                query(Map.of(
                        GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(perPage),
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_NAME,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_ASC)),
                groupId);
        return mapArray(cachedGet(url, TTL_LIST_GROUP), GitLabProject::from);
    }

    /**
     * 获取 Job 完整构建日志（纯文本）
     */
    public String getJobTrace(long projectId, long jobId) throws Exception {
        String url = api(GitLabEndpoints.JOB_TRACE, projectId, jobId);
        HttpResponse<String> resp = client.send(get(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return resp.body();
        }
        throw new GitLabApiException(resp.statusCode(), resp.body());
    }

    /**
     * 增量拉取 Job 构建日志（Range 请求头），避免每次自动刷新都下载整份日志：
     * <ul>
     *   <li>fromByte=0：全量拉取（200），full=true；</li>
     *   <li>fromByte&gt;0：请求 Range: bytes=&lt;fromByte&gt;- ；GitLab 运行中返回 206 部分内容
     *       （full=false），已结束返回 200 完整内容（full=true；服务器忽略 Range 时同样返回 200
     *       完整内容，可安全回退整份替换），或 416（偏移已到末尾，无新内容）。</li>
     * </ul>
     * 多字节 UTF-8 字符可能被 Range 边界切断：上一块末尾未完成的字节由调用方经 [carry] 传入，
     * 与本次返回合并解码后再把新的未完成尾部随结果带回，保证拼接后日志不出现乱码。
     */
    public JobTraceResult getJobTrace(long projectId, long jobId, long fromByte, byte[] carry) throws Exception {
        String url = api(GitLabEndpoints.JOB_TRACE, projectId, jobId);
        HttpRequest.Builder builder = get(url);
        if (fromByte > 0) {
            builder.header(GitLabEndpoints.HEADER_RANGE, "bytes=" + fromByte + "-");
        }
        HttpResponse<byte[]> resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        int code = resp.statusCode();
        if (code == 416) {
            // 请求偏移已到日志末尾：没有新内容可追加；carry 保留，若日志后续追加仍可无缝拼接
            return new JobTraceResult("", fromByte, carry, false);
        }
        if (code != 200 && code != 206) {
            throw new GitLabApiException(code, new String(resp.body(), StandardCharsets.UTF_8));
        }
        boolean partial = code == 206;
        byte[] body = resp.body() == null ? new byte[0] : resp.body();

        if (!partial) {
            // 200 完整响应：要么是首屏（fromByte==0），要么是服务器忽略了 Range 又返回整份日志。
            if (fromByte <= 0) {
                // 首屏：整份解码；carry 为末尾未完成的多字节尾部
                Utf8Split split = splitUtf8(body);
                return new JobTraceResult(
                        split.text, split.text.getBytes(StandardCharsets.UTF_8).length, split.carry, true);
            }
            // Range 被服务器忽略：在客户端从整份返回里切出 [fromByte, 末尾) 作为增量。
            // 否则每次自动刷新都会重新下载/替换/重建整份大日志，表现为日志"突然一下输出大量、不流畅"。
            if (fromByte >= body.length) {
                // 已推进到日志末尾，无新内容
                return new JobTraceResult("", fromByte, carry, false);
            }
            int start = (int) fromByte;
            byte[] tail = Arrays.copyOfRange(body, start, body.length);
            byte[] merged = concat(carry, tail);
            Utf8Split split = splitUtf8(merged);
            // 本次已把 [fromByte, 文件末尾) 一眼拿全，offset 推进到末尾；作为增量追加（full=false）
            return new JobTraceResult(split.text, body.length, split.carry, false);
        }

        // 206 部分响应（Range 生效）：body = [fromByte, serverEnd)，拼上上次未完成的多字节尾部再解码
        byte[] merged = concat(carry, body);
        Utf8Split split = splitUtf8(merged);
        String cr = resp.headers().firstValue(GitLabEndpoints.HEADER_CONTENT_RANGE).orElse(null);
        long end = cr == null ? -1 : contentRangeEnd(cr);
        long nextOffset = end >= 0 ? end + 1 : fromByte + body.length;
        return new JobTraceResult(split.text, nextOffset, split.carry, false);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * 从 Content-Range 头解析末字节偏移（"bytes 0-1023/146515" -> 1023）；解析失败返回 -1
     */
    private static long contentRangeEnd(String header) {
        int slash = header.indexOf('/');
        String range = slash >= 0 ? header.substring(0, slash) : header;
        int dash = range.lastIndexOf('-');
        if (dash < 0) return -1;
        try {
            return Long.parseLong(range.substring(dash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class Utf8Split {
        final String text;
        final byte[] carry;

        Utf8Split(String text, byte[] carry) {
            this.text = text;
            this.carry = carry;
        }
    }

    /**
     * 按 UTF-8 解码字节块；若末尾存在未完成的多字节序列，截断保留到 carry 供下一块拼接，
     * 避免 Range 分块切断多字节字符导致乱码/搜索错位。
     */
    private static Utf8Split splitUtf8(byte[] bytes) {
        int n = bytes.length;
        int cut = n;
        for (int i = n - 1; i >= Math.max(0, n - 4); i--) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                break; // 结尾是 ASCII，之前内容完整
            }
            if (b >= 0xC0) { // 多字节序列首字节
                int expected = b < 0xE0 ? 2 : (b < 0xF0 ? 3 : 4);
                if (n - i < expected) {
                    cut = i; // 末尾序列不完整：截断并保留为 carry
                }
                break;
            }
            // 0x80-0xBF：续字节，继续向前找首字节
        }
        String text = new String(bytes, 0, cut, StandardCharsets.UTF_8);
        byte[] carry = cut < n ? Arrays.copyOfRange(bytes, cut, n) : new byte[0];
        return new Utf8Split(text, carry);
    }

    /**
     * 获取单个 Job 的实时状态（自动刷新日志时同步更新状态显示）。
     * 用短 TTL 缓存：自动刷新间隔 ≥5s，5s 缓存与刷新频率一致，兼顾实时性与接口压力。
     */
    public JobInfo getJob(long projectId, long jobId) throws Exception {
        String url = api(GitLabEndpoints.JOB, projectId, jobId);
        return JobInfo.from(cachedGet(url, TTL_LIST_CACHE).getAsJsonObject());
    }

    /**
     * 触发流水线，支持自定义变量 variables[key]=value，返回新流水线实体
     */
    public PipelineInfo triggerPipeline(long projectId, String ref, Map<String, String> variables) throws Exception {
        StringBuilder form = new StringBuilder();
        form.append(GitLabEndpoints.FORM_REF).append('=').append(encode(ref));
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) {
                    continue;
                }
                form.append('&').append(GitLabEndpoints.FORM_VAR_PREFIX).append(encode(e.getKey()))
                        .append(GitLabEndpoints.FORM_VAR_SUFFIX).append('=')
                        .append(encode(e.getValue() == null ? "" : e.getValue()));
            }
        }
        // 触发端点的版本兼容：新版走复数 POST /projects/:id/pipelines；
        // 老版本 GitLab 只有单数端点 POST /projects/:id/pipeline（8.14 引入、17.0 移除），
        // 复数路由不存在时 GitLab 在路由层直接回 404 {"error":"404 Not Found"}
        //（业务失败会带具体原因文本，与此泛化响应不同）。
        // 因此先试复数、仅 404 时降级重试单数，新老实例都能覆盖。
        try {
            return postTrigger(api(GitLabEndpoints.PIPELINES, projectId), form.toString());
        } catch (GitLabApiException ex) {
            if (ex.statusCode != 404) {
                throw ex;
            }
            try {
                return postTrigger(api(GitLabEndpoints.PIPELINE_CREATE_LEGACY, projectId), form.toString());
            } catch (GitLabApiException legacyEx) {
                throw new GitLabApiException(legacyEx.statusCode,
                        "复数端点：" + ex.getMessage() + "；降级单数端点：" + legacyEx.getMessage());
            }
        }
    }

    /**
     * 用同一份表单体向指定的流水线创建端点发起 POST，成功返回新流水线实体
     */
    private PipelineInfo postTrigger(String url, String form) throws Exception {
        HttpRequest request = get(url)
                .header(GitLabEndpoints.HEADER_CONTENT_TYPE, GitLabEndpoints.CONTENT_TYPE_FORM)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            return PipelineInfo.from(executeJson(request).getAsJsonObject());
        } catch (GitLabApiException ex) {
            // 把实际请求的 URL 一并带出，便于用户用浏览器/curl 直接复现，
            // 区分「端点/令牌问题」与「ref 无 CI 配置」。
            throw new GitLabApiException(ex.statusCode, "POST " + url + "  body=" + form + "  -> " + ex.getMessage());
        }
    }

    /**
     * 取消流水线，返回更新后的流水线实体
     */
    public PipelineInfo cancelPipeline(long projectId, long pipelineId) throws Exception {
        return pipelineAction(projectId, pipelineId, GitLabEndpoints.ACTION_CANCEL);
    }

    /**
     * 取消单个 Job，返回更新后的 Job 实体
     */
    public JobInfo cancelJob(long projectId, long jobId) throws Exception {
        return jobAction(projectId, jobId, GitLabEndpoints.ACTION_CANCEL);
    }

    /**
     * 获取单条流水线详情（含 duration 耗时、user 触发人；列表接口不返回这两项）
     */
    public PipelineInfo getPipelineDetail(long projectId, long pipelineId) throws Exception {
        String url = api(GitLabEndpoints.PIPELINE, projectId, pipelineId);
        return PipelineInfo.from(cachedGet(url, TTL_PIPELINE_DETAIL).getAsJsonObject());
    }

    /**
     * 重试失败的流水线 / 已取消的流水线，返回更新后的流水线实体
     */
    public PipelineInfo retryPipeline(long projectId, long pipelineId) throws Exception {
        return pipelineAction(projectId, pipelineId, GitLabEndpoints.ACTION_RETRY);
    }

    /**
     * 重试失败的 Job / 已取消的 Job，返回更新后的 Job 实体
     */
    public JobInfo retryJob(long projectId, long jobId) throws Exception {
        return jobAction(projectId, jobId, GitLabEndpoints.ACTION_RETRY);
    }

    /**
     * 执行手动 Job（.gitlab-ci.yml 里 when: manual 的作业，需点「执行」才会跑），返回更新后的 Job 实体
     */
    public JobInfo playJob(long projectId, long jobId) throws Exception {
        return jobAction(projectId, jobId, GitLabEndpoints.ACTION_PLAY);
    }

    /**
     * 流水线写动作（取消/重试）：POST 无请求体，成功后清空缓存，返回更新后的流水线实体
     */
    private PipelineInfo pipelineAction(long projectId, long pipelineId, String action) throws Exception {
        String url = api(GitLabEndpoints.PIPELINE + action, projectId, pipelineId);
        return PipelineInfo.from(postNoBody(url).getAsJsonObject());
    }

    /**
     * Job 写动作（取消/重试/执行）：POST 无请求体，成功后清空缓存，返回更新后的 Job 实体
     */
    private JobInfo jobAction(long projectId, long jobId, String action) throws Exception {
        String url = api(GitLabEndpoints.JOB + action, projectId, jobId);
        return JobInfo.from(postNoBody(url).getAsJsonObject());
    }

    /**
     * 发送一个无请求体的 POST 写操作（取消/重试/执行），成功后清空缓存，返回响应 JSON
     */
    private JsonElement postNoBody(String url) throws Exception {
        JsonElement result = executeJson(get(url).POST(HttpRequest.BodyPublishers.noBody()).build());
        clearCache();
        return result;
    }

    /**
     * 把 JsonArray 逐元素映射为实体列表（共享通用逻辑，取代各 UI 里零散的 parse 方法）
     */
    private <T> List<T> mapArray(JsonElement arr, Function<JsonObject, T> mapper) {
        return JsonUtil.mapList(arr, mapper);
    }

    private List<PipelineInfo> mapArray(JsonElement arr) {
        return mapArray(arr, PipelineInfo::from);
    }

    /**
     * 用 Job 实体工厂对 JsonElement 做列表映射
     */
    private List<JobInfo> mapJobs(JsonElement arr) {
        return mapArray(arr, JobInfo::from);
    }

    private JsonElement executeJson(HttpRequest request) throws Exception {
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return JsonNull.INSTANCE;
            }
            return JsonParser.parseString(body);
        }
        throw new GitLabApiException(resp.statusCode(), resp.body());
    }

    /**
     * 带 TTL 的 GET 缓存读取；ttlMillis <= 0 表示不缓存直接请求（缓存本身是 static 共享的）
     */
    private JsonElement cachedGet(String url, long ttlMillis) throws Exception {
        if (ttlMillis <= 0) {
            return executeJson(get(url).GET().build());
        }
        long now = System.currentTimeMillis();
        CacheEntry entry = CACHE.get(url);
        if (entry != null && entry.expiresAt > now) {
            return entry.value;
        }
        JsonElement value = executeJson(get(url).GET().build());
        CACHE.put(url, new CacheEntry(value, now + ttlMillis));
        purgeExpired(now);
        return value;
    }

    /**
     * 清理已过期的缓存项，防止长期运行后缓存无限增长
     */
    private static void purgeExpired(long now) {
        CACHE.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
    }

    /**
     * 触发/取消/重试/执行等写操作后调用：清空缓存，让下次刷新立即拿到最新数据
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}