package com.gitlab.pipeline.viewer.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 触发流水线对话框：分支下拉选择 + 自定义变量（每行 key=value）
 */
public class TriggerPipelineDialog extends DialogWrapper {

    private final JComboBox<String> refCombo = new JComboBox<>();
    private final JTextArea variablesArea = new JTextArea(6, 40);

    public TriggerPipelineDialog(@Nullable Project project, String defaultRef, List<String> branches) {
        super(project);
        setTitle("触发流水线");
        if (branches != null) {
            for (String b : branches) {
                refCombo.addItem(b);
            }
        }
        if (refCombo.getItemCount() == 0) {
            // 分支加载失败等异常情况：回退为可手动输入，保证功能可用
            refCombo.setEditable(true);
            refCombo.getEditor().setItem(defaultRef == null ? "" : defaultRef);
        } else {
            boolean found = false;
            if (defaultRef != null) {
                for (int i = 0; i < refCombo.getItemCount(); i++) {
                    if (defaultRef.equals(refCombo.getItemAt(i))) {
                        refCombo.setSelectedIndex(i);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                refCombo.setSelectedIndex(0);
            }
        }
        variablesArea.setLineWrap(false);
        variablesArea.setFont(JBUI.Fonts.create("Monospaced", JBUI.scale(12)));
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
        panel.add(new JBLabel("分支 (ref):"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(refCombo, c);

        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JBLabel("变量 (每行 key=value):"), c);
        c.gridx = 1;
        c.gridy = 1;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        panel.add(new JScrollPane(variablesArea), c);

        panel.setPreferredSize(new Dimension(460, 240));
        return panel;
    }

    public String getRef() {
        Object item = refCombo.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    public Map<String, String> getVariables() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : variablesArea.getText().split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            } else {
                map.put(line, "");
            }
        }
        return map;
    }
}
