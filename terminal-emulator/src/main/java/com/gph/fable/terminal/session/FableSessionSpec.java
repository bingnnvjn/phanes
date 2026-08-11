package com.gph.fable.terminal.session;

/**
 * 会话层创建参数（工单 26 缝）：Java/Rust 两实现的统一输入。
 *
 * cellWidthPixels/cellHeightPixels 仅 Java PTY 的 TIOCSWINSZ 初始像素尺寸使用；
 * Rust 实现（libfable-session sessionCreate 只收 cols/rows）忽略这两个字段。
 *
 * args[0] 是 argv0 名（登录 shell 为 "-bash"，与 Java createSubprocess / execvp 同语义）；
 * args[1..] 为真实参数。Java/Rust 两实现均按此约定消费。
 */
public final class FableSessionSpec {

    private final String mShell;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final int mColumns;
    private final int mRows;
    private final int mCellWidthPixels;
    private final int mCellHeightPixels;

    public FableSessionSpec(String shell, String cwd, String[] args, String[] env,
                            int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mShell = shell;
        mCwd = cwd;
        mArgs = args == null ? new String[0] : args;
        mEnv = env == null ? new String[0] : env;
        mColumns = Math.max(1, columns);
        mRows = Math.max(1, rows);
        mCellWidthPixels = Math.max(0, cellWidthPixels);
        mCellHeightPixels = Math.max(0, cellHeightPixels);
    }

    public String getShell() {
        return mShell;
    }

    public String getCwd() {
        return mCwd;
    }

    public String[] getArgs() {
        return mArgs;
    }

    public String[] getEnv() {
        return mEnv;
    }

    public int getColumns() {
        return mColumns;
    }

    public int getRows() {
        return mRows;
    }

    public int getCellWidthPixels() {
        return mCellWidthPixels;
    }

    public int getCellHeightPixels() {
        return mCellHeightPixels;
    }
}
