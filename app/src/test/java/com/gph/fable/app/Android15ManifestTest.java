package com.gph.fable.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 工单 05：Android 15 适配的 Manifest 声明锁（程序化验收）。
 *
 * targetSdk 35 要求：POST_NOTIFICATIONS 权限、前台服务类型声明
 * （specialUse）+ 对应的 FOREGROUND_SERVICE_SPECIAL_USE 权限 +
 * PROPERTY_SPECIAL_USE_FGS_SUBTYPE 属性。这些声明缺一不可，
 * 缺失会在 Android 14+ 真机上抛 SecurityException / MissingForegroundServiceTypeException。
 */
public class Android15ManifestTest {

    private static final String MANIFEST_PATH = "src/main/AndroidManifest.xml";

    private String manifestText() throws IOException {
        File manifest = new File(MANIFEST_PATH);
        Assert.assertTrue("Manifest not found at " + manifest.getAbsolutePath(), manifest.isFile());
        return new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void manifest_declares_post_notifications_permission() throws IOException {
        Assert.assertTrue("Missing android.permission.POST_NOTIFICATIONS",
            manifestText().contains("android.permission.POST_NOTIFICATIONS"));
    }

    @Test
    public void manifest_declares_foreground_service_special_use_permission() throws IOException {
        Assert.assertTrue("Missing android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            manifestText().contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"));
    }

    @Test
    public void termux_service_declares_special_use_type_and_subtype() throws IOException {
        String manifest = manifestText();
        int serviceStart = manifest.indexOf("android:name=\".app.TermuxService\"");
        int serviceEnd = manifest.indexOf("</service>", serviceStart);
        Assert.assertTrue("TermuxService declaration not found", serviceStart >= 0);
        String service = manifest.substring(serviceStart, serviceEnd);

        Assert.assertTrue("TermuxService missing foregroundServiceType=\"specialUse\"",
            service.contains("android:foregroundServiceType=\"specialUse\""));
        Assert.assertTrue("TermuxService missing PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            service.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"));
    }

    @Test
    public void run_command_service_declares_special_use_type_and_subtype() throws IOException {
        String manifest = manifestText();
        int serviceStart = manifest.indexOf("android:name=\".app.RunCommandService\"");
        int serviceEnd = manifest.indexOf("</service>", serviceStart);
        Assert.assertTrue("RunCommandService declaration not found", serviceStart >= 0);
        String service = manifest.substring(serviceStart, serviceEnd);

        Assert.assertTrue("RunCommandService missing foregroundServiceType=\"specialUse\"",
            service.contains("android:foregroundServiceType=\"specialUse\""));
        Assert.assertTrue("RunCommandService missing PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            service.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"));
    }

    /**
     * 工单 05：targetSdk 35 强制 edge-to-edge。根布局保持
     * {@code android:fitsSystemWindows="true"}，系统栏 insets 会作为 padding 应用，
     * 终端不被状态栏/导航栏遮挡（渲染器不动，适配落在界面层）。
     */
    @Test
    public void activity_root_layout_keeps_fits_system_windows() throws IOException {
        File layout = new File("src/main/res/layout/activity_termux.xml");
        Assert.assertTrue("Layout not found at " + layout.getAbsolutePath(), layout.isFile());
        String text = new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8);

        Assert.assertTrue("activity_termux.xml root must keep android:fitsSystemWindows=\"true\"",
            text.contains("android:fitsSystemWindows=\"true\""));
    }
}
