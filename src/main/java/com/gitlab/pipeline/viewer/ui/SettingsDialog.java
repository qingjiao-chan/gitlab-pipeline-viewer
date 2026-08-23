package com.gitlab.pipeline.viewer.ui;

import com.gitlab.pipeline.viewer.settings.GitLabSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * GitLab 连接与刷新设置对话框
 */
public class SettingsDialog extends DialogWrapper {

    private final JTextField urlField = new JTextField();
    private final JPasswordField tokenField = new JPasswordField();
    private final JCheckBox autoRefreshCheck = new JCheckBox("运行中的 Job 日志自动刷新");
    private final JTextField intervalField = new JTextField();
    private final JTextField pageSizeField = new JTextField();
    private final JTextField timeoutField = new JTextField();

    public SettingsDialog(@Nullable Project project) {
        super(project);
        setTitle("GitLab 设置");
        GitLabSettings s = GitLabSettings.getInstance();
        urlField.setText(s.getGitlabUrl());
        tokenField.setText(s.getToken());
        autoRefreshCheck.setSelected(s.isAutoRefresh());
        intervalField.setText(String.valueOf(s.getRefreshIntervalSeconds()));
        pageSizeField.setText(String.valueOf(s.getPipelinePageSize()));
        timeoutField.setText(String.valueOf(s.getRequestTimeoutSeconds()));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JBLabel("GitLab 地址:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(urlField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JBLabel("访问令牌 (api):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(tokenField, c);

        c.gridx = 1;
        c.gridy = 2;
        panel.add(autoRefreshCheck, c);

        c.gridx = 0;
        c.gridy = 3;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JBLabel("日志刷新间隔(秒):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(intervalField, c);

        c.gridx = 0;
        c.gridy = 4;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JBLabel("每页流水线条数:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(pageSizeField, c);

        c.gridx = 0;
        c.gridy = 5;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JBLabel("请求超时(秒):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(timeoutField, c);

        panel.setPreferredSize(new Dimension(480, 200));
        return panel;
    }

    @Override
    protected void doOKAction() {
        GitLabSettings s = GitLabSettings.getInstance();
        s.setGitlabUrl(urlField.getText().trim());
        s.setToken(new String(tokenField.getPassword()));
        s.setAutoRefresh(autoRefreshCheck.isSelected());
        s.setRefreshIntervalSeconds(parseInt(intervalField.getText(), 10));
        s.setPipelinePageSize(parseInt(pageSizeField.getText(), 10));
        s.setRequestTimeoutSeconds(parseInt(timeoutField.getText(), 15));
        super.doOKAction();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
