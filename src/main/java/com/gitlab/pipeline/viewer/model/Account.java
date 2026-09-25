package com.gitlab.pipeline.viewer.model;

import org.jetbrains.annotations.NotNull;

/**
 * GitLab 账号：一个服务地址对应一份访问凭据（令牌本身不保存在本实体中，统一经
 * {@code TokenStore} 存入 IDE 密码库）。多账号场景下设置中可维护多个账号，
 * 同一时刻只有一个「当前账号」生效。
 *
 * @param id   账号稳定标识（密码库 key、缓存隔离都基于它，生成后不变）
 * @param name 展示名称（用户可改）
 * @param url  GitLab 服务地址（不带尾斜杠）
 */
public record Account(@NotNull String id, @NotNull String name, @NotNull String url) {

    public Account {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        url = url == null ? "" : url;
    }
}
