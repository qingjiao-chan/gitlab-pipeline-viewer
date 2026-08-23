package com.gitlab.pipeline.viewer.model;

import org.jetbrains.annotations.NotNull;

/**
 * 当前 IDEA 打开的项目（或其 Git 远程仓库）对应的一个 GitLab 项目。
 * 一个 IDEA 窗口可包含多个项目（含附加项目），每个项目对应一个 GitLab 地址。
 */
public class ProjectEntry {
    private final String ideaProjectName;
    private final String remoteUrl;
    private final String host;
    private final String path;

    public ProjectEntry(@NotNull String ideaProjectName, @NotNull String remoteUrl,
                        @NotNull String host, @NotNull String path) {
        this.ideaProjectName = ideaProjectName;
        this.remoteUrl = remoteUrl;
        this.host = host;
        this.path = path;
    }

    public String getIdeaProjectName() {
        return ideaProjectName;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public String getHost() {
        return host;
    }

    /**
     * GitLab 项目路径，如 group/subgroup/project
     */
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        // 下拉框只显示最后一级项目名（group/sub/project -> project），更简洁
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
