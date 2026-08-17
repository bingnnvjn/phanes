package com.gph.fable.app.session

import android.os.Handler
import android.os.Looper
import com.gph.fable.app.SessionEventCallback
import com.gph.fable.app.SessionHandle
import com.gph.fable.core.session.FableSession
import com.gph.fable.core.session.FableSessionCallbacks
import com.gph.fable.core.session.FableSessionSpec
import com.gph.fable.shared.logger.Logger

private const val LOG_TAG = "RustPtySession"

/** libfable-session 事件名（工单 25 六事件 schema，2026-08-11 核实）。 */
private const val EVENT_OUTPUT_CHUNK = "output_chunk"
private const val EVENT_EXIT_CODE = "exit_code"
private const val EVENT_SESSION_CLOSED = "session_closed"

/**
 * 会话层抽象缝的 Rust 实现（工单 26）：包装 fable-v1/25 的
 * [SessionHandle]（libfable-session.so，portable-pty）。
 *
 * - 事件回调来自 Rust 每会话分发线程，先 marshal 到主线程再投递
 *   [FableSessionCallbacks]（主线程契约）；
 * - 事件流六事件第一版只做诊断/日志（ADR-0008 决策 7，2026-08-11 核实）：
 *   output_chunk → onOutput；exit_code → 记录退出码；session_closed → onExit；
 * - pid 未从 JNI 暴露（工单 25 边界定稿 8 符号，2026-08-11 核实），getPid() 返回 0；
 *   getCwd() 返回创建时 cwd。
 * - args[0] 是 argv0 名（登录 shell "-bash"，与 execvp 同语义），
 *   原样传给 sessionCreate；Rust 侧经 portable-pty argv0 补丁实现（2026-08-11）。
 */
class RustPtySession(
    spec: FableSessionSpec,
    private val callbacks: FableSessionCallbacks
) : FableSession {

    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * Native 回调线程只入队；本对象保证输出背压、同一时刻仅一个主线程 drain，
     * 并在 close 时让已排队的旧 generation 静默。
     */
    private val eventDispatcher: MainThreadEventDispatcher
    private val eventCallback: SessionEventCallback
    private val handle: Long
    private val cwd: String

    @Volatile
    private var running = true

    @Volatile
    private var exited = false

    @Volatile
    private var closed = false

    /** 会话结束后是否已释放 Rust 侧句柄（sessionClose 幂等标记）。 */
    @Volatile
    private var handleReleased = false

    @Volatile
    private var exitStatus = 0

    init {
        cwd = spec.getCwd()
        eventDispatcher = MainThreadEventDispatcher(
            { runnable -> mainHandler.post(runnable) },
            { sessionId, event, timestampMs, data, exitCode, message, extra ->
                dispatch(sessionId, event, timestampMs, data, exitCode, message, extra)
            }
        )
        eventCallback = SessionEventCallback { sessionId, event, timestampMs, data, exitCode, message, extra ->
            eventDispatcher.enqueue(sessionId, event, timestampMs, data, exitCode, message, extra)
        }
        handle = SessionHandle.sessionCreate(
            spec.getShell(),
            spec.getArgs(),
            spec.getEnv(),
            spec.getCwd(),
            spec.getColumns(),
            spec.getRows(),
            eventCallback
        )
        if (handle == 0L) {
            running = false
            exited = true
            Logger.logError(LOG_TAG, "sessionCreate failed: ${SessionHandle.sessionLastError()}")
        }
    }

    fun isValid(): Boolean = handle != 0L

    private fun dispatch(
        sessionId: Long,
        event: String,
        timestampMs: Long,
        data: ByteArray?,
        exitCode: Int,
        message: String?,
        extra: Array<String>?
    ) {
        when (event) {
            EVENT_OUTPUT_CHUNK -> {
                // 与 Java 语义一致：直到 session_closed 前的输出都投递
                // （kill 后残余输出也回显，如 logout）。
                if (data != null && data.isNotEmpty()) {
                    callbacks.onOutput(data, data.size)
                }
            }

            EVENT_EXIT_CODE -> {
                this.exitStatus = exitCode
            }

            EVENT_SESSION_CLOSED -> {
                if (!exited) {
                    exited = true
                    running = false
                    callbacks.onExit(exitStatus)
                }
                logDispatcherDiagnostics("native-session-closed")
                // 会话已结束：释放 Rust 侧会话记录（幂等；kill 流程的
                // sessionClose 已在 close() 先行，此处仅自然退出时执行）。
                releaseHandle()
            }
        }
        callbacks.onDiagnostics(event, sessionId, timestampMs, data, exitCode, message, extra)
    }

    override fun write(data: ByteArray, offset: Int, len: Int) {
        if (handle == 0L || closed || exited) return
        if (offset == 0 && len == data.size) {
            writeChecked(data, len)
        } else {
            val slice = data.copyOfRange(offset, offset + len)
            writeChecked(slice, len)
        }
    }

    private fun writeChecked(data: ByteArray, len: Int) {
        val written = SessionHandle.sessionWrite(handle, data, len)
        if (written < 0) {
            // 写失败不再静默（工单 26 审查项：错误零反馈）。
            Logger.logWarn(LOG_TAG, "sessionWrite failed: ${SessionHandle.sessionLastError()}")
        }
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (handle != 0L && !closed && !exited) {
            SessionHandle.sessionResize(handle, columns, rows)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 先撤销 JNI callback，再使 Java drain 的旧 generation 无效；因此即使 native
        // 分发线程已拿到一个事件，close 返回后也不能再触达 TerminalSession/Activity。
        eventDispatcher.close()
        if (handle != 0L && !exited) {
            SessionHandle.sessionSetEventCallback(handle, null)
            SessionHandle.sessionClose(handle)
            handleReleased = true
            // FableSession 契约要求 close 后仍有一次 onExit；这是调用 close 的受控
            // 终态通知，不复用/放行任何 native 的旧 generation callback。
            mainHandler.post(this::notifyClosedByCaller)
        }
    }

    /** close() 的受控终态通知：仅主线程执行，幂等。 */
    private fun notifyClosedByCaller() {
        if (exited) return
        exited = true
        running = false
        callbacks.onExit(exitStatus)
        logDispatcherDiagnostics("caller-close")
    }

    /** 工单 44 真机验收：仅在会话终态输出一行 dispatcher 汇总。 */
    private fun logDispatcherDiagnostics(reason: String) {
        val diagnostics = eventDispatcher.getDiagnostics()
        Logger.logInfo(
            LOG_TAG,
            "dispatcher reason=$reason" +
                " delivered_bytes=${diagnostics.deliveredBytes}" +
                " peak_queued_bytes=${diagnostics.peakQueuedOutputBytes}" +
                " drain_runnables=${diagnostics.drainRunnableCount}" +
                " merged_chunks=${diagnostics.mergedOutputChunkCount}" +
                " copied_input_bytes=${diagnostics.copiedInputBytes}" +
                " merge_alloc_bytes=${diagnostics.mergeAllocationBytes}"
        )
    }

    /** 释放 Rust 侧会话记录（会话结束后调用；幂等）。 */
    private fun releaseHandle() {
        if (handle == 0L || handleReleased) return
        handleReleased = true
        SessionHandle.sessionClose(handle)
    }

    override fun isRunning(): Boolean = running

    override fun getExitStatus(): Int = exitStatus

    override fun getPid(): Int {
        // 工单 25 JNI 边界未暴露 pid；v1 不扩展（见工单 26 Comments）。
        return 0
    }

    override fun getCwd(): String = cwd

    override fun getEngineName(): String = "rust"
}
