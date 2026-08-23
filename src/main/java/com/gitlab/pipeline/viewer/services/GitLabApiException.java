package com.gitlab.pipeline.viewer.services;

/**
 * GitLab API 调用失败（非 2xx）时抛出，携带 HTTP 状态码便于提示 401/403/404
 */
public class GitLabApiException extends Exception {
    public final int statusCode;

    public GitLabApiException(int statusCode, String body) {
        super("GitLab API 错误 " + statusCode + ": " + truncate(body));
        this.statusCode = statusCode;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}