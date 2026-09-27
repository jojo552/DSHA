package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ClaudeSession;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.ClaudeProtocol;
import com.deepseekharness.app.util.UiText;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;

/** 原生聊天无需新 WebView API；权限问题作为页面内容保留，旋转后仍可答复。 */
public final class ClaudeActivity extends AppCompatActivity {
    private ClaudeSession session;
    private CardPage page;
    private TextView transcript, status;
    private EditText prompt;
    private Button send, stop;
    private LinearLayout approval;
    private String permissionId = "";
    private String savedPermissionId = "";
    private JsonObject savedAnswers = new JsonObject();
    private final LinkedHashMap<String, ClaudeQuestionInput> answers = new LinkedHashMap<>();
    private boolean followOutput = true;
    private int savedScroll = -1;
    private boolean renderPending;
    private final Runnable refresh = this::render;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable delayedRender = this::renderNow;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = ClaudeSession.get(this);
        page = new CardPage(this, "Claude Code", t("在手机上与 Claude 对话、编程。首次使用请在「更多」中安装并登录。"));
        UiNavigation.addHeader(this, page.root, "Claude Code", this::finish);
        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(page.dp(18), 0, page.dp(18), 0);
        rowButton(controls, t("更多"), false, this::more);
        rowButton(controls, t("最新消息"), false, () -> { followOutput = true; scrollToLatest(); });
        page.root.addView(controls, page.root.indexOfChild(page.scroll));
        status = page.text("", 12, R.color.text_secondary); page.content.addView(status);
        transcript = page.text("", 15, R.color.text); transcript.setTextIsSelectable(true);
        transcript.setPadding(0, page.dp(12), 0, page.dp(12)); page.content.addView(transcript);
        approval = page.column(); page.content.addView(approval);
        prompt = new EditText(this); prompt.setHint(t("发送给 Claude…"));
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        prompt.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        prompt.setMinLines(1); prompt.setMaxLines(4); prompt.setTextColor(getColor(R.color.text));
        prompt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32000)});
        page.footer.addView(prompt);
        LinearLayout actions = new LinearLayout(this); page.footer.addView(actions);
        send = rowButton(actions, t("发送"), true, () -> {
            if (session.send(prompt.getText().toString())) { prompt.setText(""); followOutput = true; scrollToLatest(); }
        });
        stop = rowButton(actions, t("停止"), false, session::stop);
        page.scroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            followOutput = page.content.getHeight() - y - page.scroll.getHeight() <= page.dp(48);
        });
        if (saved != null) {
            prompt.setText(saved.getString("draft", "")); savedScroll = saved.getInt("scroll", 0);
            followOutput = saved.getBoolean("follow", true);
            savedPermissionId = saved.getString("permission", "");
            try { savedAnswers = com.google.gson.JsonParser.parseString(saved.getString("answers", "{}")).getAsJsonObject(); }
            catch (RuntimeException ignored) { savedAnswers = new JsonObject(); }
        }
        setContentView(page.root); renderNow();
    }
    @Override protected void onStart() { super.onStart(); session.listen(refresh); renderNow(); }
    @Override protected void onStop() { session.detach(refresh); ui.removeCallbacks(delayedRender); renderPending = false; super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft", prompt.getText().toString()); out.putInt("scroll", page.scroll.getScrollY());
        out.putBoolean("follow", followOutput); out.putString("permission", permissionId);
        JsonObject values = new JsonObject();
        for (var entry : answers.entrySet()) values.add(entry.getKey(), entry.getValue().state.snapshot());
        out.putString("answers", values.toString()); super.onSaveInstanceState(out);
    }
    private void render() {
        // 固定频率刷新，连续输出时也能更新，避免每个 token 都重排整段文字。
        if (renderPending) return;
        renderPending = true;
        ui.postDelayed(delayedRender, 60);
    }
    private void renderNow() {
        renderPending = false;
        if (transcript == null) return;
        String text = session.transcript();
        if (!text.contentEquals(transcript.getText())) {
            transcript.setText(text);
            if (followOutput) scrollToLatest();
        }
        status.setText(com.deepseekharness.app.util.UiStateText.render(session.status()));
        boolean busy = session.busy(); send.setEnabled(!busy); stop.setVisibility(busy ? View.VISIBLE : View.GONE);
        JsonObject question = session.permission();
        String id = question == null ? "" : ClaudeProtocol.text(question, "id");
        if (!id.equals(permissionId)) {permissionId = id; showPermission(question);}
        if (savedScroll >= 0) {
            int y = savedScroll; savedScroll = -1;
            page.scroll.post(() -> { if (!followOutput) page.scroll.scrollTo(0, y); });
        }
    }
    private void scrollToLatest() { page.scroll.post(() -> { if (followOutput) page.scroll.scrollTo(0, page.content.getHeight()); }); }
    private Button rowButton(LinearLayout row, String label, boolean primary, Runnable action) {
        Button button = page.button(row, label, primary, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(page.dp(3), page.dp(4), page.dp(3), 0); button.setLayoutParams(params); return button;
    }
    private void more() {
        String[] items = {t("安装 Claude Code"), t("连接配置"), t("登录 Claude 账号"), t("打开完整 Claude Code"), t("新对话"), t("可选：手机控制授权")};
        new DshaDialogBuilder(this).setTitle(t("更多")).setItems(items, (dialog, which) -> {
            if (which < 5 && session.busy()) {
                Toast.makeText(this, t("请先停止当前任务"), Toast.LENGTH_SHORT).show(); return;
            }
            switch (which) {
                case 0 -> session.install();
                case 1 -> settings();
                case 2, 3 -> startActivity(new Intent(this, ClaudeTerminalActivity.class).putExtra("login", which == 2));
                case 4 -> { session.newConversation(); followOutput = true; }
                case 5 -> startActivity(new Intent(this, AccessibilitySetupActivity.class));
            }
        }).show();
    }
    private void showPermission(JsonObject question) {
        approval.removeAllViews(); answers.clear(); if (question == null) return;
        approval.addView(page.text(t("Claude 等待你的答复"), 16, R.color.text));
        String tool = ClaudeProtocol.text(question, "tool");
        JsonObject input = question.has("input") && question.get("input").isJsonObject()
                ? question.getAsJsonObject("input") : new JsonObject();
        if (tool.equals("AskUserQuestion") && input.has("questions") && input.get("questions").isJsonArray()) {
            for (var item : input.getAsJsonArray("questions")) {
                if (!item.isJsonObject()) continue;
                JsonObject q = item.getAsJsonObject(); String label = ClaudeProtocol.text(q, "question");
                if (label.isEmpty()) continue;
                JsonObject saved = permissionId.equals(savedPermissionId) && savedAnswers.has(label) && savedAnswers.get(label).isJsonObject()
                        ? savedAnswers.getAsJsonObject(label) : null;
                answers.put(label, new ClaudeQuestionInput(page, approval, q, saved));
            }
        } else {
            TextView details = page.text(tool + "\n" + input, 13, R.color.text); details.setTextIsSelectable(true); approval.addView(details);
        }
        page.button(approval, t("允许 / 提交答复"), true, () -> {
            JsonObject values = new JsonObject();
            for (var entry : answers.entrySet()) {
                if (!entry.getValue().validate()) return;
                values.addProperty(entry.getKey(), entry.getValue().state.answer());
            }
            session.answer(true, answers.isEmpty() ? null : values);
        });
        page.button(approval, t("拒绝"), false, () -> session.answer(false, null));
        savedAnswers = new JsonObject(); savedPermissionId = "";
        if (savedScroll < 0) page.scroll.post(() -> page.scroll.smoothScrollTo(0, approval.getTop()));
    }
    private void settings() {
        ConfigStore config = new ConfigStore(this);
        LinearLayout content = page.column(); content.setPadding(page.dp(18), 0, page.dp(18), 0);
        EditText model = field(content, t("模型（留空使用默认值）"), config.getClaudeModel());
        EditText endpoint = field(content, t("API 地址（留空使用官方服务）"), config.getClaudeBaseUrl());
        EditText key = field(content, t("API Key（留空保留；账号登录无需填写）"), "");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); key.setSaveEnabled(false);
        CheckBox clear = new CheckBox(this); clear.setText(t("清除已保存的 API Key，使用账号登录")); content.addView(clear);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        var dialog = new DshaDialogBuilder(this).setTitle(t("Claude 连接配置"))
                .setView(scroll).setNegativeButton(t("取消"), null).setPositiveButton(t("保存"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
            String url = endpoint.getText().toString().trim();
            if (!ClaudeProtocol.validEndpoint(url)) {endpoint.setError(t("请填写 HTTPS 地址或本机 HTTP 地址")); return;}
            try {
                if (!config.saveClaudeSettings(model.getText().toString().trim(), url, key.getText().toString().trim(), clear.isChecked()))
                    throw new IllegalStateException(t("密钥保存失败，原配置已保留"));
                dialog.dismiss();
            } catch (RuntimeException error) {key.setError(error.getMessage());}
        })); dialog.show();
    }
    private EditText field(LinearLayout parent, String hint, String value) {
        parent.addView(page.text(hint, 12, R.color.text_secondary));
        EditText field = new EditText(this); field.setSingleLine(true); field.setText(value); parent.addView(field); return field;
    }
    private static String t(String zh) { return UiText.text(zh); }
}
