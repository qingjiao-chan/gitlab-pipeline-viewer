package com.gitlab.pipeline.viewer.services;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 统一通知管理（Application 级工具服务）。
 * <p>
 * 规范约束（见 {@code 规范.md} §4 / §7）：
 * - 禁止直接 {@code new NotificationGroup}，必须通过 plugin.xml 声明的
 * {@code notificationGroup} 扩展点（id = {@link #GROUP_ID}）经
 * {@link NotificationGroupManager} 获取后创建通知；
 * - 对用户可理解的错误统一走通知（BALLOON），避免频繁弹 {@code MessageDialog}。
 */
public final class NotificationService {

    /**
     * 与 plugin.xml 中 {@code <notificationGroup>} 的 id 保持一致的组 id
     */
    public static final String GROUP_ID = "GitLab Pipeline Viewer";

    private NotificationService() {
    }

    /**
     * 发送一条通知到指定项目（EDT 上调用）。
     */
    public static void notify(@NotNull Project project, @NotNull String content, @NotNull NotificationType type) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(content, type);
        Notifications.Bus.notify(notification, project);
    }

    /**
     * 信息类通知。
     */
    public static void info(@NotNull Project project, @NotNull String content) {
        notify(project, content, NotificationType.INFORMATION);
    }

    /**
     * 错误类通知。
     */
    public static void error(@NotNull Project project, @NotNull String content) {
        notify(project, content, NotificationType.ERROR);
    }
}