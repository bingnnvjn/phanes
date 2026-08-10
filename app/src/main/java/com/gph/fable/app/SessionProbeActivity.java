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

import com.gph.fable.shared.view.SystemBarInsets;

/**
 * 工单 24 验证切片：Fable Session Probe（独立 launcher，不碰主终端）。
 *
 * 会话 = portable-pty（Rust，libfable-session.so）起的 bash --login；
 * 输出直接按 UTF-8 显示，不走 libghostty-vt（本切片只验会话层零件）。
 * [自检] 在真机内跑一遍与工单验收对应的 PASS/FAIL 断言（离屏自检 + 真机同框）。
 */
public class SessionProbeActivity extends Activity {
    private static final String SHELL = "/data/data/com.gph.fable/files/usr/bin/bash";
    private static final String PREFIX = "/data/data/com.gph.fable/files/usr";
    private static final String HOME = "/data/data/com.gph.fable/files/home";
    private static final String TMPDIR = "/data/data/com.gph.fable/files/usr/tmp";
    private static final int MAX_SESSIONS = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Session> sessions = new ArrayList<>();

    private TextView status;
    private TextView out;
    private ScrollView sv;
    private EditText input;

    private static class Session {
        long pty;
        Thread reader;
        volatile boolean alive = true;
        final StringBuilder text = new StringBuilder();
        final Object lock = new Object();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        buildUi();
        setStatus("初始化中…");
        try {
            setStatus("buildInfo: " + SessionProbe.buildInfo());
        } catch (Throwable t) {
            setStatus("native load FAILED: " + t);
            return;
        }
        File shell = new File(SHELL);
        setStatus("shell 存在=" + shell.exists() + " 可读=" + shell.canRead()
                + "（" + SHELL + "）\n点 [spawn] 建会话；[自检] 跑验收断言");
        main.postDelayed(this::spawnSession, 300);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#FFD54F"));
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
        input.setHint("输入命令（发送到所有会话），如 echo $PREFIX");
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
        input.requestFocus();
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "spawn", v -> spawnSession());
        addButton(row, "40x10", v -> resizeAll(40, 10));
        addButton(row, "80x24", v -> resizeAll(80, 24));
        addButton(row, "seq200", v -> sendToAll("seq 1 200\necho __DONE__\n"));
        addButton(row, "env", v -> sendToAll(envCheckCmd()));
        addButton(row, "自检", v -> runSelfTest());
        addButton(row, "send", v -> sendInput());
        addButton(row, "clear", v -> {
            out.setText("");
            for (Session s : sessions) {
                synchronized (s.lock) {
                    s.text.setLength(0);
                }
            }
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

    private String envCheckCmd() {
        return "printf 'PREFIX=%s\\nHOME=%s\\nTERM=%s\\nTMPDIR=%s\\nPATH=%s\\n' \"$PREFIX\" \"$HOME\" \"$TERM\" \"$TMPDIR\" \"$PATH\"\n";
    }

    private void spawnSession() {
        Session s = spawnForTest();
        if (s == null) {
            setStatus("spawn FAILED: " + SessionProbe.lastError());
            return;
        }
        setStatus("会话 " + (sessions.size() - 1) + " 已建立，等待输出…");
    }

    private void readLoop(Session s) {
        byte[] buf = new byte[4096];
        while (s.alive) {
            int n = SessionProbe.ptyRead(s.pty, buf);
            if (n <= 0) break;
            String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
            synchronized (s.lock) {
                s.text.append(chunk);
                s.lock.notifyAll();
            }
            main.post(this::refreshOut);
        }
        s.alive = false;
        main.post(() -> {
            int idx = sessions.indexOf(s);
            setStatus("会话 " + idx + " 已退出");
            refreshOut();
        });
    }

    private void refreshOut() {
        StringBuilder sb = new StringBuilder();
        synchronized (sessions) {
            for (int i = 0; i < sessions.size(); i++) {
                Session s = sessions.get(i);
                if (sb.length() > 0) sb.append('\n');
                sb.append("── 会话 ").append(i).append(" ──\n");
                String t;
                synchronized (s.lock) {
                    t = s.text.toString();
                }
                if (t.length() > 20000) t = t.substring(t.length() - 20000);
                sb.append(t);
                if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            }
        }
        out.setText(sb.toString());
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private void sendToAll(String cmd) {
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        for (Session s : sessions) {
            SessionProbe.ptyWrite(s.pty, bytes, bytes.length);
        }
        setStatus("已发送: " + cmd.trim());
    }

    private void sendInput() {
        String cmd = input.getText().toString();
        if (cmd.isEmpty()) return;
        sendToAll(cmd.endsWith("\n") ? cmd : cmd + "\n");
        input.setText("");
    }

    private void resizeAll(int cols, int rows) {
        for (Session s : sessions) {
            SessionProbe.ptyResize(s.pty, cols, rows);
        }
        setStatus("resize -> " + cols + "x" + rows);
    }

    private void setStatus(String line) {
        if (status != null) status.setText("[session-probe] " + line);
    }

    /* ---------- 自检：真机内跑工单验收断言（PASS/FAIL） ---------- */

    private void runSelfTest() {
        setStatus("自检启动…");
        Thread t = new Thread(this::selfTestBody, "session-probe-selftest");
        t.start();
    }

    private void selfTestBody() {
        StringBuilder r = new StringBuilder();
        boolean ok = true;
        ArrayList<Session> spawned = new ArrayList<>();
        try {
            ok &= check(r, "shell 存在", new File(SHELL).exists()
                    && new File(SHELL).canRead(), SHELL);

            Session s0 = spawnForTest();
            if (s0 == null) {
                ok &= check(r, "spawn bash（ptySpawn）", false,
                        "lastError: " + SessionProbe.lastError());
            } else {
                spawned.add(s0);
                send(s0, "stty -echo\n");
                Thread.sleep(400);
                send(s0, "echo __READY__\n");
                ok &= check(r, "spawn bash + 读写交互（__READY__）",
                        waitText(s0, "__READY__", 10000), "echo __READY__");

                send(s0, envCheckCmd());
                ok &= check(r, "env PREFIX", waitText(s0, "PREFIX=" + PREFIX, 5000), PREFIX);
                ok &= check(r, "env HOME", waitText(s0, "HOME=" + HOME, 5000), HOME);
                ok &= check(r, "env TERM", waitText(s0, "TERM=xterm-256color", 5000), "xterm-256color");
                ok &= check(r, "env TMPDIR", waitText(s0, "TMPDIR=" + TMPDIR, 5000), TMPDIR);
                ok &= check(r, "env PATH 含 prefix/bin", waitText(s0, "PATH=" + PREFIX + "/bin", 5000),
                        PREFIX + "/bin");
                send(s0, "printf 'PWD=%s\\n' \"$(pwd)\"\n");
                ok &= check(r, "pwd = files/home", waitText(s0, "PWD=" + HOME, 5000), HOME);

                SessionProbe.ptyResize(s0.pty, 40, 10);
                send(s0, "stty size\n");
                ok &= check(r, "resize 40x10 (10 40)", waitText(s0, "10 40", 5000), "10 40");
                SessionProbe.ptyResize(s0.pty, 80, 24);
                send(s0, "stty size\n");
                ok &= check(r, "resize 80x24 (24 80)", waitText(s0, "24 80", 5000), "24 80");

                ok &= check(r, "4 会话并行", parallelCheck(spawned, r), "4×UNIQ 标记互不串");

                long start = System.currentTimeMillis();
                send(s0, "seq 1 200\necho __DONE__\n");
                boolean done = waitText(s0, "__DONE__", 15000);
                long ms = System.currentTimeMillis() - start;
                String text = snapshot(s0).replace("\r\n", "\n").replace("\r", "");
                boolean full = done && text.contains("\n200\n");
                ok &= check(r, "seq 200 不卡死（" + ms + "ms）", full, "DONE 前含 1..200");
            }
        } catch (Throwable t) {
            ok = false;
            r.append("FAIL 异常: ").append(t).append('\n');
        } finally {
            for (Session s : spawned) closeSession(s);
            final boolean result = ok;
            final String report = r.toString();
            main.post(() -> {
                setStatus("自检" + (result ? "全过 PASS" : "有失败 FAIL"));
                appendReport(report);
            });
        }
    }

    private Session spawnForTest() {
        long pty = SessionProbe.ptySpawn(SHELL, envSnapshot(), HOME, 80, 24);
        if (pty == 0) return null;
        Session s = new Session();
        s.pty = pty;
        synchronized (sessions) {
            sessions.add(s);
        }
        s.reader = new Thread(() -> readLoop(s), "selftest-reader");
        s.reader.start();
        return s;
    }

    private boolean parallelCheck(ArrayList<Session> spawned, StringBuilder r) {
        ArrayList<Session> extra = new ArrayList<>();
        try {
            for (int i = 1; i < 4; i++) {
                Session s = spawnForTest();
                if (s == null) return false;
                extra.add(s);
            }
            // 全部 4 个会话各自输出唯一标记
            for (int i = 0; i < 4; i++) {
                Session s = i == 0 ? spawned.get(0) : extra.get(i - 1);
                send(s, "stty -echo\necho UNIQ_" + i + "\n");
            }
            boolean allOk = true;
            for (int i = 0; i < 4; i++) {
                Session s = i == 0 ? spawned.get(0) : extra.get(i - 1);
                if (!waitText(s, "UNIQ_" + i, 10000)) {
                    allOk = false;
                    continue;
                }
                String text = snapshot(s);
                for (int j = 0; j < 4; j++) {
                    if (i != j && text.contains("UNIQ_" + j)) {
                        allOk = false;
                        r.append("  会话 ").append(i).append(" 混入 ").append(j).append(" 的输出\n");
                    }
                }
            }
            return allOk;
        } finally {
            for (Session s : extra) closeSession(s);
        }
    }

    private void send(Session s, String cmd) {
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        SessionProbe.ptyWrite(s.pty, bytes, bytes.length);
    }

    private boolean waitText(Session s, String marker, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (s.lock) {
            while (System.currentTimeMillis() < deadline) {
                if (s.text.indexOf(marker) >= 0) return true;
                try {
                    s.lock.wait(200);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return s.text.indexOf(marker) >= 0;
        }
    }

    private String snapshot(Session s) {
        synchronized (s.lock) {
            return s.text.toString();
        }
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

    private void closeSession(Session s) {
        s.alive = false;
        SessionProbe.ptyClose(s.pty);
        synchronized (sessions) {
            sessions.remove(s);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Session s : new ArrayList<>(sessions)) closeSession(s);
    }
}
