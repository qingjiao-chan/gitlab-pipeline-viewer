package com.gitlab.pipeline.viewer.model;

import java.awt.*;

/**
 * GitLab 流水线 / Job 状态：把所有散落在各处的「魔法状态字符串」及对应行为
 * （中文文案 / 颜色 / 是否运行中 / 是否可重试）内聚到一个枚举里。
 * <p>
 * 采用「枚举 = 简易策略」的思路：
 * 一段行为差异（文案、颜色、可操作性）通过枚举字段统一描述，外部只需问
 * {@code PipelineStatus.isActive(...)} / {@code .label(...)} 等即可，消除 if-else 与魔法字符串散落。
 */
public enum PipelineStatus {

    SUCCESS("success", "成功", 0x1F883D, false, false, false),
    FAILED("failed", "失败", 0xCF222E, false, true, true),
    CANCELED("canceled", "已取消", 0x6E7781, false, true, true),
    RUNNING("running", "运行中", 0xBF8700, true, false, false),
    SCHEDULED("scheduled", "已计划", 0xBF8700, true, false, false),
    PENDING("pending", "等待中", 0x0969DA, true, false, false),
    CREATED("created", "已创建", 0x0969DA, true, false, false),
    PREPARING("preparing", "准备中", 0x0969DA, true, false, false),
    WAITING("waiting_for_resource", "等待资源", 0x0969DA, true, false, false),
    MANUAL("manual", "手动", 0x6E7781, false, false, true),
    SKIPPED("skipped", "已跳过", 0x6E7781, false, false, false);

    /**
     * 未知状态下使用的兜底文案
     */
    private static final String UNKNOWN_LABEL = "";
    private static final Color UNKNOWN_COLOR = new Color(0x6E7781);

    private final String apiName;
    private final String label;
    private final Color color;
    private final boolean active;
    private final boolean retryablePipeline;
    private final boolean retryableJob;

    PipelineStatus(String apiName, String label, int argb,
                   boolean active, boolean retryablePipeline, boolean retryableJob) {
        this.apiName = apiName;
        this.label = label;
        this.color = new Color(argb);
        this.active = active;
        this.retryablePipeline = retryablePipeline;
        this.retryableJob = retryableJob;
    }

    /**
     * GitLab 接口返回的原始状态名（如 "running"），用于与模型原始字段比对、去重等
     */
    public String apiName() {
        return apiName;
    }

    public String label() {
        return label;
    }

    public Color color() {
        return color;
    }

    /**
     * 是否运行中/等待中（可取消）
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 流水线是否可重试（失败 / 已取消）
     */
    public boolean isRetryablePipeline() {
        return retryablePipeline;
    }

    /**
     * Job 是否可执行或重试（手动 / 失败 / 已取消）
     */
    public boolean isRetryableJob() {
        return retryableJob;
    }

    /**
     * 由 API 原始状态名映射为枚举；未知返回 null
     */
    public static PipelineStatus of(String apiName) {
        if (apiName == null) {
            return null;
        }
        for (PipelineStatus s : values()) {
            if (s.apiName.equals(apiName)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 展示用中文文案；未知状态原样返回原始串（保证不漏信息）
     */
    public static String display(String apiName) {
        PipelineStatus s = of(apiName);
        if (s != null) {
            return s.label();
        }
        return apiName == null ? UNKNOWN_LABEL : apiName;
    }

    /**
     * 展示用颜色；未知状态用灰色
     */
    public static Color displayColor(String apiName) {
        PipelineStatus s = of(apiName);
        return s != null ? s.color() : UNKNOWN_COLOR;
    }

    public static boolean isActive(String apiName) {
        PipelineStatus s = of(apiName);
        return s != null && s.active;
    }

    public static boolean isRetryablePipeline(String apiName) {
        PipelineStatus s = of(apiName);
        return s != null && s.retryablePipeline;
    }

    public static boolean isRetryableJob(String apiName) {
        PipelineStatus s = of(apiName);
        return s != null && s.retryableJob;
    }
}