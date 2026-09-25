package com.gitlab.pipeline.viewer.services;

import com.gitlab.pipeline.viewer.model.*;
import com.gitlab.pipeline.viewer.util.JsonUtil;
import com.google.gson.*;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.time.Duration;

import javax.net.ssl.SSLException;

import com.intellij.util.net.HttpConfigurable;
import com.intellij.util.net.ssl.CertificateManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * key = 账号 id + 完整请求 URL（含项目/流水线 id），多账号/多项目间不会串数据；
 * 容量上限 200（LRU），写操作成功后仅失效当前账号。
 * <p>
 * HttpClient 也按「服务器 + 连接超时」static 复用（线程安全、共享连接池）。
 * GET 请求遇到 429/502/503/504/瞬时网络异常自动重试（最多 2 次，429 尊重 Retry-After），
 * POST 绝不重试。
 */
public class GitLabApiService {

    /** 缓存条目上限：LRU 淘汰，防止长期运行后缓存无限增长（多账号/多项目共享） */
    private static final int MAX_CACHE_ENTRIES = 200;

    /** GET 请求失败后的最大尝试次数（含首次），即最多重试 2 次 */
    private static final int MAX_ATTEMPTS = 3;

    /** 429 Retry-After 的等待上限，避免服务端要求长时间挂起 */
    private static final long RETRY_AFTER_CAP_MS = 20_000;

    private final String accountId;
    private final String baseUrl;
    private final String token;
    private final HttpClient client;
    private final Duration timeout;

    /**
     * HttpClient 复用池：{@link java.net.http.HttpClient} 线程安全且内部持有连接池与
     * selector 线程；过去每次「new 一个 API 客户端」都连带新建 HttpClient，频繁操作会
     * 累积 TCP/TLS 握手与后台线程。按「服务器地址 + 连接超时」复用，令牌不参与 key
     * （认证头逐请求携带），切账号不会误用旧令牌。
     */
    private static final Map<String, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    /**
     * 账号级共享 TTL 缓存：key = accountId + 分隔符 + 完整请求 URL。
     * 用 access-order LinkedHashMap 做 LRU（上限 {@link #MAX_CACHE_ENTRIES}），
     * 写入时顺带清理过期项；所有访问在 synchronized 块内完成。
     */
    private static final Map<String, CacheEntry> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

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

    public GitLabApiService(String accountId, String baseUrl, String token, int timeoutSeconds) {
        this.accountId = accountId == null ? "" : accountId;
        this.baseUrl = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        long timeoutSec = Math.max(5, timeoutSeconds);
        String clientKey = this.baseUrl + "#" + timeoutSec;
        this.client = CLIENTS.computeIfAbsent(clientKey, k -> buildHttpClient(timeoutSec));
        this.timeout = Duration.ofSeconds(Math.max(15, timeoutSec * 6));
    }

    /**
     * 构造共享 HttpClient：
     * - SSLContext 接入平台 {@link CertificateManager}，使 IDE 已接受（含自签名/内网 CA）的
     *   服务器证书对插件同样生效；未接受的证书由平台弹出标准确认框；平台不可用时退回 JDK 默认。
     * - ProxySelector 使用 IDE 代理设置（Settings → Appearance → System Settings → HTTP Proxy），
     *   与浏览器/IDEA 自身网络行为保持一致。
     */
    private static HttpClient buildHttpClient(long timeoutSec) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .followRedirects(HttpClient.Redirect.NORMAL);
        try {
            builder.sslContext(CertificateManager.getInstance().getSslContext());
        } catch (Throwable t) {
            // 平台证书组件不可用时保持 JDK 默认 SSLContext
        }
        try {
            ProxySelector selector = HttpConfigurable.getInstance().getOnlyBySettingsSelector();
            if (selector != null) {
                builder.proxy(selector);
            }
        } catch (Throwable t) {
            // 代理组件不可用时走直连
        }
        return builder.build();
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
     * 获取当前令牌对应的用户（GET /user）。用于设置中的「测试连接」：
     * 成功即说明地址可达且令牌有效。不做缓存（设置场景按需调用）。
     */
    public CurrentUser getCurrentUser() throws Exception {
        String url = api(GitLabEndpoints.USER);
        // 连接测试语义：只请求一次（错误地址/坏网络下不必等 3×超时），结果由调用方就地展示
        return CurrentUser.from(executeJsonNoRetry(get(url).GET().build()).getAsJsonObject());
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

    // ---- 分页拉取的页大小与硬性总量上限（GitLab per_page 最大只接受 100） ----
    private static final int PAGE_SIZE = 100;
    /** 单条流水线的 Job 数硬上限：极端 include 矩阵可能产生数千 Job，必须截断防止失控 */
    private static final int HARD_CAP_JOBS = 500;
    /** 组 / 组下项目的硬上限：组树选择器按层懒加载，单层 2000 足够覆盖大型实例 */
    private static final int HARD_CAP_GROUPS = 2000;
    /** 分支硬上限：触发弹窗的分支选择，超大仓库截断保护，后续可加搜索参数 */
    private static final int HARD_CAP_BRANCHES = 1000;

    /**
     * 获取流水线下的完整 Job 列表（自动翻页，最多 {@link #HARD_CAP_JOBS} 个）。
     * 旧实现固定 per_page=100 只拉第一页，Job 超过 100 的流水线会静默丢失作业。
     */
    public List<JobInfo> listJobs(long projectId, long pipelineId) throws Exception {
        JsonArray all = pagedGet(GitLabEndpoints.PIPELINE_JOBS, null,
                PAGE_SIZE, HARD_CAP_JOBS, TTL_LIST_CACHE, projectId, pipelineId);
        return mapJobs(all);
    }

    /**
     * 获取项目完整分支列表（按名称升序，自动翻页，最多 {@link #HARD_CAP_BRANCHES} 个）
     */
    public List<BranchInfo> listBranches(long projectId) throws Exception {
        JsonArray all = pagedGet(GitLabEndpoints.BRANCHES,
                Map.of(GitLabEndpoints.PARAM_SORT, GitLabEndpoints.BRANCH_SORT),
                PAGE_SIZE, HARD_CAP_BRANCHES, TTL_PROJECT_INFO, projectId);
        return mapArray(all, BranchInfo::from);
    }

    /**
     * 获取顶级项目组（用于树形选择第一层；懒加载），自动翻页至
     * {@link #HARD_CAP_GROUPS}。请求带 top_level_only=true 只返回顶层组；
     * 旧版 GitLab / 部分部署不支持该参数时会把子组也一并返回，这里再按
     * parent_id 兜底过滤一次，保证第一层只含顶级组。
     */
    public List<GroupEntry> listRootGroups() throws Exception {
        JsonArray all = pagedGet(GitLabEndpoints.GROUPS,
                Map.of(
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_NAME,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_ASC,
                        GitLabEndpoints.PARAM_TOP_LEVEL_ONLY, GitLabEndpoints.BOOL_TRUE),
                PAGE_SIZE, HARD_CAP_GROUPS, TTL_LIST_GROUP);
        List<GroupEntry> top = new ArrayList<>();
        for (JsonElement el : all) {
            if (el != null && el.isJsonObject() && JsonUtil.isRootLevel(el.getAsJsonObject())) {
                top.add(GroupEntry.from(el.getAsJsonObject()));
            }
        }
        return top;
    }

    /**
     * 获取指定组的直接子组（GET /groups/:id/subgroups），自动翻页，用于树形选择按层懒加载
     */
    public List<GroupEntry> listSubGroups(long groupId) throws Exception {
        JsonArray all = pagedGet(GitLabEndpoints.GROUP_SUBGROUPS,
                Map.of(
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_NAME,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_ASC),
                PAGE_SIZE, HARD_CAP_GROUPS, TTL_LIST_GROUP, groupId);
        return mapArray(all, GroupEntry::from);
    }

    /**
     * 获取指定组的直接项目（不含 include_subgroups，即不含子组项目），自动翻页，
     * 用于树形选择展开时懒加载
     */
    public List<GitLabProject> listDirectProjects(long groupId) throws Exception {
        JsonArray all = pagedGet(GitLabEndpoints.GROUP_PROJECTS,
                Map.of(
                        GitLabEndpoints.PARAM_ORDER_BY, GitLabEndpoints.ORDER_NAME,
                        GitLabEndpoints.PARAM_SORT, GitLabEndpoints.SORT_ASC),
                PAGE_SIZE, HARD_CAP_GROUPS, TTL_LIST_GROUP, groupId);
        return mapArray(all, GitLabProject::from);
    }

    /**
     * 通用分页拉取：逐页 GET 聚合为一个 {@link JsonArray}，适用于「需要全量列表」的
     * 只读端点（Job / 分支 / 组树）。
     * <ul>
     *   <li>停止条件（满足任一）：本页元素数 &lt; perPage（末页）；已到达
     *       X-Total-Pages 头给出的总页数；累计达到 {@code hardCap}；</li>
     *   <li>X-Total-Pages 在部分部署上缺省时，仅靠「短页」判定，逻辑依然正确；</li>
     *   <li>聚合结果整体按 {@code ttlMillis} 缓存（缓存 key 不含页码），
     *       ttl &le; 0 不缓存。</li>
     * </ul>
     *
     * @param template    端点模板（%s 占位）
     * @param fixedParams 除分页外的固定查询参数（顺序稳定，可空）
     * @param perPage     每页条数（GitLab 最大 100）
     * @param hardCap     聚合元素硬上限，超过即截断
     * @param ttlMillis   缓存 TTL
     * @param args        端点模板参数
     */
    private JsonArray pagedGet(String template, Map<String, String> fixedParams,
                               int perPage, int hardCap, long ttlMillis, Object... args)
            throws Exception {
        String baseUrl = api(template, args);
        Map<String, String> baseParams = new LinkedHashMap<>();
        if (fixedParams != null) {
            baseParams.putAll(fixedParams);
        }
        baseParams.put(GitLabEndpoints.PARAM_PER_PAGE, String.valueOf(perPage));
        String baseQuery = query(baseParams);

        if (ttlMillis > 0) {
            String key = cacheKey(baseUrl + baseQuery);
            long now = System.currentTimeMillis();
            synchronized (CACHE) {
                CacheEntry entry = CACHE.get(key);
                if (entry != null && entry.expiresAt > now && entry.value instanceof JsonArray cached) {
                    return cached;
                }
            }
        }

        JsonArray all = new JsonArray();
        int page = 1;
        int totalPages = Integer.MAX_VALUE;
        while (all.size() < hardCap) {
            Map<String, String> pageParams = new LinkedHashMap<>(baseParams);
            pageParams.put(GitLabEndpoints.PARAM_PAGE, String.valueOf(page));
            HttpRequest request = get(baseUrl + query(pageParams)).GET().build();
            HttpResponse<String> resp = send(request, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) {
                throw new GitLabApiException(sc, resp.body());
            }
            String body = resp.body();
            JsonElement parsed = (body == null || body.isBlank())
                    ? JsonNull.INSTANCE : JsonParser.parseString(body);
            if (!(parsed instanceof JsonArray arr) || arr.isEmpty()) {
                break; // 空页即末页
            }
            for (JsonElement e : arr) {
                if (all.size() >= hardCap) {
                    break;
                }
                all.add(e);
            }
            String totalHeader = resp.headers()
                    .firstValue(GitLabEndpoints.HEADER_TOTAL_PAGES).orElse(null);
            if (totalHeader != null) {
                try {
                    totalPages = Math.max(1, Integer.parseInt(totalHeader.trim()));
                } catch (NumberFormatException ignore) {
                    // 非数字头忽略，退化为短页判定
                }
            }
            if (arr.size() < perPage || page >= totalPages) {
                break;
            }
            page++;
        }

        if (ttlMillis > 0) {
            String key = cacheKey(baseUrl + baseQuery);
            long now = System.currentTimeMillis();
            synchronized (CACHE) {
                CACHE.put(key, new CacheEntry(all, now + ttlMillis));
                purgeExpired(now);
            }
        }
        return all;
    }

    /**
     * 获取 Job 完整构建日志（纯文本）
     */
    public String getJobTrace(long projectId, long jobId) throws Exception {
        String url = api(GitLabEndpoints.JOB_TRACE, projectId, jobId);
        HttpResponse<String> resp = send(get(url).GET().build(), HttpResponse.BodyHandlers.ofString());
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
        HttpResponse<byte[]> resp = send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
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
            PipelineInfo created = PipelineInfo.from(executeJson(request).getAsJsonObject());
            invalidateAccountCache();
            return created;
        } catch (GitLabApiException ex) {
            // 把实际请求的 URL 一并带出，便于用户用浏览器/curl 直接复现，
            // 区分「端点/令牌问题」与「ref 无 CI 配置」。
            // 注意：不能把 form 拼进异常消息——variables 中可能包含密钥类变量。
            throw new GitLabApiException(ex.statusCode, "POST " + url + "  -> " + ex.getMessage());
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
        invalidateAccountCache();
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
        return parseJsonResponse(send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * 不做任何 GET 重试的 JSON 请求（连接测试等需要快速反馈的场景）。
     */
    private JsonElement executeJsonNoRetry(HttpRequest request) throws Exception {
        return parseJsonResponse(send(request, HttpResponse.BodyHandlers.ofString(), true));
    }

    private JsonElement parseJsonResponse(HttpResponse<String> resp) throws GitLabApiException {
        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) {
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return JsonNull.INSTANCE;
            }
            return JsonParser.parseString(body);
        }
        throw new GitLabApiException(sc, resp.body());
    }

    /**
     * 统一发送入口：仅对幂等 GET 在「429 / 502 / 503 / 504 / 瞬时网络异常」时做最多
     * {@link #MAX_ATTEMPTS} 次尝试。429 优先读 Retry-After（秒），其余按指数退避
     * 500ms、1s；POST 等写操作绝不自动重试（避免重复触发/重复取消）。
     */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws Exception {
        return send(request, handler, false);
    }

    /**
     * 统一发送入口：仅对幂等 GET 在「429 / 502 / 503 / 504 / 瞬时网络异常」时做最多
     * {@link #MAX_ATTEMPTS} 次尝试。429 优先读 Retry-After（秒），其余按指数退避
     * 500ms、1s；POST 等写操作绝不自动重试（避免重复触发/重复取消）。
     * TLS/证书失败立即抛出并给出证书引导（重试只会重复弹出证书确认框）。
     *
     * @param disableRetry true 时即使 GET 也只尝试一次（连接测试）
     */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                    boolean disableRetry) throws Exception {
        boolean retriable = !disableRetry && "GET".equals(request.method());
        IOException lastIo = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<T> resp = client.send(request, handler);
                int code = resp.statusCode();
                if (retriable && attempt + 1 < MAX_ATTEMPTS && isRetriableStatus(code)) {
                    long waitMs = retryWaitMillis(resp, code, attempt);
                    if (waitMs >= 0) {
                        Thread.sleep(waitMs);
                        continue;
                    }
                }
                return resp;
            } catch (IOException io) {
                if (isSslFailure(io)) {
                    throw GitLabApiException.sslFailure(io);
                }
                lastIo = io;
                if (!retriable || attempt + 1 >= MAX_ATTEMPTS) {
                    throw new GitLabApiException(0, io.getMessage(), io);
                }
                Thread.sleep(backoffMillis(attempt));
            }
        }
        // 理论不可达：循环内必定 return 或 throw
        throw new GitLabApiException(0, lastIo == null ? "请求失败" : lastIo.getMessage(), lastIo);
    }

    /**
     * 判断异常链中是否存在 TLS/证书层失败（不受信 CA、握手失败、证书过期/吊销等）。
     */
    private static boolean isSslFailure(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur instanceof SSLException || cur instanceof CertificateException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isRetriableStatus(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
    }

    /**
     * 计算重试等待毫秒数；429 优先使用 Retry-After（delta-seconds，HTTP-date 形式不支持
     * 时退化为指数退避），其余状态按 500ms 起步翻倍。返回 -1 表示不应重试。
     */
    private static long retryWaitMillis(HttpResponse<?> resp, int code, int attempt) {
        if (code == 429) {
            String header = resp.headers().firstValue(GitLabEndpoints.HEADER_RETRY_AFTER).orElse(null);
            if (header != null) {
                try {
                    long secs = Long.parseLong(header.trim());
                    return Math.min(RETRY_AFTER_CAP_MS, Math.max(0, secs) * 1000L);
                } catch (NumberFormatException ignore) {
                    // Retry-After 可能是 HTTP-date，这里不解析绝对时间，走指数退避
                }
            }
        }
        return backoffMillis(attempt);
    }

    private static long backoffMillis(int attempt) {
        return 500L * (1L << Math.max(0, attempt));
    }

    /**
     * 带 TTL 的 GET 缓存读取；ttlMillis <= 0 表示不缓存直接请求。
     * 缓存 key 带账号 id 前缀，多账号/多实例共享 static 缓存也不会串数据。
     */
    private JsonElement cachedGet(String url, long ttlMillis) throws Exception {
        if (ttlMillis <= 0) {
            return executeJson(get(url).GET().build());
        }
        String key = cacheKey(url);
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            CacheEntry entry = CACHE.get(key);
            if (entry != null && entry.expiresAt > now) {
                return entry.value;
            }
        }
        JsonElement value = executeJson(get(url).GET().build());
        synchronized (CACHE) {
            CACHE.put(key, new CacheEntry(value, now + ttlMillis));
            purgeExpired(now);
        }
        return value;
    }

    private String cacheKey(String url) {
        return accountId + "|" + url;
    }

    /**
     * 清理已过期的缓存项（调用方需持有 CACHE 的监视器）
     */
    private static void purgeExpired(long now) {
        CACHE.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
    }

    /**
     * 清空当前账号的 GET 缓存：触发/取消/重试/执行等写操作成功后调用，
     * 让下次刷新立即拿到最新数据，且不影响其他账号的缓存。
     */
    public void invalidateAccountCache() {
        clearAccountCache(accountId);
    }

    /**
     * 清空指定账号的 GET 缓存（供 service 层在无实例时调用）。
     */
    public static void clearAccountCache(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            return;
        }
        String prefix = accountId + "|";
        synchronized (CACHE) {
            CACHE.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /**
     * 清空全部账号的 GET 缓存（仅用于全局设置级变更/排障）。
     */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}