package com.deepseekharness.app.util;

import com.google.gson.JsonObject;

/** 只描述实际接通的无障碍路径，不把系统版本支持误报为已获授权。 */
public final class DeviceUiCapabilities {
    private DeviceUiCapabilities() { }
    public static JsonObject describe(int api, boolean connected) {
        JsonObject out = new JsonObject();
        out.addProperty("androidApi", api); out.addProperty("accessibilityConnected", connected);
        out.addProperty("requiresDshaApproval", true);
        JsonObject supported = new JsonObject();
        for (String tool : new String[]{"android_get_state", "android_click_text", "android_type", "android_key", "android_scroll"})
            supported.addProperty(tool, api >= 23 && connected);
        supported.addProperty("android_click", api >= 24 && connected);
        supported.addProperty("android_swipe", api >= 24 && connected);
        supported.addProperty("android_screenshot", api >= 30 && connected);
        out.add("available", supported);
        out.addProperty("guidance", api < 24 ? "Android 6 使用文字点击与控件滚动；本无障碍通道不支持坐标手势和截图。"
                : api < 30 ? "本无障碍通道不支持截图，可先读取页面结构。" : "先读取页面，再操作并验证。受保护窗口可能不允许截图。");
        return out;
    }
}
