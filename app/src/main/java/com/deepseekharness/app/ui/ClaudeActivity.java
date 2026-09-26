package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
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
    private Button send, install, fresh, settings, login;
    private LinearLayout approval;
    private String permissionId = "";
    private boolean renderPending;
    private final Runnable refresh = this::render;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable delayedRender = this::renderNow;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = ClaudeSession.get(this);
        page = new CardPage(this, "Claude Code", t("在手机上编程和操作应用。首次使用请安装并配置登录。"));
        UiNavigation.addHeader(this, page.root, "Claude Code", this::finish);
        LinearLayout controls = page.card();
        install = page.button(controls, t("安装 Claude Code"), false, session::install);
        settings = page.button(controls, t("连接配置"), false, this::settings);
        login = page.button(controls, t("登录 / 完整终端"), false, this::terminalMenu);
        fresh = page.button(controls, t("新对话"), false, session::newConversation);
        page.button(controls, t("手机操作授权"), false,
                () -> startActivity(new Intent(this, AccessibilitySetupActivity.class)));
        status = page.text("", 12, R.color.text_secondary); page.content.addView(status);
        transcript = page.text("", 15, R.color.text); transcript.setTextIsSelectable(true);
        transcript.setPadding(0, page.dp(12), 0, page.dp(12)); page.content.addView(transcript);
        approval = page.column(); page.content.addView(approval);
        prompt = new EditText(this); prompt.setHint(t("发送给 Claude…"));
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        prompt.setMinLines(1); prompt.setMaxLines(4); prompt.setTextColor(getColor(R.color.text));
        prompt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32000)});
        page.footer.addView(prompt);
        send = page.button(page.footer, t("发送"), true, () -> {
            if (session.busy()) session.stop();
            else if (session.send(prompt.getText().toString())) prompt.setText("");
        });
        if (saved != null) prompt.setText(saved.getString("draft", ""));
        setContentView(page.root); renderNow();
    }
    @Override protected void onStart() { super.onStart(); session.listen(refresh); renderNow(); }
    @Override protected void onStop() { session.detach(refresh); ui.removeCallbacks(delayedRender); renderPending = false; super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("draft", prompt.getText().toString()); super.onSaveInstanceState(out); }
    private void render() {
        // 固定频率刷新，连续输出时也能更新，避免每个 token 都重排整段文字。
        if (renderPending) return;
        renderPending = true;
        ui.postDelayed(delayedRender, 60);
    }
    private void renderNow() {
        renderPending = false;
        if (transcript == null) return;
        transcript.setText(session.transcript()); status.setText(com.deepseekharness.app.util.UiStateText.render(session.status()));
        boolean busy = session.busy(); send.setText(busy ? t("停止") : t("发送"));
        install.setEnabled(!busy); fresh.setEnabled(!busy); settings.setEnabled(!busy); login.setEnabled(!busy);
        JsonObject question = session.permission();
        String id = question == null ? "" : ClaudeProtocol.text(question, "id");
        if (!id.equals(permissionId)) {permissionId = id; showPermission(question);}
    }
    private void showPermission(JsonObject question) {
        approval.removeAllViews(); if (question == null) return;
        approval.addView(page.text(t("Claude 等待你的答复"), 16, R.color.text));
        String tool = ClaudeProtocol.text(question, "tool");
        JsonObject input = question.has("input") && question.get("input").isJsonObject()
                ? question.getAsJsonObject("input") : new JsonObject();
        LinkedHashMap<String, EditText> answers = new LinkedHashMap<>();
        if (tool.equals("AskUserQuestion") && input.has("questions") && input.get("questions").isJsonArray()) {
            for (var item : input.getAsJsonArray("questions")) {
                if (!item.isJsonObject()) continue;
                JsonObject q = item.getAsJsonObject(); String label = ClaudeProtocol.text(q, "question");
                if (label.isEmpty()) continue;
                approval.addView(page.text(label, 14, R.color.text));
                if (q.has("options") && q.get("options").isJsonArray()) {
                    for (var option : q.getAsJsonArray("options")) {
                        if (!option.isJsonObject()) continue;
                        String choice = ClaudeProtocol.text(option.getAsJsonObject(), "label");
                        String description = ClaudeProtocol.text(option.getAsJsonObject(), "description");
                        approval.addView(page.text("• " + choice + (description.isEmpty() ? "" : "：" + description), 12, R.color.text_secondary));
                    }
                }
                EditText answer = new EditText(this); answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                answer.setHint(t("填写答案，可用逗号分隔多项"));
                approval.addView(answer); answers.put(label, answer);
            }
        } else {
            TextView details = page.text(tool + "\n" + input, 13, R.color.text); details.setTextIsSelectable(true); approval.addView(details);
        }
        page.button(approval, t("允许 / 提交答复"), true, () -> {
            JsonObject values = new JsonObject();
            for (var entry : answers.entrySet()) {
                String value = entry.getValue().getText().toString().trim();
                if (value.isEmpty()) {entry.getValue().setError(t("请填写答案")); return;}
                values.addProperty(entry.getKey(), value);
            }
            session.answer(true, answers.isEmpty() ? null : values);
        });
        page.button(approval, t("拒绝"), false, () -> session.answer(false, null));
        page.scroll.post(() -> page.scroll.smoothScrollTo(0, approval.getTop()));
    }
    private void terminalMenu() {
        new DshaDialogBuilder(this).setTitle(t("Claude 终端"))
                .setItems(new String[]{t("登录 Claude 账号"), t("打开完整 Claude Code")},
                        (dialog, which) -> startActivity(new Intent(this, ClaudeTerminalActivity.class).putExtra("login", which == 0))).show();
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
