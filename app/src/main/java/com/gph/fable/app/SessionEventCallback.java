package com.gph.fable.app;

/**
 * 工单 25 事件回调：六事件最小集 schema（ADR-0008 决策 7）。
 * event 取值为 session_created / command_started / output_chunk /
 * command_finished / exit_code / session_closed。
 * data 仅 output_chunk 有值（UTF-8 字节）；exitCode 仅 exit_code 有值（-1=无）；
 * message 为诊断说明；extra 为扩展 metadata（"k=v" 数组，第一版可为空）。
 */
public interface SessionEventCallback {
    void onEvent(long sessionId, String event, long timestampMs,
                 byte[] data, int exitCode, String message, String[] extra);
}
