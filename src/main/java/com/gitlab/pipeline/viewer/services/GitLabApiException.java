package com.gitlab.pipeline.viewer.services;

/**
 * GitLab API 调用失败时抛出，携带 HTTP 状态码便于区分：
 * <ul>
 *   <li>{@code statusCode == 0}：网络层失败（连不上、超时等）；</li>
 *   <li>{@code statusCode == -1}：TLS/证书层失败（自签名、内网 CA 不受信、证书吊销检查失败）；</li>
 *   <li>401/403：令牌无效/过期/缺权限，提示文案直接引导用户去设置检查；</li>
 *   <li>404：资源不存在或无权限（GitLab 无权限时也常返回 404，避免侧漏资源存在性）；</li>
 *   <li>429：被限流。</li>
 * </ul>
 */
public class GitLabApiException extends Exception {
    public final int statusCode;

    public GitLabApiException(int statusCode, String body) {
        super(buildMessage(statusCode, body));
        this.statusCode = statusCode;
    }

    public GitLabApiException(int statusCode, String message, Throwable cause) {
        super(buildMessage(statusCode, message), cause);
        this.statusCode = statusCode;
    }

    public boolean isAuthError() {
        return statusCode == 401 || statusCode == 403;
    }

    /**
     * TLS/证书层失败：给出可操作的证书信任引导，而不是笼统的"网络不可达"。
     */
    public static GitLabApiException sslFailure(Throwable cause) {
        return new GitLabApiException(-1, cause == null ? "" : String.valueOf(cause.getMessage()), cause);
    }

    private static String buildMessage(int statusCode, String body) {
        String detail = truncate(body);
        return switch (statusCode) {
            case -1 -> "SSL/TLS 连接失败：服务器证书不受信任（可能是自签名证书或内网 CA 签发）。"
                    + "请在 IDE 的 Settings → Tools → Server Certificates 中接受该证书后重试；"
                    + "若正在使用抓包/代理工具（如 Fiddler、dev-sidecar），请检查其根证书是否已被 IDE 信任"
                    + (detail.isEmpty() ? "" : "。底层错误：" + detail);
            case 0 -> "无法连接 GitLab 服务器（网络不可达、地址错误或请求超时）"
                    + (detail.isEmpty() ? "" : "：" + detail);
            case 401, 403 -> "GitLab 鉴权失败（HTTP " + statusCode
                    + "）：访问令牌无效、已过期或缺少 api 权限，请在「设置」中检查该账号的令牌"
                    + (detail.isEmpty() ? "" : "。服务器响应：" + detail);
            case 404 -> "GitLab 资源不存在或无权访问（HTTP 404），请检查服务器地址与项目路径"
                    + (detail.isEmpty() ? "" : "。服务器响应：" + detail);
            case 429 -> "已触发 GitLab 访问限流（HTTP 429），请稍后重试或调大自动刷新间隔"
                    + (detail.isEmpty() ? "" : "。服务器响应：" + detail);
            default -> "GitLab API 错误 " + statusCode + (detail.isEmpty() ? "" : ": " + detail);
        };
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
