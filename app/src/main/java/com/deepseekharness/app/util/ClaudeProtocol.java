package com.deepseekharness.app.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;

/** Claude 子进程协议边界；限制单行大小，保留用户内容原文。 */
public final class ClaudeProtocol {
    public static final int MAX_LINE = 262144;
    private ClaudeProtocol() { }
    public static JsonObject event(String line) {
        if (line.length() > MAX_LINE) throw new IllegalArgumentException("Claude 输出超过单条消息限制");
        try {
            var value = JsonParser.parseString(line);
            if (value.isJsonObject() && !text(value.getAsJsonObject(), "type").isEmpty()) return value.getAsJsonObject();
        } catch (RuntimeException ignored) { }
        return null;
    }
    public static String text(JsonObject value, String key) {
        try { return value.has(key) ? value.get(key).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }
    public static boolean flag(JsonObject value, String key) {
        var item = value.get(key);
        return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean() && item.getAsBoolean();
    }
    public static boolean validEndpoint(String value) {
        if (value.isEmpty()) return true;
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) return false;
            return "https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
        } catch (Exception invalid) { return false; }
    }
    public static String boundedTranscript(String value) {
        if (value.length() <= 131072) return value;
        int start = value.length() - 131072;
        if (Character.isLowSurrogate(value.charAt(start))) start++;
        return "…\n" + value.substring(start);
    }
}
