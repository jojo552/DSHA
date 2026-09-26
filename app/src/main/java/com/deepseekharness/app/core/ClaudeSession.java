package com.deepseekharness.app.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.util.ClaudeProtocol;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.SensitiveData;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 与 Activity 生命周期分离；旋转保留请求，停止确认进程组退出后才释放维护锁。 */
public final class ClaudeSession {
    private static ClaudeSession instance;
    private static final java.util.concurrent.atomic.AtomicLong serial = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<com.deepseekharness.app.PtySession, Long> terminals = new java.util.LinkedHashMap<>();
    public static synchronized void registerTerminal(com.deepseekharness.app.PtySession terminal) { terminals.put(terminal, serial.incrementAndGet()); }
    public static synchronized long phoneSession() {
        Run run = instance == null ? null : instance.current;
        if (run != null && !run.install && !run.cancelled) return run.id;
        terminals.entrySet().removeIf(entry -> !entry.getKey().isRunning());
        return terminals.isEmpty() ? 0 : terminals.values().iterator().next();
    }
    public static synchronized ClaudeSession get(Context context) {
        if (instance == null) instance = new ClaudeSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final ConfigStore config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable listener;
    private volatile Run current;
    private String transcript, session;
    private String status = "";
    private final java.util.ArrayDeque<JsonObject> permissions = new java.util.ArrayDeque<>();
    private boolean streamed;
    private boolean hasText;
    private static final class Run {
        volatile Process process;
        volatile boolean cancelled;
        volatile boolean workerDone;
        final long id = serial.incrementAndGet();
        RuntimeTasks work;
        final boolean install;
        final java.util.concurrent.Semaphore outputSlots = new java.util.concurrent.Semaphore(64);
        Run(boolean install) { this.install = install; }
    }
    private ClaudeSession(Context context) {
        this.context = context; config = new ConfigStore(context);
        transcript = config.getClaudeTranscript(); session = config.getClaudeSession();
    }
    public void listen(Runnable callback) { listener = callback; }
    public void detach(Runnable callback) { if (listener == callback) listener = null; }
    public String transcript() { return transcript; }
    public String status() { return status; }
    public boolean busy() { return current != null; }
    public JsonObject permission() { return permissions.peekFirst(); }
    public void newConversation() {
        if (busy()) return;
        session = ""; transcript = ""; permissions.clear(); status = ""; save(); changed();
    }
    public boolean send(String prompt) {
        if (busy() || prompt.trim().isEmpty() || prompt.length() > 32000) return false;
        JsonObject request = new JsonObject(); request.addProperty("type", "run");
        request.addProperty("prompt", prompt); request.addProperty("cwd", config.getWorkdir());
        request.addProperty("resume", session); request.addProperty("model", config.getClaudeModel());
        request.addProperty("baseUrl", config.getClaudeBaseUrl());
        try { request.addProperty("apiKey", config.getClaudeApiKey()); }
        catch (RuntimeException error) { status = "Claude 凭据无法解密，请重新保存配置"; changed(); return false; }
        append("\n你：\n" + prompt + "\n\nClaude：\n"); streamed = false; hasText = false;
        start(false, request); return true;
    }
    public void install() { if (!busy()) start(true, null); }
    private void start(boolean install, JsonObject request) {
        Run run = new Run(install); current = run; status = install ? "正在安装 Claude Code…" : "Claude 正在处理…";
        permissions.clear(); changed();
        try { com.deepseekharness.app.ClaudeService.start(context); }
        catch (RuntimeException error) {current = null; status = safe(error); changed(); return;}
        new Thread(() -> execute(run, request), "dsha-claude").start();
    }
    private void execute(Run run, JsonObject request) {
        String failure = null;
        try {
            EnvironmentTaskGate.Lease gate = EnvironmentTaskGate.tryAcquire("Claude Code");
            if (gate == null) throw new IOException("环境正在维护，请稍后重试");
            try (gate) { gate.run(() -> {
                if (run.cancelled) return null;
                run.work = RuntimeTasks.beginDetached("Claude Code");
                var proot = HarnessController.get(context).proot();
                if (!proot.isEnvironmentReady()) throw new IOException("环境未就绪，请先完成安装");
                String command = run.install ? "exec /bin/bash /usr/local/share/dsha/claude/install.sh"
                        : "exec /usr/local/bin/node /usr/local/share/dsha/claude/runner.cjs";
                run.process = proot.execClaude(command);
                if (!run.install) {
                    if (HttpShellService.instance() == null) new HttpShellService(context).start();
                    write(run, request);
                } else run.process.getOutputStream().close();
                return null;
            }); }
            if (run.process != null) read(run);
        } catch (Exception error) { failure = safe(error); }
        finally {
            boolean closed = run.process == null;
            if (run.process != null) {
                try { run.process.destroy(); closed = ProcessTermination.awaitExit(run.process, 5000); }
                catch (RuntimeException error) { failure = safe(error); }
            }
            if (closed && run.work != null) run.work.close();
            run.workerDone = true;
            final boolean finished = closed;
            final String problem = failure;
            main.post(() -> {
                if (current != run) return;
                permissions.clear();
                if (finished) {current = null; com.deepseekharness.app.ClaudeService.stop(context);}
                status = !finished ? "未确认进程退出，保留环境保护；请重试停止"
                        : run.cancelled ? "已停止；可发送消息继续会话"
                        : problem != null ? problem : run.install ? "安装完成，请配置密钥或登录" : "本轮已结束";
                save(); changed();
            });
        }
    }
    private void read(Run run) throws Exception {
        InputStreamReader reader = new InputStreamReader(run.process.getInputStream(), StandardCharsets.UTF_8);
        StringBuilder pending = new StringBuilder(); char[] chars = new char[4096];
        while (true) {
            if (run.cancelled) { run.process.destroy(); return; }
            while (reader.ready()) {
                int count = reader.read(chars); if (count < 0) break;
                pending.append(chars, 0, count);
                int end;
                while ((end = pending.indexOf("\n")) >= 0) {
                    String line = pending.substring(0, end); pending.delete(0, end + 1);
                    if (line.length() > ClaudeProtocol.MAX_LINE) throw new IOException("Claude 单条输出过大");
                    deliver(run, line);
                }
                if (pending.length() > ClaudeProtocol.MAX_LINE) throw new IOException("Claude 单条输出过大");
            }
            if (ProcessTermination.exited(run.process)) {
                if (reader.ready()) continue;
                if (pending.length() > 0) deliver(run, pending.toString());
                if (run.process.exitValue() != 0) throw new IOException("Claude 进程退出码 " + run.process.exitValue() + "，请查看上方输出");
                return;
            }
            Thread.sleep(25);
        }
    }
    private void deliver(Run run, String line) throws InterruptedException {
        // 对 UI 队列施加背压，旧设备不会因安装日志或流式输出积压而无限占用内存。
        while (!run.outputSlots.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (run.cancelled) return;
        }
        main.post(() -> { try { receive(run, line); } finally { run.outputSlots.release(); } });
    }
    private void receive(Run run, String line) {
        if (current != run) return;
        JsonObject event = run.install ? null : ClaudeProtocol.event(line);
        if (event == null) { if (!line.trim().isEmpty()) append("\n" + SensitiveData.redact(line) + "\n"); changed(); return; }
        String type = ClaudeProtocol.text(event, "type"), text = ClaudeProtocol.text(event, "text");
        switch (type) {
            case "session": session = ClaudeProtocol.text(event, "id"); save(); break;
            case "delta": streamed = true; hasText = true; append(text); break;
            case "assistant": if (!streamed) append(text); hasText |= !text.isEmpty(); streamed = false; break;
            case "tool": append("\n[" + ClaudeProtocol.text(event, "name") + "]\n"); break;
            case "permission":
                if (!run.cancelled && !ClaudeProtocol.text(event, "id").isEmpty()) {
                    permissions.addLast(event); status = "等待你的答复";
                }
                break;
            case "result":
                String id = ClaudeProtocol.text(event, "id"); if (!id.isEmpty()) session = id;
                if (ClaudeProtocol.flag(event, "error") || !hasText) append("\n" + text + "\n");
                save(); break;
            case "error": append("\n" + text + "\n"); break;
            default: break;
        }
        changed();
    }
    public void answer(boolean allow, JsonObject answers) {
        Run run = current;
        JsonObject permission = permissions.peekFirst();
        if (run == null || run.cancelled || permission == null) return;
        JsonObject response = new JsonObject(); response.addProperty("type", "permission");
        response.addProperty("id", ClaudeProtocol.text(permission, "id")); response.addProperty("allow", allow);
        if (answers != null) response.add("answers", answers);
        permissions.removeFirst(); status = permissions.isEmpty() ? "Claude 正在处理…" : "等待你的答复"; changed();
        new Thread(() -> { try { write(run, response); }
            catch (IOException error) { main.post(() -> {status = safe(error); changed();}); }
        }, "dsha-claude-answer").start();
    }
    private static void write(Run run, JsonObject message) throws IOException {
        synchronized (run) {
            if (run.cancelled || run.process == null) throw new IOException("会话已停止");
            run.process.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
            run.process.getOutputStream().flush();
        }
    }
    public void stop() {
        Run run = current; if (run == null) return;
        run.cancelled = true; status = "正在停止…"; changed();
        new Thread(() -> { try { stopAndWait(5000); }
            catch (Exception error) { main.post(() -> {status = safe(error); changed();}); }
        }, "dsha-claude-stop").start();
    }
    public static void shutdownAndWait(long timeout) throws IOException {
        ClaudeSession value; synchronized (ClaudeSession.class) { value = instance; }
        if (value != null) value.stopAndWait(timeout);
    }
    private void stopAndWait(long timeout) throws IOException {
        Run run = current; if (run == null) return;
        run.cancelled = true;
        long end = System.nanoTime() + timeout * 1000000L;
        do {
            if (run.process != null) {
                try { run.process.destroy(); } catch (RuntimeException error) { throw new IOException(safe(error)); }
                if (run.workerDone && ProcessTermination.exited(run.process)) {
                    if (run.work != null) run.work.close();
                    main.post(() -> {if (current == run) {current = null; permissions.clear(); status = "已停止；可发送消息继续会话"; com.deepseekharness.app.ClaudeService.stop(context); changed();}});
                    return;
                }
            }
            if (current != run) return;
            try { Thread.sleep(30); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        } while (System.nanoTime() < end);
        throw new IOException("尚未确认 Claude 子进程退出，环境仍受保护");
    }
    private static String safe(Throwable error) { return SensitiveData.redact(String.valueOf(error.getMessage())); }
    private void append(String value) { transcript = ClaudeProtocol.boundedTranscript(transcript + value); }
    private void save() { config.saveClaudeConversation(session, transcript); }
    private void changed() { if (listener != null) listener.run(); }
}
