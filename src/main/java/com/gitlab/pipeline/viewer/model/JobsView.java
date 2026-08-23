package com.gitlab.pipeline.viewer.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一次「加载流水线下的 Jobs 及其选中日志」的数据快照（纯数据，无逻辑）。
 */
public record JobsView(List<JobInfo> jobs, long targetJobId, @Nullable String trace) {
}