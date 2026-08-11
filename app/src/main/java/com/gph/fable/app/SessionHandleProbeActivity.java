package com.gph.fable.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.gph.fable.shared.view.SystemBarInsets;

/**
 * 工单 25 验证切片：SessionHandle 生产 JNI 探针（独立 launcher，不碰主终端）。
 *
 * [自检] 在真机内跑验收断言：创建/读写/resize/close、六事件回调序列、
 * 诊断日志订阅、8 会话并发独立性（2–4 硬指标内含）。
 */
public class SessionHandleProbeActivity extends Activity {
    private static final String SHELL = "/data/data/com.gph.fable/files/usr/bin/bash";
    private static final String PREFIX = "/data/data/com.gph.fable/files/usr";
    private static final String HOME = "/data/data/com.gph.fable/files/home";
    private static final String TMPDIR = "/data/data/com.gph.fable/files/usr/tmp";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Long> handles = new CopyOnWriteArrayList<>();

    private TextView status;
    private TextView out;
    private ScrollView sv;
    private EditText input;

    /** 事件回调 sink：线程安全记录，自检线程轮询。 */
    private static class EventRow {
        final long sessionId;
        final String event;
        final long ts;
        final byte[] data;
        final int exitCode;
        final String message;

        EventRow(long sessionId, String event, long ts, byte[] data, int exitCode, String message) {
            this.sessionId = sessionId;
            this.event = event;
            this.ts = ts;
            this.data = data;
            this.exitCode = exitCode;
            this.message = message;
        }
    }

    private static class LogRow {
        final long sessionId;
        final int level;
        final String tag;
        final String message;

        LogRow(long sessionId, int level, String tag, String message) {
            this.sessionId = sessionId;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }
    }

    private final List<EventRow> events = new CopyOnWriteArrayList<>();
    private final List<LogRow> logs = new CopyOnWriteArrayList<>();

    private final SessionEventCallback eventCallback = (sessionId, event, ts, data, exitCode,
                                                        message, extra) -> {
        events.add(new EventRow(sessionId, event, ts, data, exitCode, message));
        main.post(this::refreshOut);
    };

    private final SessionLogCallback logCallback = (sessionId, level, tag, message, ts) -> {
        logs.add(new LogRow(sessionId, level, tag, message));
        main.post(this::refreshOut);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        buildUi();
        setStatus("初始化…");
        try {
            SessionHandle.sessionSetLogCallback(logCallback);
            setStatus("日志订阅已注册（sessionSetLogCallback）");
        } catch (Throwable t) {
            setStatus("native load FAILED: " + t);
            return;
        }
        File shell = new File(SHELL);
        setStatus("shell 存在=" + shell.exists() + " 可读=" + shell.canRead()
                + "\n点 [spawn] 建会话（事件回调）；[自检] 跑验收断言");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#80D8FF"));
        status.setTextSize(12);
        status.setTypeface(Typeface.MONOSPACE);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextColor(Color.parseColor("#E0E0E0"));
        out.setBackgroundColor(Color.BLACK);
        out.setTextIsSelectable(true);
        out.setMovementMethod(new ScrollingMovementMethod());
        sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        input = new EditText(this);
        input.setHint("命令（发送到全部会话）");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTextColor(Color.parseColor("#FFFFFF"));
        input.setHintTextColor(Color.parseColor("#9E9E9E"));
        input.setBackgroundColor(Color.parseColor("#1E1E1E"));
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendInput();
                return true;
            }
            return false;
        });
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "spawn", v -> spawnOne());
        addButton(row, "echo", v -> sendToAll("echo __ECHO__\n"));
        addButton(row, "40x10", v -> resizeAll(40, 10));
        addButton(row, "exit3", v -> sendToAll("exit 3\n"));
        addButton(row, "自检", v -> runSelfTest());
        addButton(row, "clear", v -> {
            out.setText("");
            events.clear();
            logs.clear();
        });
        root.addView(row);

        setContentView(root);
        SystemBarInsets.applyAllSystemBarInsets(root);
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        row.addView(b, lp);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private String[] envSnapshot() {
        return new String[]{
                "PREFIX=" + PREFIX,
                "HOME=" + HOME,
                "PATH=" + PREFIX + "/bin:" + PREFIX + "/bin/applets:/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "TMPDIR=" + TMPDIR,
                "SHELL=" + SHELL,
                "LANG=en_US.UTF-8",
        };
    }

    private void spawnOne() {
        long h = SessionHandle.sessionCreate(SHELL, new String[]{"-bash"}, envSnapshot(),
                HOME, 80, 24, eventCallback);
        if (h == 0) {
            setStatus("spawn FAILED: " + SessionHandle.sessionLastError());
            return;
        }
        handles.add(h);
        setStatus("句柄 " + h + " 已创建（事件回调在线），共 " + handles.size() + " 会话");
    }

    private void sendInput() {
        String cmd = input.getText().toString();
        if (cmd.isEmpty()) return;
        sendToAll(cmd.endsWith("\n") ? cmd : cmd + "\n");
        input.setText("");
    }

    private void sendToAll(String cmd) {
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        int n = 0;
        for (long h : handles) {
            int w = SessionHandle.sessionWrite(h, bytes, bytes.length);
            if (w >= 0) n++;
        }
        setStatus("写入 " + n + "/" + handles.size() + " 会话: " + cmd.trim());
    }

    private void resizeAll(int cols, int rows) {
        for (long h : handles) {
            SessionHandle.sessionResize(h, cols, rows);
        }
        setStatus("resize -> " + cols + "x" + rows);
    }

    private void setStatus(String line) {
        if (status != null) status.setText("[session-handle] " + line);
    }

    private void refreshOut() {
        StringBuilder sb = new StringBuilder();
        sb.append("── 日志（诊断订阅） ──\n");
        for (LogRow l : logs) {
            sb.append('[').append(levelName(l.level)).append("] s").append(l.sessionId)
                    .append(' ').append(l.message).append('\n');
        }
        sb.append("── 事件回调 ──\n");
        for (EventRow e : events) {
            sb.append("s").append(e.sessionId).append(' ').append(e.event);
            if (e.event.equals("output_chunk") && e.data != null) {
                sb.append(" bytes=").append(e.data.length);
            }
            if (e.event.equals("exit_code")) sb.append(" code=").append(e.exitCode);
            if (e.message != null && !e.message.isEmpty()) {
                sb.append(" msg=").append(e.message);
            }
            sb.append('\n');
        }
        if (sb.length() > 30000) {
            sb.setLength(30000);
        }
        out.setText(sb.toString());
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private String levelName(int level) {
        return level == 0 ? "info" : level == 1 ? "warn" : "error";
    }

    /* ---------- 自检：真机内跑工单 25 验收断言（PASS/FAIL） ---------- */

    private void runSelfTest() {
        setStatus("自检启动…");
        Thread t = new Thread(this::selfTestBody, "session-handle-selftest");
        t.start();
    }

    private void selfTestBody() {
        StringBuilder r = new StringBuilder();
        boolean ok = true;
        ArrayList<Long> spawned = new ArrayList<>();
        try {
            // 1) 事件回调：创建 → session_created/command_started
            long h0 = SessionHandle.sessionCreate(SHELL, new String[]{"--login"}, envSnapshot(),
                    HOME, 80, 24, eventCallback);
            if (h0 == 0) {
                ok &= check(r, "sessionCreate", false, SessionHandle.sessionLastError());
            } else {
                spawned.add(h0);
                ok &= check(r, "事件回调 session_created", waitEvent(h0, "session_created", 5000),
                        "onEvent 收到 session_created");
                ok &= check(r, "事件回调 command_started", waitEvent(h0, "command_started", 5000),
                        "onEvent 收到 command_started");

                send(h0, "stty -echo\necho __HANDLE_READY__\n");
                ok &= check(r, "output_chunk 事件（__HANDLE_READY__）",
                        waitOutput(h0, "__HANDLE_READY__", 10000), "echo __HANDLE_READY__");

                // 2) sessionRead：只读侧缓冲与事件流同源
                byte[] buf = new byte[8192];
                int n = SessionHandle.sessionRead(h0, buf);
                String readText = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
                ok &= check(r, "sessionRead 读到缓冲（含 __HANDLE_READY__）",
                        n > 0 && readText.contains("__HANDLE_READY__"),
                        "read n=" + n);

                // 3) resize：40x10 行列正确
                SessionHandle.sessionResize(h0, 40, 10);
                send(h0, "stty size\necho __R40__\n");
                ok &= check(r, "resize 40x10 (10 40)",
                        waitOutput(h0, "__R40__", 5000)
                                && lastOutput(h0).contains("10 40"),
                        "stty size 输出 10 40");

                // 4) 退出码：exit 3 → command_finished + exit_code(3)
                send(h0, "exit 3\n");
                ok &= check(r, "command_finished 事件", waitEvent(h0, "command_finished", 10000),
                        "onEvent 收到 command_finished");
                ok &= check(r, "exit_code 事件 = 3", waitExitCode(h0, 3, 5000),
                        "exit 3 应带 exitCode=3");

                // 5) close → session_closed 事件
                SessionHandle.sessionClose(h0);
                ok &= check(r, "session_closed 事件", waitEvent(h0, "session_closed", 5000),
                        "close 后 onEvent 收到 session_closed");
                spawned.remove((Long) h0);

                // 6) 诊断日志订阅生效
                boolean logSeen = false;
                long logDeadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < logDeadline) {
                    for (LogRow l : logs) {
                        if (l.tag.equals("fable-session")) {
                            logSeen = true;
                            break;
                        }
                    }
                    if (logSeen) break;
                    Thread.sleep(100);
                }
                ok &= check(r, "诊断日志订阅生效", logSeen, "sessionSetLogCallback 收到 onLog");
            }

            // 7) 8 会话并发独立（2–4 硬指标内含）
            ok &= check(r, "8 会话并发独立性", parallel8(r), "8×UNIQ 标记互不串");
        } catch (Throwable t) {
            ok = false;
            r.append("FAIL 异常: ").append(t).append('\n');
        } finally {
            for (Long h : spawned) {
                SessionHandle.sessionClose(h);
            }
            final boolean result = ok;
            final String report = r.toString();
            main.post(() -> {
                setStatus("自检" + (result ? "全过 PASS" : "有失败 FAIL"));
                appendReport(report);
            });
        }
    }

    private boolean parallel8(StringBuilder r) throws InterruptedException {
        ArrayList<Long> extra = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                long h = SessionHandle.sessionCreate(SHELL, new String[]{"--login"},
                        envSnapshot(), HOME, 80, 24, eventCallback);
                if (h == 0) {
                    r.append("  第 ").append(i).append(" 个会话创建失败: ")
                            .append(SessionHandle.sessionLastError()).append('\n');
                    return false;
                }
                extra.add(h);
            }
            for (int i = 0; i < 8; i++) {
                send(extra.get(i), "stty -echo\necho UNIQ_" + i + "__R\n");
            }
            boolean allOk = true;
            for (int i = 0; i < 8; i++) {
                long h = extra.get(i);
                String marker = "UNIQ_" + i + "__R";
                if (!waitOutput(h, marker, 15000)) {
                    r.append("  会话 ").append(i).append(" 未收到自身标记\n");
                    allOk = false;
                    continue;
                }
                String text = lastOutput(h);
                for (int j = 0; j < 8; j++) {
                    if (i != j && text.contains("UNIQ_" + j + "__R")) {
                        r.append("  会话 ").append(i).append(" 混入 ").append(j).append(" 的输出\n");
                        allOk = false;
                    }
                }
            }
            return allOk;
        } finally {
            for (Long h : extra) {
                SessionHandle.sessionClose(h);
            }
        }
    }

    private void send(long h, String cmd) {
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        SessionHandle.sessionWrite(h, bytes, bytes.length);
    }

    private boolean waitEvent(long h, String event, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (EventRow e : events) {
                if (e.sessionId == h && e.event.equals(event)) return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean waitOutput(long h, String marker, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder acc = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            acc.append(lastOutput(h));
            if (acc.indexOf(marker) >= 0) return true;
            Thread.sleep(100);
        }
        return acc.indexOf(marker) >= 0;
    }

    private boolean waitExitCode(long h, int code, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (EventRow e : events) {
                if (e.sessionId == h && e.event.equals("exit_code") && e.exitCode == code) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    private String lastOutput(long h) {
        StringBuilder sb = new StringBuilder();
        for (EventRow e : events) {
            if (e.sessionId == h && e.event.equals("output_chunk") && e.data != null) {
                sb.append(new String(e.data, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private boolean check(StringBuilder r, String name, boolean pass, String detail) {
        r.append(pass ? "PASS " : "FAIL ").append(name);
        if (!pass) r.append("（期望/说明: ").append(detail).append("）");
        r.append('\n');
        return pass;
    }

    private void appendReport(String report) {
        String cur = out.getText().toString();
        out.setText(cur + "\n──── 自检报告 ────\n" + report);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Long h : handles) {
            SessionHandle.sessionClose(h);
        }
        handles.clear();
        SessionHandle.sessionSetLogCallback(null);
    }
}
