package com.gph.fable.app.session;

import com.gph.fable.terminal.session.FableSessionFactory;
import com.gph.fable.terminal.session.JavaFableSessionFactory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * 工单 26 切换开关映射（纯 JVM，绕开 Robolectric 4.8.1 + JDK 25 基线，2026-08-11 核实）：
 * 开关值 → 会话层工厂的映射 + 单例身份；SharedPreferences 持久化由
 * TermuxAppSharedPreferences 既有模式承接、真机验收覆盖。
 */
public class FableSessionSwitchTest {

    @Test
    public void rustEnabledMapsToRustFactory() {
        FableSessionFactory factory = FableSessionSwitch.factoryFor(true);
        assertEquals("rust", factory.getEngineName());
        assertSame(RustFableSessionFactory.INSTANCE, factory);
    }

    @Test
    public void rustDisabledMapsToJavaFactory() {
        FableSessionFactory factory = FableSessionSwitch.factoryFor(false);
        assertEquals("java", factory.getEngineName());
        assertSame(JavaFableSessionFactory.INSTANCE, factory);
    }
}
