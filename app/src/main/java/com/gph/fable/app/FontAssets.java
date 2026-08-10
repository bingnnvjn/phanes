package com.gph.fable.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 工单 22：把 APK assets 内字体（noCompress）拷贝到 filesDir 并 JNI 传给
 * 渲染器（Rust 侧 mmap + sha256 校验；失败自动降级 Noto，绝不崩溃）。
 */
public final class FontAssets {
    private static final String TAG = "FableFontAssets";

    private static final String[] FONTS = {
            "AppleColorEmoji.ttf",
            "NotoColorEmoji.ttf",
    };

    private static final String APPLE_SHA256 =
            "6f6ad8b9751356c5707ab9e2645cddc3521d116a6b387d7b4b1437456d3784a3";
    private static final String NOTO_SHA256 =
            "0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2";

    private FontAssets() {
    }

    /** 幂等：文件存在且 sha256 匹配则跳过拷贝；无条件 JNI 传路径
     * （单字体失败时 Rust 侧 mmap 返回 None → 状态=降级，绝不崩溃）。 */
    public static synchronized void install(Context context, long rendererHandle) {
        if (context == null) {
            return;
        }
        File dir = new File(context.getFilesDir(), "fonts");
        for (String name : FONTS) {
            File out = new File(dir, name);
            String expected = expectedSha(name);
            if (out.exists() && expected != null && sha256(out).equals(expected)) {
                continue;
            }
            copyAsset(context, name, out);
        }
        if (rendererHandle != 0) {
            String apple = new File(dir, "AppleColorEmoji.ttf").getAbsolutePath();
            String noto = new File(dir, "NotoColorEmoji.ttf").getAbsolutePath();
            RenderCore.rendererSetFontPaths(rendererHandle, apple, noto);
        }
    }

    private static boolean copyAsset(Context context, String name, File out) {
        try (InputStream in = context.getAssets().open("fonts/" + name)) {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            String expected = expectedSha(name);
            return expected == null || sha256(out).equals(expected);
        } catch (IOException e) {
            Log.e(TAG, "copyAsset 失败: " + name, e);
            return false;
        }
    }

    private static String expectedSha(String name) {
        switch (name) {
            case "AppleColorEmoji.ttf":
                return APPLE_SHA256;
            case "NotoColorEmoji.ttf":
                return NOTO_SHA256;
            default:
                return null;
        }
    }

    private static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
