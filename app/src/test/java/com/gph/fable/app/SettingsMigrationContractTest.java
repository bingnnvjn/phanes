package com.gph.fable.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 工单 38：设置页仍由 XML 反射入口和资源键组成；语言迁移不能改变这些公开契约。
 */
public class SettingsMigrationContractTest {

    private static final String[] KOTLIN_TARGETS = {
        "src/main/java/com/gph/fable/app/activities/HelpActivity.kt",
        "src/main/java/com/gph/fable/app/activities/SettingsActivity.kt",
        "src/main/java/com/gph/fable/app/fragments/settings/FablePreferenceFragment.kt",
        "src/main/java/com/gph/fable/app/fragments/settings/FablePreferencesFragment.kt",
        "src/main/java/com/gph/fable/app/fragments/settings/termux/DebuggingPreferencesFragment.kt",
        "src/main/java/com/gph/fable/app/fragments/settings/termux/TerminalIOPreferencesFragment.kt",
        "src/main/java/com/gph/fable/app/fragments/settings/termux/TerminalViewPreferencesFragment.kt",
        "src/main/java/com/gph/fable/app/models/UserAction.kt",
    };

    private static final String[] JAVA_TARGETS = {
        "src/main/java/com/gph/fable/app/activities/HelpActivity.java",
        "src/main/java/com/gph/fable/app/activities/SettingsActivity.java",
        "src/main/java/com/gph/fable/app/fragments/settings/FablePreferenceFragment.java",
        "src/main/java/com/gph/fable/app/fragments/settings/FablePreferencesFragment.java",
        "src/main/java/com/gph/fable/app/fragments/settings/termux/DebuggingPreferencesFragment.java",
        "src/main/java/com/gph/fable/app/fragments/settings/termux/TerminalIOPreferencesFragment.java",
        "src/main/java/com/gph/fable/app/fragments/settings/termux/TerminalViewPreferencesFragment.java",
        "src/main/java/com/gph/fable/app/models/UserAction.java",
    };

    @Test
    public void settings_group_is_entirely_kotlin() {
        for (String path : KOTLIN_TARGETS) {
            Assert.assertTrue("Missing Kotlin migration target: " + path, new File(path).isFile());
        }
        for (String path : JAVA_TARGETS) {
            Assert.assertFalse("Java migration source must be removed: " + path, new File(path).exists());
        }
    }

    @Test
    public void kotlin_migration_preserves_reflection_and_java_static_abi() throws Exception {
        Class<?> rootFragment = Class.forName(
            "com.gph.fable.app.activities.SettingsActivity$RootPreferencesFragment");
        Assert.assertEquals("Nested root fragment must stay directly inside SettingsActivity",
            "com.gph.fable.app.activities.SettingsActivity", rootFragment.getEnclosingClass().getName());
        Assert.assertTrue("Root fragment must remain a static nested class",
            Modifier.isStatic(rootFragment.getModifiers()));

        Class<?> debuggingFragment = Class.forName(
            "com.gph.fable.app.fragments.settings.termux.DebuggingPreferencesFragment");
        Method method = debuggingFragment.getDeclaredMethod(
            "setLogLevelListPreferenceData",
            Class.forName("androidx.preference.ListPreference"),
            Class.forName("android.content.Context"),
            int.class
        );
        Assert.assertTrue("Debugging helper must remain a Java static method",
            Modifier.isStatic(method.getModifiers()));
    }

    @Test
    public void preference_xml_keeps_reflection_targets_and_storage_keys() throws IOException {
        Assert.assertTrue(read("src/main/res/xml/root_preferences.xml").contains(
            "com.gph.fable.app.fragments.settings.FablePreferencesFragment"));

        String fable = read("src/main/res/xml/fable_preferences.xml");
        Assert.assertTrue(fable.contains("fable_theme_mode"));
        Assert.assertTrue(fable.contains(
            "com.gph.fable.app.fragments.settings.termux.DebuggingPreferencesFragment"));
        Assert.assertTrue(fable.contains(
            "com.gph.fable.app.fragments.settings.termux.TerminalIOPreferencesFragment"));
        Assert.assertTrue(fable.contains(
            "com.gph.fable.app.fragments.settings.termux.TerminalViewPreferencesFragment"));

        String debugging = read("src/main/res/xml/fable_debugging_preferences.xml");
        Assert.assertTrue(debugging.contains("log_level"));
        Assert.assertTrue(debugging.contains("terminal_view_key_logging_enabled"));
        Assert.assertTrue(debugging.contains("crash_report_notifications_enabled"));

        String terminalIo = read("src/main/res/xml/fable_terminal_io_preferences.xml");
        Assert.assertTrue(terminalIo.contains("soft_keyboard_enabled"));
        Assert.assertTrue(terminalIo.contains("soft_keyboard_enabled_only_if_no_hardware"));

        String terminalView = read("src/main/res/xml/fable_terminal_view_preferences.xml");
        Assert.assertTrue(terminalView.contains("fontsize"));
        Assert.assertTrue(terminalView.contains("terminal_margin_adjustment"));
    }

    @Test
    public void settings_text_resource_keys_exist_in_default_and_simplified_chinese() throws IOException {
        String values = read("src/main/res/values/strings.xml");
        String zh = read("src/main/res/values-zh-rCN/strings.xml");
        String[] keys = {
            "fable_preferences_title",
            "fable_theme_mode_title",
            "fable_debugging_preferences_title",
            "fable_terminal_io_preferences_title",
            "fable_terminal_view_preferences_title",
            "fable_log_level_title",
            "fable_soft_keyboard_enabled_title",
            "fable_terminal_view_font_size_title",
            "about_preference_title",
        };

        for (String key : keys) {
            Assert.assertTrue("Default strings missing " + key, values.contains("name=\"" + key + "\""));
            Assert.assertTrue("Simplified Chinese strings missing " + key, zh.contains("name=\"" + key + "\""));
        }
    }

    private static String read(String path) throws IOException {
        File file = new File(path);
        Assert.assertTrue("Missing file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
