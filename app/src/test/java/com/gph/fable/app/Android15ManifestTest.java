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

    /**
     * 工单 05：设置界面根背景用 colorBackground，edge-to-edge 下手势条区域与界面同色
     * （不出现"底部一整排遮罩"）。
     */
    @Test
    public void settings_root_uses_content_background() throws IOException {
        File layout = new File("src/main/res/layout/activity_settings.xml");
        Assert.assertTrue("Layout not found at " + layout.getAbsolutePath(), layout.isFile());
        String text = new String(Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8);

        Assert.assertTrue("activity_settings.xml root must use ?android:attr/colorBackground",
            text.contains("android:background=\"?android:attr/colorBackground\""));
    }

    /**
     * 工单 05：通知正文汉化——会话/任务复数资源在中英文资源文件中都存在。
     */
    @Test
    public void notification_plurals_exist_in_default_and_zh_resources() throws IOException {
        String values = readResourceFile("src/main/res/values/strings.xml");
        String zh = readResourceFile("src/main/res/values-zh-rCN/strings.xml");

        Assert.assertTrue("values/strings.xml missing notification_sessions_count",
            values.contains("name=\"notification_sessions_count\""));
        Assert.assertTrue("values/strings.xml missing notification_tasks_count",
            values.contains("name=\"notification_tasks_count\""));
        Assert.assertTrue("values-zh-rCN/strings.xml missing notification_sessions_count",
            zh.contains("name=\"notification_sessions_count\""));
        Assert.assertTrue("values-zh-rCN/strings.xml missing notification_tasks_count",
            zh.contains("name=\"notification_tasks_count\""));
    }

    private String readResourceFile(String path) throws IOException {
        File file = new File(path);
        Assert.assertTrue("Resource file not found at " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
