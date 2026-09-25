package com.gitlab.pipeline.viewer.services;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * GitLab 访问令牌的统一存取入口：基于 IDE 内置密码库（{@link PasswordSafe}），
 * 在 Windows 上落凭据管理器、macOS 上落 Keychain、Linux 上落 libsecret/KEP，
 * 令牌不再以明文写入插件配置 XML。
 * <p>
 * 2023.1+ 的密码库 API 以 {@link CredentialAttributes} 为键（旧版
 * (requester, Class, key) 便捷签名在 2023.2 已移除），本类按账号 id 构造
 * serviceName 实现多账号令牌隔离。
 * <p>
 * 注意：任何日志 / 异常消息中都不允许打印令牌内容。
 */
public final class TokenStore {

    private static final String SERVICE_NAME = "GitLab Pipeline Viewer";

    private TokenStore() {
    }

    @NotNull
    private static CredentialAttributes attributes(@NotNull String accountId) {
        return new CredentialAttributes(SERVICE_NAME + " — " + accountId, null, TokenStore.class);
    }

    /**
     * 读取账号令牌；未存储时返回 null（空串/未配置统一由调用方归一化）。
     */
    public static @Nullable String getToken(@NotNull String accountId) {
        if (accountId.isEmpty()) {
            return null;
        }
        return PasswordSafe.getInstance().getPassword(attributes(accountId));
    }

    /**
     * 保存账号令牌；token 为 null/空串时等价于移除。
     */
    public static void setToken(@NotNull String accountId, @Nullable String token) {
        if (accountId.isEmpty()) {
            return;
        }
        PasswordSafe.getInstance().setPassword(attributes(accountId),
                token == null || token.isEmpty() ? null : token);
    }

    /**
     * 删除账号令牌（删除账号时必须同步调用）。
     */
    public static void removeToken(@NotNull String accountId) {
        if (accountId.isEmpty()) {
            return;
        }
        PasswordSafe.getInstance().setPassword(attributes(accountId), null);
    }
}
