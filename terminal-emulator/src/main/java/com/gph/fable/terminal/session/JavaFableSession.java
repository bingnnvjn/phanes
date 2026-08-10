package com.gph.fable.terminal.session;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.gph.fable.terminal.ByteQueue;
import com.gph.fable.terminal.JNI;
import com.gph.fable.terminal.Logger;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * 会话层抽象缝的 Java 实现（工单 26）：把 TerminalSession 原有的
 * PTY/进程/生命周期机制（JNI.createSubprocess + 读/写/等待三线程 +
 * 双 ByteQueue + 主线程投递）原样抽出到本类，行为不变（零回归）。
 *
 * 输出经 {@link #mMainThreadHandler} 在创建线程（通常主线程）投递
 * {@link FableSessionCallbacks#onOutput}；进程退出经
 * {@link FableSessionCallbacks#onExit} 投递（资源在投递前已清理）。
 */
public final class JavaFableSession implements FableSession {

    private static final String LOG_TAG = "JavaFableSession";

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    private final FableSessionSpec mSpec;
    private final FableSessionCallbacks mCallbacks;

    /** 队列：进程输出 → 主线程；用户输入 → 写线程。 */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);

    /** 子进程 pid；0=未启动，-1=已退出。 */
    private int mShellPid;
    private int mShellExitStatus;

    /** PTY master 半区 fd（JNI.createSubprocess 返回）。 */
    private int mTerminalFileDescriptor;

    private boolean mStarted;
    private boolean mExited;

    /** 投递输出/退出回调的 Handler（绑定创建线程的 looper，即主线程）。 */
    @SuppressLint("HandlerLeak")
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper()) {
        final byte[] mReceiveBuffer = new byte[4 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mCallbacks.onOutput(mReceiveBuffer, bytesRead);
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanup(exitCode);
                mCallbacks.onExit(exitCode);
            }
        }
    };

    JavaFableSession(FableSessionSpec spec, FableSessionCallbacks callbacks) {
        mSpec = spec;
        mCallbacks = callbacks;
    }

    /** 创建子进程并启动读/写/等待线程；失败返回 false（错误已记日志）。 */
    boolean start() {
        if (mStarted) return true;
        mStarted = true;

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mSpec.getShell(), mSpec.getCwd(),
            mSpec.getArgs(), mSpec.getEnv(), processId, mSpec.getRows(), mSpec.getColumns(),
            mSpec.getCellWidthPixels(), mSpec.getCellHeightPixels());
        mShellPid = processId[0];
        if (mShellPid <= 0) {
            Logger.logError(null, LOG_TAG, "Failed to create subprocess for shell=" + mSpec.getShell());
            mShellPid = -1;
            mExited = true;
            return false;
        }

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

        return true;
    }

    /** 进程退出后的资源清理（主线程，幂等）。 */
    private void cleanup(int exitStatus) {
        synchronized (this) {
            if (mExited) return;
            mExited = true;
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // 停止 reader/writer 线程并关闭 I/O 流。
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        JNI.close(mTerminalFileDescriptor);
    }

    @Override
    public void write(byte[] data, int offset, int len) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, len);
    }

    @Override
    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mShellPid > 0 && mTerminalFileDescriptor > 0) {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
        }
    }

    @Override
    public void close() {
        // 与原 finishIfRunning 语义一致：SIGKILL；资源清理在退出事件路径完成。
        if (mStarted && mShellPid > 0) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(null, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    @Override
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public int getPid() {
        return mShellPid;
    }

    @Override
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(null, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    @Override
    public String getEngineName() {
        return "java";
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(null, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }
}
