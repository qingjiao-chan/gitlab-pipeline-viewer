package com.gitlab.pipeline.viewer.model;

import java.util.List;

/**
 * 展开某个项目组后其直接子组与直接项目的快照（纯数据，无逻辑）。
 * 由 {@code ProjectSelectionService.loadChildren} 在后台线程组装，供项目树一次渲染。
 */
public record GroupChildrenView(List<GroupEntry> subGroups, List<ProjectEntry> projects) {
}