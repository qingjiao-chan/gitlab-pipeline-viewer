package com.gitlab.pipeline.viewer.util;

import com.gitlab.pipeline.viewer.services.GitLabFieldNames;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Gson 读取辅助：避免字段缺失/类型异常导致解析失败
 */
public final class JsonUtil {
    private JsonUtil() {
    }

    public static String stringValue(JsonObject o, String key, String def) {
        JsonElement e = o == null ? null : o.get(key);
        return (e == null || e.isJsonNull()) ? def : e.getAsString();
    }

    public static long longValue(JsonObject o, String key, long def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return def;
        }
    }

    public static int intValue(JsonObject o, String key, int def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsInt();
        } catch (Exception ex) {
            return def;
        }
    }

    public static boolean booleanValue(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return def;
        }
    }

    public static JsonObject object(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /**
     * 遍历 JsonArray，把每个元素的字符串字段按 key 收集为 List（非对象/缺失跳过）
     */
    public static List<String> stringList(JsonArray arr, String key) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (JsonElement e : arr) {
            if (e != null && e.isJsonObject()) {
                out.add(stringValue(e.getAsJsonObject(), key, ""));
            }
        }
        return out;
    }

    /**
     * 把 JsonArray 中每个对象元素经 mapper 映射为实体列表（非对象/缺失跳过）
     */
    public static <T> List<T> mapList(JsonElement arr, Function<JsonObject, T> mapper) {
        List<T> out = new ArrayList<>();
        if (arr == null || !arr.isJsonArray()) {
            return out;
        }
        for (JsonElement e : (JsonArray) arr) {
            if (e != null && e.isJsonObject()) {
                out.add(mapper.apply(e.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * 是否为「顶层组」：parent_id 为空或 0（无法直接读取的属性特殊处理入口）
     */
    public static boolean isRootLevel(JsonObject o) {
        JsonElement pid = o == null ? null : o.get(GitLabFieldNames.PARENT_ID);
        if (pid == null || pid.isJsonNull()) {
            return true;
        }
        try {
            return pid.getAsLong() == 0L;
        } catch (Exception ex) {
            return true;
        }
    }
}
