package com.gph.fable.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import com.gph.fable.shared.view.SystemBarInsets;

/**
 * 工单 10 验证切片：SurfaceView + wgpu(Vulkan) 真机上屏 harness。
 *
 * 每个会话 = PTY(bash --login) + libghostty-vt 核心 + Rust 渲染器 + 独立 SurfaceView。
 * 驱动：Kotlin 侧读到 PTY 新字节 → 触发渲染；Rust 侧用内容签名去重。
 * 不碰 Termux 主终端/会话层（红线）。
 */
public class RenderActivity extends Activity {
    private static final String SHELL = "/data/data/com.gph.fable/files/usr/bin/bash";
    private static final int MAX_SESSIONS = 4;
    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Session> sessions = new ArrayList<>();

    private TextView status;
    private LinearLayout surfaceArea;
    private EditText input;
    private File debugFile;

    private static class Session {
        long pty;
        long renderer;
        SurfaceView view;
        Thread reader;
        volatile boolean alive = true;
        int cols = DEFAULT_COLS;
        int rows = DEFAULT_ROWS;
        int width;
        int height;
        boolean renderShown;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        buildUi();
        setStatus("初始化中…");
        debugFile = new File(getExternalFilesDir(null), "fable-render-debug.log");
        debugLog("onCreate");
        try {
            File shell = new File(SHELL);
            debugLog("shell exists=" + shell.exists());
            setStatus("shell 存在=" + shell.exists() + " 可读=" + shell.canRead()
                    + "\n点 [spawn] 建会话；输出直接经 wgpu 上屏");
        } catch (Throwable t) {
            debugLog("onCreate FAILED: " + t);
            setStatus("native load FAILED: " + t);
        }
    }

    private void debugLog(String line) {
        try {
            if (debugFile == null) {
                return;
            }
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            try (FileWriter w = new FileWriter(debugFile, true)) {
                w.write(ts + " " + line + "\n");
            }
        } catch (IOException ignored) {
        }
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
        // 工单 13 真机反馈：整段 rendererInfo 塞进状态区会换行成十几行、
        // 把终端挤出屏幕。限高 2 行省略；完整信息走 [info] / debug log。
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setBackgroundColor(Color.parseColor("#1E1E1E"));
        status.setOnClickListener(v -> {
            if (status.getMaxLines() == 2) {
                status.setMaxLines(Integer.MAX_VALUE);
                status.setEllipsize(null);
            } else {
                status.setMaxLines(2);
                status.setEllipsize(TextUtils.TruncateAt.END);
            }
        });
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        surfaceArea = new LinearLayout(this);
        surfaceArea.setOrientation(LinearLayout.VERTICAL);
        surfaceArea.setBackgroundColor(Color.BLACK);
        root.addView(surfaceArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        input = new EditText(this);
        input.setHint("输入命令（发送到所有会话），如 echo $PREFIX");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTextColor(Color.WHITE);
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
        addButton(row, "select", v -> selectDemo());
        addButton(row, "send", v -> sendInput());
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row2, "redraw", v -> redrawAll());
        addButton(row2, "info", v -> showInfo());
        addButton(row2, "view", v -> canvasRedAll());
        addButton(row2, "gpu", v -> gpuTestAll());
        addButton(row2, "top", v -> scrollAll(-100000));
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        // 工单 13：COLRv1 验收序列（彩色/绿勾/ZWJ 家庭/肤色/旗帜/杂项/冷门码位）。
        addButton(row3, "emoji13", v -> sendToAll(
            "echo 🚀✅👨‍👩‍👧‍👦👍🏻🇨🇳⌨️🔋🧑‍🚀🫖🫶\n"));
        // 工单 13 扩展测试集：黄脸/动物/食物/活动/物体/符号/ZWJ 27 个。
        addButton(row3, "emoji27", v -> sendToAll(
            "echo 😀😢😂😍😡🥺🐶🐱🐼🦊🍎🍕🍜⚽🎮🎵📱💻☕❤️⭐⚠️🎄🎂💯👋🏻🏳️‍🌈\n"));
        addButton(row3, "emoji", v -> sendToAll("echo 🚀✅\n"));
        // 工单 13：按钮命令必须带末尾真换行（\n），否则 bash 一直等回车不执行。
        addButton(row3, "box", v -> sendToAll("printf '┌───┐\\n│ x │\\n└───┘\\n'\n"));
        addButton(row3, "u-line", v -> sendToAll("printf '\\033[4munderline\\033[0m\\n'\n"));
        root.addView(row3);

        setContentView(root);
        // 工单 05：edge-to-edge——渲染 harness 也避让状态栏/手势条。
        SystemBarInsets.applyAllSystemBarInsets(root);
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        row.addView(b, lp);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void spawnSession() {
        debugLog("spawnSession begin");
        if (sessions.size() >= MAX_SESSIONS) {
            setStatus("已达上限 " + MAX_SESSIONS + " 个会话");
            return;
        }
        long renderer = 0;
        try {
            debugLog("before rendererCreate");
            renderer = RenderCore.rendererCreate(DEFAULT_COLS, DEFAULT_ROWS);
            debugLog("after rendererCreate handle=" + renderer);
            if (renderer == 0) {
                setStatus("rendererCreate FAILED（看调试日志）");
                return;
            }
        } catch (Throwable t) {
            debugLog("rendererCreate THREW: " + t);
            setStatus("rendererCreate THREW: " + t);
            return;
        }
        long pty = 0;
        try {
            debugLog("before ptySpawn");
            pty = RenderCore.ptySpawn(SHELL, DEFAULT_COLS, DEFAULT_ROWS);
            debugLog("after ptySpawn handle=" + pty);
            if (pty == 0) {
                setStatus("ptySpawn FAILED（bootstrap 是否已安装？）");
                RenderCore.rendererDestroy(renderer);
                return;
            }
        } catch (Throwable t) {
            debugLog("ptySpawn THREW: " + t);
            setStatus("ptySpawn THREW: " + t);
            RenderCore.rendererDestroy(renderer);
            return;
        }

        Session s = new Session();
        s.renderer = renderer;
        s.pty = pty;
        debugLog("before addView");
        s.view = new SurfaceView(this);
        s.view.setBackgroundColor(Color.BLACK);
        s.view.setZOrderOnTop(true);
        s.view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (width <= 0 || height <= 0 || !holder.getSurface().isValid()) {
                    debugLog("surfaceChanged invalid " + width + "x" + height);
                    return;
                }
                s.width = width;
                s.height = height;
                s.cols = Math.max(20, width / 12);
                s.rows = Math.max(10, height / 24);
                debugLog("surfaceChanged " + width + "x" + height + " cells=" + s.cols + "x" + s.rows);
                RenderCore.ptyResize(s.pty, s.cols, s.rows);
                RenderCore.rendererResize(s.renderer, s.cols, s.rows);
                debugLog("before attach");
                RenderCore.rendererAttach(s.renderer, holder.getSurface(), width, height);
                debugLog("after attach");
                debugLog("surface attached info: "
                        + RenderCore.rendererInfo(s.renderer));
                setStatus("surface attached " + width + "x" + height);
                renderFrame(s);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                debugLog("surfaceDestroyed");
                RenderCore.rendererDetach(s.renderer);
            }
        });
        surfaceArea.addView(s.view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        debugLog("after addView");

        sessions.add(s);
        debugLog("before reader start");
        s.reader = new Thread(() -> readLoop(s), "render-reader-" + sessions.size());
        s.reader.start();
        debugLog("reader started");
        debugLog("spawn info: " + RenderCore.rendererInfo(s.renderer));
        setStatus("会话 " + (sessions.size() - 1) + " 已建立（点 [info] 看渲染详情）");
        debugLog("spawnSession end");
    }

    private void readLoop(Session s) {
        debugLog("readLoop begin");
        byte[] buf = new byte[4096];
        while (s.alive) {
            int n = RenderCore.ptyRead(s.pty, buf);
            if (n > 0) {
                debugLog("ptyRead n=" + n);
            }
            if (n <= 0) {
                break;
            }
            final byte[] chunk = new byte[n];
            System.arraycopy(buf, 0, chunk, 0, n);
            main.post(() -> {
                if (!s.alive || !sessions.contains(s)) {
                    return;
                }
                RenderCore.rendererWrite(s.renderer, chunk, chunk.length);
                renderFrame(s);
            });
        }
        s.alive = false;
        int idx = sessions.indexOf(s);
        main.post(() -> setStatus("会话 " + idx + " 已退出"));
    }

    private void renderFrame(Session s) {
        if (!s.alive || s.width <= 0 || s.height <= 0) {
            return;
        }
        debugLog("before render " + s.width + "x" + s.height);
        boolean ok = RenderCore.rendererRender(s.renderer, s.width, s.height);
        debugLog("after render");
        if (ok && !s.renderShown) {
            s.renderShown = true;
            debugLog("render OK info: " + RenderCore.rendererInfo(s.renderer));
            setStatus("render OK");
        }
    }

    private void redrawAll() {
        for (Session s : sessions) {
            s.renderShown = false;
            renderFrame(s);
        }
        if (!sessions.isEmpty()) {
            setStatus("redraw: " + RenderCore.rendererInfo(sessions.get(0).renderer));
        }
    }

    private void showInfo() {
        if (sessions.isEmpty()) {
            setStatus("no session");
            return;
        }
        setStatus(RenderCore.rendererInfo(sessions.get(0).renderer));
    }

    private void canvasRedAll() {
        for (Session s : sessions) {
            android.graphics.Canvas canvas = s.view.getHolder().lockCanvas();
            if (canvas == null) {
                setStatus("view test: lockCanvas null");
                return;
            }
            canvas.drawColor(Color.RED);
            s.view.getHolder().unlockCanvasAndPost(canvas);
        }
        setStatus("view test: java red drawn");
    }

    private void gpuTestAll() {
        for (Session s : sessions) {
            if (s.width <= 0 || s.height <= 0) {
                continue;
            }
            boolean ok = RenderCore.rendererTestPattern(s.renderer, s.width, s.height);
            setStatus("test pattern ok=" + ok + " " + RenderCore.rendererInfo(s.renderer));
        }
    }

    private void sendToAll(String cmd) {
        byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
        for (Session s : sessions) {
            RenderCore.ptyWrite(s.pty, bytes, bytes.length);
        }
        setStatus("已发送: " + cmd.trim());
    }

    private void sendInput() {
        String cmd = input.getText().toString();
        if (cmd.isEmpty()) {
            return;
        }
        sendToAll(cmd.endsWith("\n") ? cmd : cmd + "\n");
        input.setText("");
    }

    private void resizeAll(int cols, int rows) {
        for (Session s : sessions) {
            s.cols = cols;
            s.rows = rows;
            RenderCore.ptyResize(s.pty, cols, rows);
            RenderCore.rendererResize(s.renderer, cols, rows);
            renderFrame(s);
        }
        setStatus("resize -> " + cols + "x" + rows);
    }

    private void scrollAll(int delta) {
        for (Session s : sessions) {
            RenderCore.rendererScroll(s.renderer, delta);
            renderFrame(s);
        }
        setStatus("scroll delta " + delta);
    }

    private void selectDemo() {
        for (Session s : sessions) {
            RenderCore.rendererSetSelection(s.renderer, 1, 0, Math.min(12, s.cols));
            renderFrame(s);
        }
        setStatus("selection overlay row=1 col=0..12");
    }

    private void setStatus(String line) {
        if (status != null) {
            status.setText("[render] " + line);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        debugLog("onDestroy begin");
        for (Session s : sessions) {
            s.alive = false;
            RenderCore.rendererDetach(s.renderer);
            RenderCore.ptyClose(s.pty);
            RenderCore.rendererDestroy(s.renderer);
        }
        sessions.clear();
        debugLog("onDestroy end");
    }
}
