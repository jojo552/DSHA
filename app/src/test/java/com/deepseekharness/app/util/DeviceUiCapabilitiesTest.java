package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceUiCapabilitiesTest {
    @Test public void androidSixUsesNodeActions() {
        var value = DeviceUiCapabilities.describe(23, true).getAsJsonObject("available");
        assertTrue(value.get("android_click_text").getAsBoolean());
        assertTrue(value.get("android_scroll").getAsBoolean());
        assertFalse(value.get("android_click").getAsBoolean());
        assertFalse(value.get("android_screenshot").getAsBoolean());
    }
    @Test public void gesturesAndScreenshotsHaveDifferentMinimumVersions() {
        assertTrue(DeviceUiCapabilities.describe(24, true).getAsJsonObject("available").get("android_swipe").getAsBoolean());
        assertFalse(DeviceUiCapabilities.describe(29, true).getAsJsonObject("available").get("android_screenshot").getAsBoolean());
        assertTrue(DeviceUiCapabilities.describe(30, true).getAsJsonObject("available").get("android_screenshot").getAsBoolean());
    }
    @Test public void disconnectedServiceAdvertisesNoActions() {
        for (var entry : DeviceUiCapabilities.describe(37, false).getAsJsonObject("available").entrySet()) assertFalse(entry.getValue().getAsBoolean());
    }
}
