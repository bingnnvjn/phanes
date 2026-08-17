package com.gph.fable.app.session

import android.util.Log
import com.gph.fable.app.SessionHandle
import com.gph.fable.app.SessionLogCallback
import com.gph.fable.core.session.FableSession
import com.gph.fable.core.session.FableSessionCallbacks
import com.gph.fable.core.session.FableSessionFactory
import com.gph.fable.core.session.FableSessionSpec
import com.gph.fable.shared.logger.Logger

/**
 * 会话层抽象缝的 Rust 工厂（工单 26）：创建 [RustPtySession]
 * （libfable-session.so，fable-v1/25 产物）。
 *
 * 诊断日志订阅（sessionSetLogCallback）为进程级全局回调，注册一次即可；
 * 事件流日志经各会话 FableSessionCallbacks#onDiagnostics 投递（TerminalSession 落日志）。
 */
object RustFableSessionFactory : FableSessionFactory {

    private const val LOG_TAG = "FableSessionRust"

    @Volatile
    private var logCallbackRegistered = false

    /** libfable-session 日志级别（工单 25 定稿：0=info 1=warn 2=error，2026-08-11 核实）。 */
    private const val LOG_LEVEL_WARN = 1
    private const val LOG_LEVEL_ERROR = 2

    override fun create(
        spec: FableSessionSpec?,
        callbacks: FableSessionCallbacks?
    ): FableSession? {
        if (spec == null || callbacks == null) return null
        return try {
            registerLogCallbackOnce()
            val session = RustPtySession(spec, callbacks)
            if (!session.isValid()) {
                // sessionCreate 已把 lastError 记日志。
                null
            } else {
                session
            }
        } catch (t: Throwable) {
            // libfable-session.so 缺失/加载失败等极端情况：降级为创建失败，
            // TerminalSession 统一写 "[Session creation failed]" 结束会话。
            Logger.logError(LOG_TAG, "Rust session create failed: $t")
            null
        }
    }

    override fun getEngineName(): String = "rust"

    /** 进程级日志订阅只注册一次；level 0/1/2 = info/warn/error。 */
    @Synchronized
    private fun registerLogCallbackOnce() {
        if (logCallbackRegistered) return
        logCallbackRegistered = true
        try {
            SessionHandle.sessionSetLogCallback(
                SessionLogCallback { sessionId, level, tag, message, timestampMs ->
                    val priority = when (level) {
                        LOG_LEVEL_WARN -> Log.WARN
                        LOG_LEVEL_ERROR -> Log.ERROR
                        else -> Log.INFO
                    }
                    Logger.logMessage(
                        priority,
                        LOG_TAG,
                        "session=$sessionId tag=$tag msg=$message"
                    )
                }
            )
        } catch (t: Throwable) {
            Logger.logError(LOG_TAG, "sessionSetLogCallback failed: $t")
            logCallbackRegistered = false
        }
    }
}
