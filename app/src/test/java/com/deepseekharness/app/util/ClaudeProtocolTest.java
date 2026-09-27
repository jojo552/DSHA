package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClaudeProtocolTest {
    @Test public void endpointsRejectCredentialLeakAndInvalidSchemes() {
        assertTrue(ClaudeProtocol.validEndpoint(""));
        assertTrue(ClaudeProtocol.validEndpoint("https://api.anthropic.com"));
        assertTrue(ClaudeProtocol.validEndpoint("http://127.0.0.1:8080"));
        assertFalse(ClaudeProtocol.validEndpoint("http://example.com"));
        assertFalse(ClaudeProtocol.validEndpoint("https://user:secret@example.com"));
        assertFalse(ClaudeProtocol.validEndpoint("https://example.com?key=secret"));
        assertFalse(ClaudeProtocol.validEndpoint("file:///root"));
    }
    @Test public void protocolKeepsUnicodeAndRejectsNoise() {
        assertEquals("中文😊", ClaudeProtocol.text(ClaudeProtocol.event("{\"type\":\"delta\",\"text\":\"中文😊\"}"), "text"));
        assertNull(ClaudeProtocol.event("npm notice installed"));
        assertNull(ClaudeProtocol.event("[]"));
        assertNull(ClaudeProtocol.event("{\"type\":null}"));
        assertNull(ClaudeProtocol.event("{\"type\":{}}"));
        assertFalse(ClaudeProtocol.flag(ClaudeProtocol.event("{\"type\":\"result\",\"error\":{}}"), "error"));
        assertTrue(ClaudeProtocol.flag(ClaudeProtocol.event("{\"type\":\"result\",\"error\":true}"), "error"));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProtocol.event("x".repeat(ClaudeProtocol.MAX_LINE + 1)));
    }
    @Test public void transcriptTruncationDoesNotSplitSurrogatePair() {
        String result = ClaudeProtocol.boundedTranscript("a😊" + "b".repeat(131071));
        assertFalse(Character.isLowSurrogate(result.charAt(2)));
        assertTrue(result.length() <= 131074);
    }
}
