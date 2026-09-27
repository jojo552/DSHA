package com.deepseekharness.app.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** 点选和自定义回答共用一份状态，旋转恢复时只接受本题仍存在的选项。 */
public final class ClaudeQuestionAnswer {
    private final boolean multiple;
    private final Set<String> options = new LinkedHashSet<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private String custom = "";

    public ClaudeQuestionAnswer(JsonObject question) {
        multiple = ClaudeProtocol.flag(question, "multiSelect");
        if (question.has("options") && question.get("options").isJsonArray()) {
            for (var item : question.getAsJsonArray("options")) {
                if (!item.isJsonObject()) continue;
                String label = ClaudeProtocol.text(item.getAsJsonObject(), "label");
                if (!label.isEmpty()) options.add(label);
            }
        }
    }
    public boolean multiple() { return multiple; }
    public boolean selected(String option) { return selected.contains(option); }
    public String custom() { return custom; }
    public void select(String option, boolean checked) {
        if (!options.contains(option)) return;
        if (checked) {
            if (!multiple) selected.clear();
            selected.add(option); custom = "";
        } else selected.remove(option);
    }
    public void custom(String value) { custom = value; selected.clear(); }
    public String answer() { return custom.trim().isEmpty() ? String.join(", ", selected) : custom.trim(); }
    public JsonObject snapshot() {
        JsonObject value = new JsonObject(); JsonArray choices = new JsonArray();
        for (String option : selected) choices.add(option);
        value.add("selected", choices); value.addProperty("custom", custom); return value;
    }
    public void restore(JsonObject value) {
        selected.clear(); custom = "";
        if (value.has("selected") && value.get("selected").isJsonArray()) {
            for (var option : value.getAsJsonArray("selected")) {
                if (option.isJsonPrimitive() && option.getAsJsonPrimitive().isString()) select(option.getAsString(), true);
            }
        }
        String text = ClaudeProtocol.text(value, "custom");
        if (!text.isEmpty()) custom(text);
    }
}
