package com.gph.fable.terminal.session;

import androidx.annotation.Nullable;

/**
 * 会话层工厂接口（工单 26 缝）：Java/Rust 两实现平级，切换开关决定
 * 当前会话用哪个工厂（构建期默认 Rust，设置页 debug 项可切回 Java）。
 */
public interface FableSessionFactory {

    /**
     * 创建会话后端（同步完成 spawn/建句柄）；失败返回 null（错误已记日志，
     * 调用方 {@code TerminalSession.initializeEmulator} 统一处理）。
     */
    @Nullable
    FableSession create(FableSessionSpec spec, FableSessionCallbacks callbacks);

    /** 会话层引擎名："java" / "rust"（诊断/切换用）。 */
    String getEngineName();
}
