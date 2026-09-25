package com.gitlab.pipeline.viewer.model;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * 当前令牌对应的 GitLab 用户（GET /user 返回），用于「测试连接」就地反馈。
 *
 * @param id       用户数字 id（缺失为 0）
 * @param username 登录名（缺失为空串）
 * @param name     显示名称（缺失时退回 username）
 */
public record CurrentUser(long id, @NotNull String username, @NotNull String name) {

    public static CurrentUser from(JsonObject o) {
        long id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsLong() : 0L;
        String username = o.has("username") && !o.get("username").isJsonNull()
                ? o.get("username").getAsString() : "";
        String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
        return new CurrentUser(id, username, name.isEmpty() ? username : name);
    }
}
