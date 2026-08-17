package com.gph.fable.app.session;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gph.fable.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 最近会话记录（工单 04，ADR-0002 第 4 节方案 B）。
 *
 * 每条记录 = 最近一次使用时间戳 + 工作目录，持久化在 App SharedPreferences。
 * 进程被杀后 PTY 无法复活，一键重开 = 新 shell 进入记录的工作目录；
 * 目录失效时回退 {@code $HOME}（{@link #resolveWorkingDirectory}）。
 *
 * 存储格式：每行 {@code <timestamp>\t<urlencoded path>}，新记录在前；
 * 编码/解码为纯逻辑，便于 JVM 单测（缝 2）。
 */
public final class RecentSessionStore {

    /** 最多保留的记录条数。 */
    public static final int MAX_ENTRIES = 10;

    private static final String KEY = "fable_recent_sessions_v1";
    private RecentSessionStore() {
    }

    /** 一条最近会话记录。 */
    public static final class RecentSession {
        public final long timestamp;
        public final String workingDirectory;

        public RecentSession(long timestamp, String workingDirectory) {
            this.timestamp = timestamp;
            this.workingDirectory = workingDirectory;
        }
    }

    /** 记录一次会话使用：已存在同目录则移到最前并更新时间，否则插入最前，超上限裁尾。 */
    public static void record(@NonNull Context context, @NonNull String workingDirectory) {
        record(preferencesStore(context), workingDirectory);
    }

    /** 读取最近会话记录（新记录在前）。 */
    @NonNull
    public static List<RecentSession> load(@NonNull Context context) {
        return load(preferencesStore(context));
    }

    /** 删除某工作目录的记录。 */
    public static void remove(@NonNull Context context, @NonNull String workingDirectory) {
        remove(preferencesStore(context), workingDirectory);
    }

    /** 存储抽象：JVM 测试用内存实现，绕开 Robolectric 4.8.1 + JDK 25 基线崩溃。 */
    public interface StringStore {
        @Nullable
        String read();

        void write(@Nullable String value);
    }

    /** 记录（store 版，公共行为入口）。 */
    public static void record(@NonNull StringStore store, @NonNull String workingDirectory) {
        List<RecentSession> sessions = upsert(load(store), workingDirectory, System.currentTimeMillis());
        store.write(encode(sessions));
    }

    /** 读取（store 版）。 */
    @NonNull
    public static List<RecentSession> load(@NonNull StringStore store) {
        return decode(store.read());
    }

    /** 删除（store 版）。 */
    public static void remove(@NonNull StringStore store, @NonNull String workingDirectory) {
        List<RecentSession> sessions = new ArrayList<>(load(store));
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (workingDirectory.equals(sessions.get(i).workingDirectory)) {
                sessions.remove(i);
            }
        }
        store.write(encode(sessions));
    }

    @NonNull
    private static StringStore preferencesStore(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION, Context.MODE_PRIVATE);
        return new StringStore() {
            @Override
            public String read() {
                return preferences.getString(KEY, null);
            }

            @Override
            public void write(String value) {
                preferences.edit().putString(KEY, value).apply();
            }
        };
    }

    /** 一键重开目录解析：目录存在用目录，否则回退 {@code home}。 */
    @NonNull
    public static String resolveWorkingDirectory(@Nullable String workingDirectory, @NonNull String home) {
        if (workingDirectory == null || workingDirectory.isEmpty()) return home;
        File directory = new File(workingDirectory);
        return directory.isDirectory() ? directory.getAbsolutePath() : home;
    }

    /** 纯逻辑：插入/更新（测试入口）。 */
    @NonNull
    static List<RecentSession> upsert(@NonNull List<RecentSession> sessions, @NonNull String workingDirectory, long timestamp) {
        List<RecentSession> result = new ArrayList<>(sessions.size() + 1);
        for (RecentSession session : sessions) {
            if (!workingDirectory.equals(session.workingDirectory)) {
                result.add(session);
            }
        }
        result.add(0, new RecentSession(timestamp, workingDirectory));
        while (result.size() > MAX_ENTRIES) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    /** 纯逻辑：编码（测试入口）。 */
    @NonNull
    static String encode(@NonNull List<RecentSession> sessions) {
        StringBuilder builder = new StringBuilder();
        for (RecentSession session : sessions) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(session.timestamp).append('\t')
                .append(RecentSessionPathCodec.encode(session.workingDirectory));
        }
        return builder.toString();
    }

    /** 纯逻辑：解码，坏行跳过（测试入口）。 */
    @NonNull
    static List<RecentSession> decode(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();

        List<RecentSession> sessions = new ArrayList<>();
        for (String line : raw.split("\n")) {
            int separator = line.indexOf('\t');
            if (separator < 0) continue;

            long timestamp;
            try {
                timestamp = Long.parseLong(line.substring(0, separator));
            } catch (NumberFormatException e) {
                continue;
            }

            String workingDirectory = RecentSessionPathCodec.decode(line.substring(separator + 1));
            if (workingDirectory == null || workingDirectory.isEmpty()) continue;
            sessions.add(new RecentSession(timestamp, workingDirectory));
        }
        return sessions;
    }
}
