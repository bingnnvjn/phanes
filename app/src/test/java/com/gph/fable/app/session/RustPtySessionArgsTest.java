package com.gph.fable.app.session;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/**
 * 工单 26 修复：FableSessionSpec 的 args 是 argv0 约定（Java createSubprocess 同语义，
 * 登录 shell 为 "-bash"），而 libfable-session 的 args 是真实参数（探针 "--login" 同形）。
 * RustPtySession 在缝内做翻译，保证两实现平级。
 */
public class RustPtySessionArgsTest {

    @Test
    public void loginArgv0BecomesLoginFlag() {
        assertArrayEquals(new String[] { "--login" }, RustPtySession.translateArgs(new String[] { "-bash" }));
    }

    @Test
    public void loginArgv0WithExtraArgsKeepsExtraArgs() {
        assertArrayEquals(new String[] { "--login", "-c", "echo hi" },
            RustPtySession.translateArgs(new String[] { "-bash", "-c", "echo hi" }));
    }

    @Test
    public void nonLoginArgv0Dropped() {
        assertArrayEquals(new String[0], RustPtySession.translateArgs(new String[] { "sh" }));
    }

    @Test
    public void nonLoginArgv0WithExtraArgsKeepsExtraArgs() {
        assertArrayEquals(new String[] { "-c", "ls" },
            RustPtySession.translateArgs(new String[] { "sh", "-c", "ls" }));
    }

    @Test
    public void emptyArgsStayEmpty() {
        assertArrayEquals(new String[0], RustPtySession.translateArgs(new String[0]));
        assertArrayEquals(new String[0], RustPtySession.translateArgs(null));
    }
}
