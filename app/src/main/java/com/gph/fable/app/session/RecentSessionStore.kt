package com.gph.fable.app.session

import android.content.Context
import android.content.SharedPreferences

import com.gph.fable.shared.termux.TermuxConstants

import java.io.File
import java.util.Collections

/**
 * 最近会话记录（工单 04，ADR-0002 第 4 节方案 B）。
 *
 * 每条记录 = 最近一次使用时间戳 + 工作目录，持久化在 App SharedPreferences。
 * 进程被杀后 PTY 无法复活，一键重开 = 新 shell 进入记录的工作目录；
 * 目录失效时回退 {@code $HOME}（[resolveWorkingDirectory]）。
 *
 * 存储格式：每行 {@code <timestamp>\t<urlencoded path>}，新记录在前；
 * 编码/解码为纯逻辑，便于 JVM 单测（缝 2）。
 */
object RecentSessionStore {

    /** 最多保留的记录条数。 */
    @JvmField
    val MAX_ENTRIES = 10

    private const val KEY = "fable_recent_sessions_v1"

    /** 一条最近会话记录。 */
    class RecentSession(
        @JvmField val timestamp: Long,
        @JvmField val workingDirectory: String
    )

    /** 存储抽象：JVM 测试用内存实现，绕开 Robolectric 4.8.1 + JDK 25 基线崩溃。 */
    interface StringStore {
        fun read(): String?

        fun write(value: String?)
    }

    /** 记录一次会话使用：已存在同目录则移到最前并更新时间，否则插入最前，超上限裁尾。 */
    @JvmStatic
    fun record(context: Context, workingDirectory: String) {
        record(preferencesStore(context), workingDirectory)
    }

    /** 读取最近会话记录（新记录在前）。 */
    @JvmStatic
    fun load(context: Context): List<RecentSession> {
        return load(preferencesStore(context))
    }

    /** 删除某工作目录的记录。 */
    @JvmStatic
    fun remove(context: Context, workingDirectory: String) {
        remove(preferencesStore(context), workingDirectory)
    }

    /** 记录（store 版，公共行为入口）。 */
    @JvmStatic
    fun record(store: StringStore, workingDirectory: String) {
        val sessions = upsert(load(store), workingDirectory, System.currentTimeMillis())
        store.write(encode(sessions))
    }

    /** 读取（store 版）。 */
    @JvmStatic
    fun load(store: StringStore): List<RecentSession> {
        return decode(store.read())
    }

    /** 删除（store 版）。 */
    @JvmStatic
    fun remove(store: StringStore, workingDirectory: String) {
        val sessions = load(store).toMutableList()
        for (index in sessions.lastIndex downTo 0) {
            if (workingDirectory == sessions[index].workingDirectory) {
                sessions.removeAt(index)
            }
        }
        store.write(encode(sessions))
    }

    private fun preferencesStore(context: Context): StringStore {
        val preferences: SharedPreferences = context.getSharedPreferences(
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION,
            Context.MODE_PRIVATE
        )
        return object : StringStore {
            override fun read(): String? = preferences.getString(KEY, null)

            override fun write(value: String?) {
                preferences.edit().putString(KEY, value).apply()
            }
        }
    }

    /** 一键重开目录解析：目录存在用目录，否则回退 [home]。 */
    @JvmStatic
    fun resolveWorkingDirectory(workingDirectory: String?, home: String): String {
        if (workingDirectory.isNullOrEmpty()) return home
        val directory = File(workingDirectory)
        return if (directory.isDirectory) directory.absolutePath else home
    }

    /** 纯逻辑：插入/更新（测试入口）。 */
    @JvmStatic
    fun upsert(
        sessions: List<RecentSession>,
        workingDirectory: String,
        timestamp: Long
    ): List<RecentSession> {
        val result = ArrayList<RecentSession>(sessions.size + 1)
        for (session in sessions) {
            if (workingDirectory != session.workingDirectory) {
                result.add(session)
            }
        }
        result.add(0, RecentSession(timestamp, workingDirectory))
        while (result.size > MAX_ENTRIES) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    /** 纯逻辑：编码（测试入口）。 */
    @JvmStatic
    fun encode(sessions: List<RecentSession>): String {
        val builder = StringBuilder()
        for (session in sessions) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(session.timestamp)
                .append('\t')
                .append(RecentSessionPathCodec.encode(session.workingDirectory))
        }
        return builder.toString()
    }

    /** 纯逻辑：解码，坏行跳过（测试入口）。 */
    @JvmStatic
    fun decode(raw: String?): List<RecentSession> {
        if (raw.isNullOrEmpty()) return Collections.emptyList()

        val sessions = ArrayList<RecentSession>()
        for (line in raw.split("\n")) {
            val separator = line.indexOf('\t')
            if (separator < 0) continue

            val timestamp = line.substring(0, separator).toLongOrNull() ?: continue
            val workingDirectory = RecentSessionPathCodec.decode(line.substring(separator + 1))
            if (workingDirectory.isNullOrEmpty()) continue
            sessions.add(RecentSession(timestamp, workingDirectory))
        }
        return sessions
    }
}
