package com.gph.fable.app.session

import java.util.ArrayDeque

/**
 * 工单 44：把 native 事件收敛为每会话一个有界主线程 drain。
 *
 * 输出背压是阻塞生产者而不是丢字节：native dispatcher 在队列满时等待主线程消费；
 * close 会推进 generation 并清空旧事件，已投递 runnable 因 generation 不匹配而静默。
 */
class MainThreadEventDispatcher(
    private val scheduler: Scheduler,
    private val consumer: Consumer,
) {

    companion object {
        const val MAX_QUEUED_OUTPUT_BYTES = 1024 * 1024
        const val MAX_DRAIN_OUTPUT_BYTES = 256 * 1024

        private fun copyBytes(data: ByteArray?, len: Int): ByteArray {
            if (data == null || len <= 0) return ByteArray(0)
            val safeLen = minOf(len, data.size)
            return data.copyOf(safeLen)
        }
    }

    fun interface Scheduler {
        fun post(runnable: Runnable): Boolean
    }

    fun interface Consumer {
        fun onEvent(
            sessionId: Long,
            event: String,
            timestampMs: Long,
            data: ByteArray,
            exitCode: Int,
            message: String?,
            extra: Array<String>?,
        )
    }

    class Diagnostics(
        @JvmField val deliveredBytes: Long,
        @JvmField val peakQueuedOutputBytes: Int,
        @JvmField val drainRunnableCount: Long,
        @JvmField val mergedOutputChunkCount: Long,
        @JvmField val copiedInputBytes: Long,
        @JvmField val mergeAllocationBytes: Long,
    )

    private class Event(
        val sessionId: Long,
        val name: String,
        val timestampMs: Long,
        var data: ByteArray,
        val exitCode: Int,
        val message: String?,
        val extra: Array<String>?,
    ) {
        fun isOutput(): Boolean = name == "output_chunk"
    }

    private val queue = ArrayDeque<Event>()

    private var closed = false
    private var drainScheduled = false
    private var generation = 0L
    private var queuedOutputBytes = 0
    private var deliveredBytes = 0L
    private var peakQueuedOutputBytes = 0
    private var drainRunnableCount = 0L
    private var mergedOutputChunkCount = 0L
    private var copiedInputBytes = 0L
    private var mergeAllocationBytes = 0L

    fun enqueueOutput(data: ByteArray?, len: Int): Boolean {
        return enqueueOutput(0L, 0L, data, len)
    }

    fun enqueue(
        sessionId: Long,
        event: String?,
        timestampMs: Long,
        data: ByteArray?,
        exitCode: Int,
        message: String?,
        extra: Array<String>?,
    ): Boolean {
        if (event == "output_chunk") {
            return enqueueOutput(sessionId, timestampMs, data, data?.size ?: 0)
        }
        return enqueueOne(
            sessionId,
            event ?: "",
            timestampMs,
            copyBytes(data, data?.size ?: 0),
            exitCode,
            message,
            extra,
        )
    }

    private fun enqueueOutput(
        sessionId: Long,
        timestampMs: Long,
        data: ByteArray?,
        len: Int,
    ): Boolean {
        if (data == null || len <= 0) {
            return enqueueOne(sessionId, "output_chunk", timestampMs, ByteArray(0), -1, null, null)
        }
        val safeLen = minOf(len, data.size)
        var offset = 0
        while (offset < safeLen) {
            val chunkLen = minOf(MAX_DRAIN_OUTPUT_BYTES, safeLen - offset)
            val chunk = data.copyOfRange(offset, offset + chunkLen)
            synchronized(this) {
                copiedInputBytes += chunkLen
            }
            if (!enqueueOne(sessionId, "output_chunk", timestampMs, chunk, -1, null, null)) {
                return false
            }
            offset += chunkLen
        }
        return true
    }

    private fun enqueueOne(
        sessionId: Long,
        event: String,
        timestampMs: Long,
        payload: ByteArray,
        exitCode: Int,
        message: String?,
        extra: Array<String>?,
    ): Boolean {
        synchronized(this) {
            if (closed) return false
            while (
                !closed &&
                queue.isNotEmpty() &&
                event == "output_chunk" &&
                queuedOutputBytes + payload.size > MAX_QUEUED_OUTPUT_BYTES
            ) {
                try {
                    (this as java.lang.Object).wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (closed) return false

            if (event == "output_chunk" && mergeOutput(payload)) {
                return scheduleDrainLocked()
            }

            queue.addLast(Event(sessionId, event, timestampMs, payload, exitCode, message, extra))
            if (event == "output_chunk") {
                queuedOutputBytes += payload.size
                peakQueuedOutputBytes = maxOf(peakQueuedOutputBytes, queuedOutputBytes)
            }
            return scheduleDrainLocked()
        }
    }

    @Synchronized
    fun close() {
        closed = true
        generation++
        queue.clear()
        queuedOutputBytes = 0
        drainScheduled = false
        (this as java.lang.Object).notifyAll()
    }

    private fun mergeOutput(payload: ByteArray): Boolean {
        val tail = queue.peekLast() ?: return false
        if (!tail.isOutput()) return false
        if (tail.data.size + payload.size > MAX_DRAIN_OUTPUT_BYTES) return false

        val merged = ByteArray(tail.data.size + payload.size)
        tail.data.copyInto(merged, destinationOffset = 0)
        payload.copyInto(merged, destinationOffset = tail.data.size)
        tail.data = merged
        queuedOutputBytes += payload.size
        peakQueuedOutputBytes = maxOf(peakQueuedOutputBytes, queuedOutputBytes)
        mergedOutputChunkCount++
        mergeAllocationBytes += merged.size
        return true
    }

    private fun scheduleDrainLocked(): Boolean {
        if (drainScheduled) return true
        drainScheduled = true
        val scheduledGeneration = generation
        if (scheduler.post(Runnable { drain(scheduledGeneration) })) {
            drainRunnableCount++
            return true
        }

        drainScheduled = false
        closed = true
        queue.clear()
        queuedOutputBytes = 0
        (this as java.lang.Object).notifyAll()
        return false
    }

    private fun drain(scheduledGeneration: Long) {
        var drainedOutputBytes = 0
        while (true) {
            synchronized(this) {
                if (closed || scheduledGeneration != generation) return
                val next = queue.pollFirst()
                if (next == null) {
                    drainScheduled = false
                    return
                }
                if (next.isOutput()) {
                    queuedOutputBytes -= next.data.size
                    (this as java.lang.Object).notifyAll()
                }
                // close() 与 callback 串行：close 返回后，不会有已出队但尚未调用的旧
                // generation 事件触达 TerminalSession 或 Activity。
                consumer.onEvent(
                    next.sessionId,
                    next.name,
                    next.timestampMs,
                    next.data,
                    next.exitCode,
                    next.message,
                    next.extra,
                )
                if (next.isOutput()) {
                    deliveredBytes += next.data.size
                    drainedOutputBytes += next.data.size
                }
                if (!closed && drainedOutputBytes >= MAX_DRAIN_OUTPUT_BYTES && queue.isNotEmpty()) {
                    if (scheduler.post(Runnable { drain(scheduledGeneration) })) {
                        drainRunnableCount++
                        return
                    }

                    drainScheduled = false
                    closed = true
                    queue.clear()
                    queuedOutputBytes = 0
                    (this as java.lang.Object).notifyAll()
                    return
                }
            }
        }
    }

    fun getDeliveredBytesForTest(): Long {
        synchronized(this) {
            return deliveredBytes
        }
    }

    fun getDiagnostics(): Diagnostics {
        synchronized(this) {
            return Diagnostics(
                deliveredBytes,
                peakQueuedOutputBytes,
                drainRunnableCount,
                mergedOutputChunkCount,
                copiedInputBytes,
                mergeAllocationBytes,
            )
        }
    }
}
