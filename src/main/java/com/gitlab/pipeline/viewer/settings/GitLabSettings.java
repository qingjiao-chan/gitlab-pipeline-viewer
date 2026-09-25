package com.gitlab.pipeline.viewer.settings;

import com.gitlab.pipeline.viewer.model.Account;
import com.gitlab.pipeline.viewer.services.TokenStore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 插件配置持久化（应用级，所有项目共享）。
 * <p>
 * v1.1 起：
 * - 支持多账号（名称 + GitLab 地址存于 XML；令牌只存 IDE 密码库 {@link TokenStore}）；
 * - v1.0 旧版的明文 gitlabUrl/token 在首次访问时一次性迁移：建账号、令牌入密码库、
 *   清空明文字段（token 迁移后恒为空串，绝不再落盘真实令牌）。
 * <p>
 * 存盘位置：{IDE 配置目录}/options/gitlab-pipeline-viewer.xml（仅地址、刷新偏好等非敏感项）。
 */
@State(name = "GitLabPipelineViewerSettings", storages = {@Storage("gitlab-pipeline-viewer.xml")})
public class GitLabSettings implements PersistentStateComponent<GitLabSettings.State> {

    /**
     * 从 v1.0 配置迁移来的首个账号固定 id（仅迁移路径使用；新建账号走随机 id）
     */
    private static final String DEFAULT_ACCOUNT_ID = "default";

    /**
     * 可序列化的配置快照。
     */
    public static class AccountState {
        public String id = "";
        public String name = "";
        public String url = "";
    }

    public static class State {
        // ---- v1.0 迁移专用：旧版本把地址与明文令牌存在这两个字段。
        // 首次加载完成迁移后：token 恒为空串（绝不再持久化令牌）；
        // 账号建立成功后 gitlabUrl 也清空（地址以 accounts 为准）。
        public String gitlabUrl = "";
        /**
         * @deprecated 仅供 v1.0 → v1.1 迁移读取；任何新代码禁止使用，写回时必须为空串。
         */
        @Deprecated
        public String token = "";

        /**
         * 已配置账号列表（不含令牌；令牌在密码库中按 id 存取）
         */
        public List<AccountState> accounts = new ArrayList<>();
        /**
         * 当前生效账号 id（同一时刻面板只展示一个账号的数据）
         */
        public String activeAccountId = "";

        /**
         * 是否自动刷新选中运行中 Job 的日志（流水线/项目列表不自动刷新）
         */
        public boolean autoRefresh = true;
        /**
         * 日志自动刷新间隔（秒）
         */
        public int refreshIntervalSeconds = 5;
        public String lastProjectPath = "";
        /**
         * 流水线列表每页条数
         */
        public int pipelinePageSize = 10;
        /**
         * GitLab API 请求超时（秒）
         */
        public int requestTimeoutSeconds = 15;
    }

    private State state = new State();
    private volatile boolean migrated = false;

    public static GitLabSettings getInstance() {
        GitLabSettings settings = ApplicationManager.getApplication().getService(GitLabSettings.class);
        settings.ensureMigrated();
        return settings;
    }

    @Override
    public @Nullable State getState() {
        ensureMigrated();
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        this.state = state;
        this.migrated = false;
    }

    // ---------------------------------------------------------------- 迁移

    /**
     * v1.0 → v1.1 一次性迁移（幂等）：旧 gitlabUrl 非空时建立账号，旧明文令牌迁入密码库，
     * 随后清空明文 token；账号建立成功后清空 gitlabUrl。
     */
    private synchronized void ensureMigrated() {
        if (migrated) {
            return;
        }
        migrated = true;
        if (state.accounts == null) {
            state.accounts = new ArrayList<>();
        }
        if (state.activeAccountId == null) {
            state.activeAccountId = "";
        }
        String legacyUrl = normalizeUrl(state.gitlabUrl);
        @SuppressWarnings("deprecation")
        String legacyToken = state.token == null ? "" : state.token.trim();

        if (state.accounts.isEmpty() && !legacyUrl.isEmpty()) {
            AccountState a = new AccountState();
            a.id = DEFAULT_ACCOUNT_ID;
            a.name = defaultAccountName(legacyUrl);
            a.url = legacyUrl;
            state.accounts.add(a);
            if (state.activeAccountId.isEmpty()) {
                state.activeAccountId = a.id;
            }
            if (!legacyToken.isEmpty()) {
                TokenStore.setToken(a.id, legacyToken);
            }
        }
        // 明文凭据无论是否迁移成功都不再保留；地址在账号建立后同样以 accounts 为准
        state.token = "";
        if (!state.accounts.isEmpty()) {
            state.gitlabUrl = "";
        }
    }

    // ---------------------------------------------------------------- 账号管理

    /**
     * 全部已配置账号（顺序即设置中的展示顺序）
     */
    public synchronized @NotNull List<Account> getAccounts() {
        List<Account> result = new ArrayList<>();
        for (AccountState a : state.accounts) {
            result.add(new Account(a.id, a.name, a.url));
        }
        return result;
    }

    public synchronized @Nullable Account getActiveAccount() {
        return findAccount(state.activeAccountId);
    }

    public synchronized @NotNull String getActiveAccountId() {
        return state.activeAccountId == null ? "" : state.activeAccountId;
    }

    /**
     * 切换当前生效账号；id 不存在时忽略并返回 false。
     */
    public synchronized boolean setActiveAccount(@NotNull String id) {
        if (findAccount(id) == null) {
            return false;
        }
        state.activeAccountId = id;
        return true;
    }

    /**
     * 新建账号并返回其实体；url 规范化（去尾斜杠）。
     */
    public synchronized @NotNull Account addAccount(@NotNull String name, @NotNull String url) {
        AccountState a = new AccountState();
        a.id = "acct-" + UUID.randomUUID().toString().substring(0, 8);
        a.name = name == null || name.isBlank() ? defaultAccountName(url) : name.trim();
        a.url = normalizeUrl(url);
        state.accounts.add(a);
        if (state.activeAccountId.isEmpty()) {
            state.activeAccountId = a.id;
        }
        return new Account(a.id, a.name, a.url);
    }

    /**
     * 更新账号名称/地址；不存在时忽略。地址变化不改 id（密码库令牌仍有效）。
     */
    public synchronized void updateAccount(@NotNull String id, @NotNull String name, @NotNull String url) {
        AccountState a = findState(id);
        if (a == null) {
            return;
        }
        if (!name.isBlank()) {
            a.name = name.trim();
        }
        a.url = normalizeUrl(url);
    }

    /**
     * 删除账号：同步移除其密码库令牌；若删除的是当前账号，自动切到剩余首个账号。
     */
    public synchronized void removeAccount(@NotNull String id) {
        TokenStore.removeToken(id);
        state.accounts.removeIf(a -> a.id.equals(id));
        if (id.equals(state.activeAccountId)) {
            state.activeAccountId = state.accounts.isEmpty() ? "" : state.accounts.get(0).id;
        }
    }

    /**
     * 读取指定账号令牌（未配置返回空串，永不返回 null）
     */
    public @NotNull String getToken(@NotNull String accountId) {
        String t = TokenStore.getToken(accountId);
        return t == null ? "" : t;
    }

    /**
     * 保存指定账号令牌到密码库（空串等价于清除）
     */
    public void setToken(@NotNull String accountId, @Nullable String token) {
        TokenStore.setToken(accountId, token == null ? "" : token.trim());
    }

    private synchronized @Nullable Account findAccount(@NotNull String id) {
        AccountState a = findState(id);
        return a == null ? null : new Account(a.id, a.name, a.url);
    }

    private @Nullable AccountState findState(@NotNull String id) {
        for (AccountState a : state.accounts) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    private static @NotNull String normalizeUrl(@Nullable String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceAll("/+$", "");
    }

    private static @NotNull String defaultAccountName(@NotNull String url) {
        String host = url.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        return host.isEmpty() ? "GitLab 账号" : host;
    }

    // ---------------------------------------------------------------- v1.0 兼容门面（Task 5 前供旧调用点使用）

    /**
     * 当前账号的 GitLab 地址（无账号时空串）
     */
    public synchronized @NotNull String getGitlabUrl() {
        Account active = getActiveAccount();
        if (active != null) {
            return active.url();
        }
        return normalizeUrl(state.gitlabUrl);
    }

    /**
     * 当前账号的令牌（来自密码库；无账号/未配置时空串）
     */
    public @NotNull String getToken() {
        Account active = getActiveAccount();
        return active == null ? "" : getToken(active.id());
    }

    /**
     * @deprecated v1.0 门面：旧设置对话框在 Task 5 重构前使用。更新当前账号地址，
     * 尚无账号时创建一个默认账号。
     */
    @Deprecated
    public synchronized void setGitlabUrl(@Nullable String v) {
        String url = normalizeUrl(v);
        AccountState active = findState(state.activeAccountId);
        if (active != null) {
            active.url = url;
        } else if (!url.isEmpty()) {
            AccountState a = new AccountState();
            a.id = DEFAULT_ACCOUNT_ID;
            a.name = defaultAccountName(url);
            a.url = url;
            state.accounts.add(a);
            state.activeAccountId = a.id;
        }
    }

    /**
     * @deprecated v1.0 门面：旧设置对话框在 Task 5 重构前使用。令牌写入密码库，
     * 尚无账号时先创建默认账号（地址暂空，随后由 setGitlabUrl 补）。
     */
    @Deprecated
    public synchronized void setToken(@Nullable String v) {
        String token = v == null ? "" : v.trim();
        String id = state.activeAccountId;
        if (id.isEmpty()) {
            if (token.isEmpty()) {
                return;
            }
            AccountState a = new AccountState();
            a.id = DEFAULT_ACCOUNT_ID;
            a.name = "GitLab 账号";
            a.url = normalizeUrl(state.gitlabUrl);
            state.accounts.add(a);
            state.activeAccountId = a.id;
            id = a.id;
        }
        TokenStore.setToken(id, token);
    }

    // ---------------------------------------------------------------- 其他偏好（语义与 v1.0 一致）

    public boolean isAutoRefresh() {
        return state.autoRefresh;
    }

    public void setAutoRefresh(boolean v) {
        state.autoRefresh = v;
    }

    public int getRefreshIntervalSeconds() {
        return state.refreshIntervalSeconds;
    }

    public void setRefreshIntervalSeconds(int v) {
        state.refreshIntervalSeconds = Math.max(5, v);
    }

    public String getLastProjectPath() {
        return state.lastProjectPath == null ? "" : state.lastProjectPath;
    }

    public void setLastProjectPath(String v) {
        state.lastProjectPath = v == null ? "" : v;
    }

    public int getPipelinePageSize() {
        return state.pipelinePageSize;
    }

    public void setPipelinePageSize(int v) {
        state.pipelinePageSize = Math.max(5, v);
    }

    public int getRequestTimeoutSeconds() {
        return state.requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int v) {
        state.requestTimeoutSeconds = Math.max(5, v);
    }
}
