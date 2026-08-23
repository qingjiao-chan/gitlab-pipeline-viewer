package com.gitlab.pipeline.viewer.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一次「加载某一页流水线」的完整数据快照（纯数据，无逻辑）。
 * 由 {@code PipelineDataService.loadPage} 在后台线程组装，供 UI 一次性更新。
 */
public record PipelineSnapshot(
        long projectId,
        List<PipelineInfo> pipelines,
        boolean hasNext,
        @Nullable PipelineInfo target,
        List<JobInfo> jobs,
        long targetJobId,
        @Nullable String trace) {
}