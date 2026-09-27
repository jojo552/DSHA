package com.deepseekharness.app.ui;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.ClaudeProtocol;
import com.deepseekharness.app.util.ClaudeQuestionAnswer;
import com.deepseekharness.app.util.UiText;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;

/** 手机上的单选、多选与补充答案；程序同步勾选时不反过来覆盖草稿。 */
final class ClaudeQuestionInput {
    final ClaudeQuestionAnswer state;
    private final EditText custom;
    private final LinkedHashMap<String, CompoundButton> choices = new LinkedHashMap<>();
    private boolean syncing;

    ClaudeQuestionInput(CardPage page, LinearLayout parent, JsonObject question, JsonObject saved) {
        state = new ClaudeQuestionAnswer(question);
        if (saved != null) state.restore(saved);
        parent.addView(page.text(ClaudeProtocol.text(question, "question"), 14, R.color.text));
        parent.addView(page.text(UiText.text(state.multiple() ? "可选择多项，也可以自行填写" : "点选一项，也可以自行填写"), 12, R.color.text_secondary));
        if (question.has("options") && question.get("options").isJsonArray()) {
            for (var item : question.getAsJsonArray("options")) {
                if (!item.isJsonObject()) continue;
                String label = ClaudeProtocol.text(item.getAsJsonObject(), "label");
                if (label.isEmpty() || choices.containsKey(label)) continue;
                CompoundButton button = state.multiple() ? new CheckBox(page.context) : new RadioButton(page.context);
                String description = ClaudeProtocol.text(item.getAsJsonObject(), "description");
                button.setText(label + (description.isEmpty() ? "" : "\n" + description));
                button.setTextColor(page.context.getColor(R.color.text)); button.setMinHeight(page.dp(48));
                button.setOnCheckedChangeListener((view, checked) -> {
                    if (syncing) return;
                    state.select(label, checked); sync();
                });
                choices.put(label, button); parent.addView(button);
            }
        }
        custom = new EditText(page.context); custom.setHint(UiText.text("其他答案（选填）"));
        custom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        custom.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI); custom.setMaxLines(4);
        custom.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8000)});
        custom.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!syncing) { state.custom(s.toString()); sync(); }
            }
            public void afterTextChanged(Editable value) { }
        });
        parent.addView(custom); sync();
    }
    private void sync() {
        syncing = true;
        for (var entry : choices.entrySet()) entry.getValue().setChecked(state.selected(entry.getKey()));
        if (!custom.getText().toString().equals(state.custom())) custom.setText(state.custom());
        custom.setError(null); syncing = false;
    }
    boolean validate() {
        if (!state.answer().isEmpty()) return true;
        custom.setError(UiText.text("请选择或填写答案")); custom.requestFocus(); return false;
    }
}
