package com.gph.fable.app

/** Rust session diagnostic log callback. */
fun interface SessionLogCallback {
    fun onLog(sessionId: Long, level: Int, tag: String, message: String, timestampMs: Long)
}
