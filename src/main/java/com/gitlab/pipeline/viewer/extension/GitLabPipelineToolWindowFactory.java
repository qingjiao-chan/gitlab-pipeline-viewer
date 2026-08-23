package com.gitlab.pipeline.viewer.extension;

import com.gitlab.pipeline.viewer.ui.GitLabPipelinePanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 扩展点实现：注册右侧工具窗口 "GitLab Pipelines"。
 * 由 plugin.xml 里 {@code <toolWindow factoryClass="...">} 声明，IDEA 首次点击该窗口时自动调用。
 * 窗口关闭时用 Disposer 清理面板资源（定时器等），避免内存泄漏。
 */
public class GitLabPipelineToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        GitLabPipelinePanel panel = new GitLabPipelinePanel(project, toolWindow);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(toolWindow.getDisposable(), (Disposable) panel);
    }
}