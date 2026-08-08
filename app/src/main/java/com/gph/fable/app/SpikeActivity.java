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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.ArrayList;

import com.gph.fable.shared.view.SystemBarInsets;

/**
 * 工单 08 验证切片：真机 harness（探针专用，非产品代码）。
 *
 * 每个会话 = 一个 PTY（$PREFIX/bin/bash --login）+ 一个 libghostty-vt 终端。
 * 输出：PTY 字节 → 核心 → PLAIN 文本 dump 到屏幕。
 * 验收点：echo/pwd/ls、$PREFIX、resize、滚动回看、多会话并行、性能粗测。
 */
public class SpikeActivity extends Activity {
    private static final String SHELL = "/data/data/com.gph.fable/files/usr/bin/bash";
    private static final int MAX_SESSIONS = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Session> sessions = new ArrayList<>();

    private TextView status;
    private TextView out;
    private ScrollView sv;
    private EditText input;

    private static class Session {
        long pty;
        long term;
        Thread reader;
        volatile boolean alive = true;
        final StringBuilder text = new StringBuilder();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        buildUi();
        setStatus("初始化中…");
        try {
            setStatus("buildInfo: " + GhosttySpike.buildInfo());
        } catch (Throwable t) {
            setStatus("native load FAILED: " + t);
            return;
        }
        File shell = new File(SHELL);
        setStatus("shell 存在=" + shell.exists() + " 可读=" + shell.canRead()
                + "（" + SHELL + "）\n点 [spawn] 建会话；输入框发命令到所有会话");
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
        addButton(row, "scroll-10", v -> scrollAll(-10));
        addButton(row, "seq200", v -> sendToAll("seq 1 200\n"));
        addButton(row, "send", v -> sendInput());
        addButton(row, "clear", v -> {
            out.setText("");
            for (Session s : sessions) s.text.setLength(0);
        });
        root.addView(row);

        setContentView(root);
        // 工单 05：edge-to-edge——探针 harness 也避让状态栏/手势条。
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

    private void spawnSession() {
        if (sessions.size() >= MAX_SESSIONS) {
            setStatus("已达上限 " + MAX_SESSIONS + " 个会话");
            return;
        }
        long pty = GhosttySpike.ptySpawn(SHELL, 80, 24);
        if (pty == 0) {
            setStatus("spawn FAILED: " + GhosttySpike.lastError());
            return;
        }
        long term = GhosttySpike.terminalCreate(80, 24, 10000);
        if (term == 0) {
            setStatus("terminalCreate FAILED: " + GhosttySpike.lastError());
            GhosttySpike.ptyClose(pty);
            return;
        }
        Session s = new Session();
        s.pty = pty;
        s.term = term;
        sessions.add(s);
        s.reader = new Thread(() -> readLoop(s), "spike-reader-" + sessions.size());
        s.reader.start();
        setStatus("会话 " + (sessions.size() - 1) + " 已建立，等待输出…");
    }

    private void readLoop(Session s) {
        byte[] buf = new byte[4096];
        while (s.alive) {
            int n = GhosttySpike.ptyRead(s.pty, buf);
            if (n <= 0) break;
            GhosttySpike.terminalWrite(s.term, buf, n);
            /* 每次读到数据都立即 dump：避免快速刷出的输出卡在节流窗口里不显示 */
            dump(s);
        }
        s.alive = false;
        dump(s);
        int idx = sessions.indexOf(s);
        main.post(() -> setStatus("会话 " + idx + " 已退出"));
    }

    private void dump(Session s) {
        byte[] txt = GhosttySpike.terminalFormatPlain(s.term);
        if (txt == null) return;
        String str = new String(txt, StandardCharsets.UTF_8);
        main.post(() -> {
            s.text.setLength(0);
            s.text.append(str);
            refreshOut();
        });
    }

    private void refreshOut() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sessions.size(); i++) {
            Session s = sessions.get(i);
            if (sb.length() > 0) sb.append('\n');
            sb.append("── 会话 ").append(i).append(" ──\n");
            String t = s.text.toString();
            if (t.length() > 20000) t = t.substring(t.length() - 20000);
            sb.append(t);
            if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        }
        out.setText(sb.toString());
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private void sendToAll(String cmd) {
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        for (Session s : sessions) {
            GhosttySpike.ptyWrite(s.pty, bytes, bytes.length);
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
            GhosttySpike.ptyResize(s.pty, cols, rows);
            GhosttySpike.terminalResize(s.term, cols, rows);
        }
        setStatus("resize -> " + cols + "x" + rows);
    }

    private void scrollAll(int delta) {
        for (Session s : sessions) GhosttySpike.terminalScroll(s.term, delta);
        for (Session s : sessions) dump(s);
        setStatus("scroll delta " + delta);
    }

    private void setStatus(String line) {
        if (status != null) {
            status.setText("[spike] " + line);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Session s : sessions) {
            s.alive = false;
            GhosttySpike.ptyClose(s.pty);
            GhosttySpike.terminalFree(s.term);
        }
        sessions.clear();
    }
}
