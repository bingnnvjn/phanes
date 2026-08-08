package com.gph.fable.app.terminal;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件诊断日志（工单 04 选择空白）：
 * Termux 与 Fable 不同 uid，Android 8+ 下 Termux 的 logcat 看不到 Fable 日志，
 * 因此渲染器/视图关键事件直接追加写到 Download 目录，便于用户回传定位。
 */
public final class FableDiagnostics {

    private static volatile File sLogFile;

    private FableDiagnostics() {
    }

    /** 在 Application.onCreate 调用一次；优先 Download，失败退回应用私有目录。 */
    public static void init(@NonNull Context context) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            dir = context.getFilesDir();
        }
        sLogFile = new File(dir, "fable-render-debug.log");
        append("== Fable diagnostics start ==");
    }

    /** 追加一行（时间戳 + 消息）。 */
    public static void append(@NonNull String message) {
        File logFile = sLogFile;
        if (logFile == null) return;
        try (OutputStream out = new FileOutputStream(logFile, true)) {
            String line = System.currentTimeMillis() + " " + message + "\n";
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 诊断日志失败不阻塞主流程。
        }
    }
}
