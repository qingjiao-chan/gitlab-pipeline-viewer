package com.gitlab.pipeline.viewer.util;

/**
 * Git 远程地址解析，兼容多种格式：
 * - SSH scp 风格：git@gitlab.com:group/subgroup/project.git
 * - HTTPS：https://gitlab.com/group/subgroup/project.git
 * - 带端口：ssh://git@gitlab.example.com:2222/group/project.git
 * - 带端口 HTTPS：http://gitlab.example.com:8080/group/project.git
 */
public final class GitUrlUtil {
    private GitUrlUtil() {
    }

    /**
     * 提取 host（不含端口），用于判断是否为同一 GitLab 实例
     */
    public static String extractHost(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        if (u.isEmpty()) {
            return "";
        }
        if (u.startsWith("git@")) {
            String rest = u.substring(4);
            int colon = rest.indexOf(':');
            int slash = rest.indexOf('/');
            if (colon >= 0 && (slash < 0 || colon < slash)) {
                return stripPort(rest.substring(0, colon));
            }
        }
        int schemeIdx = u.indexOf("://");
        if (schemeIdx >= 0) {
            u = u.substring(schemeIdx + 3);
        }
        if (u.startsWith("git@")) {
            u = u.substring(4);
        }
        int slash = u.indexOf('/');
        String host = slash >= 0 ? u.substring(0, slash) : u;
        int at = host.indexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        return stripPort(host);
    }

    /**
     * 提取 GitLab 项目路径，如 group/subgroup/project（去除 .git 与前后斜杠）
     */
    public static String extractPath(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        if (u.isEmpty()) {
            return "";
        }
        String path;
        if (u.startsWith("git@")) {
            String rest = u.substring(4);
            int colon = rest.indexOf(':');
            int slash = rest.indexOf('/');
            if (colon >= 0 && (slash < 0 || colon < slash)) {
                path = rest.substring(colon + 1);
            } else {
                path = rest;
            }
        } else {
            int schemeIdx = u.indexOf("://");
            if (schemeIdx >= 0) {
                u = u.substring(schemeIdx + 3);
            }
            if (u.startsWith("git@")) {
                u = u.substring(4);
            }
            int slash = u.indexOf('/');
            path = slash >= 0 ? u.substring(slash + 1) : u;
        }
        return cleanPath(path);
    }

    private static String cleanPath(String p) {
        p = p == null ? "" : p.trim();
        if (p.endsWith(".git")) {
            p = p.substring(0, p.length() - 4);
        }
        p = p.replaceAll("^/+", "").replaceAll("/+$", "");
        return p;
    }

    private static String stripPort(String host) {
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }
}