package com.gph.fable.core.session;

import androidx.annotation.Nullable;

/**
 * 会话层工厂接口（工单 26 缝）：唯一实现 = RustFableSessionFactory
 * （Java 会话层已随工单 27 下线删除），由 FableService 注入。
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
