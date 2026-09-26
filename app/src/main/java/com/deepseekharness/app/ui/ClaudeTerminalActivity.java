package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;

/** 登录和完整 CLI 复用已有 PTY、输入法、生命周期与维护屏障。 */
public final class ClaudeTerminalActivity extends AppCompatActivity {
    private static final int CONTAINER = 0x00c1a0de;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this); root.setId(CONTAINER); setContentView(root);
        if (state == null) {
            PtyTerminalFragment terminal = new PtyTerminalFragment();
            Bundle args = new Bundle(); args.putBoolean("claude_terminal", true);
            args.putBoolean("claude_login", getIntent().getBooleanExtra("login", false)); terminal.setArguments(args);
            getSupportFragmentManager().beginTransaction().replace(CONTAINER, terminal).commit();
        }
    }
}
