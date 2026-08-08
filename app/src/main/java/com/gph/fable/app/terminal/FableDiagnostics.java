package com.gph.fable.app.terminal;

import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

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

    private static final String LOG_NAME = "fable-render-debug.log";

    private static volatile Context sContext;

    private FableDiagnostics() {
    }

    /** 在 Application.onCreate 调用一次。 */
    public static void init(@NonNull Context context) {
        sContext = context.getApplicationContext();
        append("== Fable diagnostics start ==");
    }

    /** 追加一行（时间戳 + 消息）。 */
    public static void append(@NonNull String message) {
        Context context = sContext;
        if (context == null) return;
        byte[] bytes = (System.currentTimeMillis() + " " + message + "\n")
            .getBytes(StandardCharsets.UTF_8);

        // 1) 有存储权限：直接写 Download 文件（Termux 可直接读）。
        if (appendToDownloadFile(context, bytes)) return;
        // 2) Android 10+：MediaStore Downloads（scoped storage，无需权限）。
        if (Build.VERSION.SDK_INT >= 29 && appendViaMediaStore(context, bytes)) return;
        // 3) 兜底：应用私有目录（Termux 可能读不到，尽力而为）。
        appendToPrivateFile(context, bytes);
    }

    private static boolean appendToDownloadFile(@NonNull Context context, @NonNull byte[] bytes) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
            try (OutputStream out = new FileOutputStream(new File(dir, LOG_NAME), true)) {
                out.write(bytes);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean appendViaMediaStore(@NonNull Context context, @NonNull byte[] bytes) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = findDownloadUri(resolver, LOG_NAME);
            boolean created = uri == null;
            if (created) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, LOG_NAME);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
            }
            try (OutputStream out = resolver.openOutputStream(uri, "wa")) {
                if (out == null) return false;
                out.write(bytes);
            }
            if (created) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Uri findDownloadUri(ContentResolver resolver, String name) {
        try (Cursor cursor = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            new String[] { MediaStore.Downloads._ID },
            MediaStore.Downloads.DISPLAY_NAME + "=?",
            new String[] { name },
            null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            }
        } catch (Exception e) {
            // 查询失败走下一级。
        }
        return null;
    }

    private static void appendToPrivateFile(@NonNull Context context, @NonNull byte[] bytes) {
        try (OutputStream out = new FileOutputStream(
            new File(context.getFilesDir(), LOG_NAME), true)) {
            out.write(bytes);
        } catch (IOException e) {
            // 诊断日志失败不阻塞主流程。
        }
    }
}
