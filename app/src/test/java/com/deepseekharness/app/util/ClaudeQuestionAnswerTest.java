package com.deepseekharness.app.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClaudeQuestionAnswerTest {
    private static ClaudeQuestionAnswer question(boolean multiple) {
        JsonObject value = JsonParser.parseString("{\"options\":[{\"label\":\"方案甲\"},{\"label\":\"方案乙\"}]}").getAsJsonObject();
        value.addProperty("multiSelect", multiple); return new ClaudeQuestionAnswer(value);
    }
    @Test public void singleChoiceReplacesPreviousAndIgnoresUnknownOption() {
        var answer = question(false); answer.select("方案甲", true); answer.select("方案乙", true);
        answer.select("不存在", true); assertEquals("方案乙", answer.answer());
        assertFalse(answer.selected("方案甲"));
    }
    @Test public void multipleChoicesCanBeRemovedAndPreserveSelectionOrder() {
        var answer = question(true); answer.select("方案乙", true); answer.select("方案甲", true);
        assertEquals("方案乙, 方案甲", answer.answer()); answer.select("方案乙", false);
        assertEquals("方案甲", answer.answer());
    }
    @Test public void typingAndSelectingNeverSubmitHiddenStaleAnswers() {
        var answer = question(true); answer.select("方案甲", true); answer.custom("  自定义答案  ");
        assertEquals("自定义答案", answer.answer()); assertFalse(answer.selected("方案甲"));
        answer.select("方案乙", true); assertEquals("", answer.custom()); assertEquals("方案乙", answer.answer());
        answer.custom("  "); assertEquals("", answer.answer());
    }
    @Test public void rotationRestoresMultipleChoicesAndUntrimmedDraft() {
        var before = question(true); before.select("方案甲", true); before.select("方案乙", true);
        var after = question(true); after.restore(before.snapshot()); assertEquals(before.answer(), after.answer());
        before.custom("  未完成的回答\n"); after.restore(before.snapshot()); assertEquals(before.custom(), after.custom());
    }
    @Test public void restoredDataCannotAddRemovedOptionsOrBreakSingleChoice() {
        var answer = question(false);
        answer.restore(JsonParser.parseString("{\"selected\":[\"方案甲\",{},\"失效选项\",\"方案乙\"]}").getAsJsonObject());
        assertEquals("方案乙", answer.answer());
        answer.restore(JsonParser.parseString("{\"selected\":[\"失效选项\"],\"custom\":null}").getAsJsonObject());
        assertEquals("", answer.answer());
    }
}
