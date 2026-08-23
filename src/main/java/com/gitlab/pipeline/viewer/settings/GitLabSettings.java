package com.gitlab.pipeline.viewer.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插件配置持久化（应用级，所有项目共享）：
 * GitLab 地址、访问令牌、自动刷新开关、上次选择的项目。
 * 自动刷新仅作用于「选中运行中的流水线时自动刷新其 Job 日志」，流水线/项目列表不自动刷新。
 * <p>
 * 对标 Spring Boot：
 * - 相当于 @ConfigurationProperties(prefix="gitlab") 的配置 Bean；
 * - @State + @Storage 告诉 IDEA「这份配置要存盘到 XML 文件」，
 * 存盘位置：{IDE 配置目录}/options/gitlab-pipeline-viewer.xml；
 * - PersistentStateComponent<State> = 实现了「读配置 -> 改配置 -> 自动保存」的模板方法；
 * 只暴露 getState()/loadState() 两个方法，IDEA 在需要时自动调用。
 */
@State(name = "GitLabPipelineViewerSettings", storages = {@Storage("gitlab-pipeline-viewer.xml")})
public class GitLabSettings implements PersistentStateComponent<GitLabSettings.State> {

    /**
     * 可序列化的配置快照：字段即配置项（类似 @ConfigurationProperties 的字段）
     */
    public static class State {
        public String gitlabUrl = "";
        public String token = "";
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

    public static GitLabSettings getInstance() {
        return ApplicationManager.getApplication().getService(GitLabSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public String getGitlabUrl() {
        return state.gitlabUrl;
    }

    public void setGitlabUrl(String v) {
        state.gitlabUrl = v == null ? "" : v;
    }

    public String getToken() {
        return state.token;
    }

    public void setToken(String v) {
        state.token = v == null ? "" : v;
    }

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
        return state.lastProjectPath;
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
