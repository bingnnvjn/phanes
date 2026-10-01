package com.gph.fable.app

/** Rust session event callback; schema is the ADR-0008 six-event minimum set. */
fun interface SessionEventCallback {
    fun onEvent(sessionId: Long, event: String, timestampMs: Long, data: ByteArray?, exitCode: Int, message: String?, extra: Array<String>?)
}
