package com.gph.fable.terminal.session;

/**
 * 会话层抽象缝的 Java 工厂（工单 26）：Java 会话层（现状）平级实现。
 * TerminalSession 未显式指定工厂时的默认回退。
 */
public final class JavaFableSessionFactory implements FableSessionFactory {

    public static final JavaFableSessionFactory INSTANCE = new JavaFableSessionFactory();

    private JavaFableSessionFactory() {
    }

    @Override
    public FableSession create(FableSessionSpec spec, FableSessionCallbacks callbacks) {
        if (spec == null || callbacks == null) return null;
        JavaFableSession session = new JavaFableSession(spec, callbacks);
        return session.start() ? session : null;
    }

    @Override
    public String getEngineName() {
        return "java";
    }
}
