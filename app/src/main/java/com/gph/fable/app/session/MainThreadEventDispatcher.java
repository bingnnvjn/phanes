package com.gph.fable.app.session;

import java.util.ArrayDeque;

/**
 * 工单 44：把 native 事件收敛为每会话一个有界主线程 drain。
 *
 * 输出背压是阻塞生产者而不是丢字节：native dispatcher 在队列满时等待主线程消费；
 * close 会推进 generation 并清空旧事件，已投递 runnable 因 generation 不匹配而静默。
 */
final class MainThreadEventDispatcher {

    static final int MAX_QUEUED_OUTPUT_BYTES = 1024 * 1024;
    static final int MAX_DRAIN_OUTPUT_BYTES = 256 * 1024;

    interface Scheduler {
        boolean post(Runnable runnable);
    }

    interface Consumer {
        void onEvent(long sessionId, String event, long timestampMs, byte[] data,
                     int exitCode, String message, String[] extra);
    }

    static final class Diagnostics {
        final long deliveredBytes;
        final int peakQueuedOutputBytes;
        final long drainRunnableCount;
        final long mergedOutputChunkCount;
        final long copiedInputBytes;
        final long mergeAllocationBytes;

        Diagnostics(long deliveredBytes, int peakQueuedOutputBytes, long drainRunnableCount,
                    long mergedOutputChunkCount, long copiedInputBytes,
                    long mergeAllocationBytes) {
            this.deliveredBytes = deliveredBytes;
            this.peakQueuedOutputBytes = peakQueuedOutputBytes;
            this.drainRunnableCount = drainRunnableCount;
            this.mergedOutputChunkCount = mergedOutputChunkCount;
            this.copiedInputBytes = copiedInputBytes;
            this.mergeAllocationBytes = mergeAllocationBytes;
        }
    }

    private static final class Event {
        final long sessionId;
        final String name;
        final long timestampMs;
        byte[] data;
        final int exitCode;
        final String message;
        final String[] extra;

        Event(long sessionId, String name, long timestampMs, byte[] data,
              int exitCode, String message, String[] extra) {
            this.sessionId = sessionId;
            this.name = name;
            this.timestampMs = timestampMs;
            this.data = data;
            this.exitCode = exitCode;
            this.message = message;
            this.extra = extra;
        }

        boolean isOutput() {
            return "output_chunk".equals(name);
        }
    }

    private final Scheduler mScheduler;
    private final Consumer mConsumer;
    private final ArrayDeque<Event> mQueue = new ArrayDeque<>();

    private boolean mClosed;
    private boolean mDrainScheduled;
    private long mGeneration;
    private int mQueuedOutputBytes;
    private long mDeliveredBytes;
    private int mPeakQueuedOutputBytes;
    private long mDrainRunnableCount;
    private long mMergedOutputChunkCount;
    private long mCopiedInputBytes;
    private long mMergeAllocationBytes;

    MainThreadEventDispatcher(Scheduler scheduler, Consumer consumer) {
        mScheduler = scheduler;
        mConsumer = consumer;
    }

    boolean enqueueOutput(byte[] data, int len) {
        return enqueueOutput(0, 0, data, len);
    }

    boolean enqueue(long sessionId, String event, long timestampMs, byte[] data,
                    int exitCode, String message, String[] extra) {
        if ("output_chunk".equals(event)) {
            return enqueueOutput(sessionId, timestampMs, data, data == null ? 0 : data.length);
        }
        return enqueueOne(sessionId, event, timestampMs, copyBytes(data, data == null ? 0 : data.length),
            exitCode, message, extra);
    }

    private boolean enqueueOutput(long sessionId, long timestampMs, byte[] data, int len) {
        if (data == null || len <= 0) {
            return enqueueOne(sessionId, "output_chunk", timestampMs, new byte[0],
                -1, null, null);
        }
        int safeLen = Math.min(len, data.length);
        for (int offset = 0; offset < safeLen; ) {
            int chunkLen = Math.min(MAX_DRAIN_OUTPUT_BYTES, safeLen - offset);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(data, offset, chunk, 0, chunkLen);
            synchronized (this) {
                mCopiedInputBytes += chunkLen;
            }
            if (!enqueueOne(sessionId, "output_chunk", timestampMs, chunk, -1, null, null)) {
                return false;
            }
            offset += chunkLen;
        }
        return true;
    }

    private boolean enqueueOne(long sessionId, String event, long timestampMs, byte[] payload,
                               int exitCode, String message, String[] extra) {
        synchronized (this) {
            if (mClosed) return false;
            while (!mClosed && !mQueue.isEmpty() && "output_chunk".equals(event)
                && mQueuedOutputBytes + payload.length > MAX_QUEUED_OUTPUT_BYTES) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (mClosed) return false;

            if ("output_chunk".equals(event) && mergeOutput(payload)) {
                return scheduleDrainLocked();
            }

            mQueue.addLast(new Event(sessionId, event, timestampMs, payload,
                exitCode, message, extra));
            if ("output_chunk".equals(event)) {
                mQueuedOutputBytes += payload.length;
                mPeakQueuedOutputBytes = Math.max(mPeakQueuedOutputBytes, mQueuedOutputBytes);
            }
            return scheduleDrainLocked();
        }
    }

    synchronized void close() {
        mClosed = true;
        mGeneration++;
        mQueue.clear();
        mQueuedOutputBytes = 0;
        mDrainScheduled = false;
        notifyAll();
    }

    private boolean mergeOutput(byte[] payload) {
        Event tail = mQueue.peekLast();
        if (tail == null || !tail.isOutput()) return false;
        if (tail.data.length + payload.length > MAX_DRAIN_OUTPUT_BYTES) return false;

        byte[] merged = new byte[tail.data.length + payload.length];
        System.arraycopy(tail.data, 0, merged, 0, tail.data.length);
        System.arraycopy(payload, 0, merged, tail.data.length, payload.length);
        tail.data = merged;
        mQueuedOutputBytes += payload.length;
        mPeakQueuedOutputBytes = Math.max(mPeakQueuedOutputBytes, mQueuedOutputBytes);
        mMergedOutputChunkCount++;
        mMergeAllocationBytes += merged.length;
        return true;
    }

    private boolean scheduleDrainLocked() {
        if (mDrainScheduled) return true;
        mDrainScheduled = true;
        final long scheduledGeneration = mGeneration;
        if (mScheduler.post(() -> drain(scheduledGeneration))) {
            mDrainRunnableCount++;
            return true;
        }

        mDrainScheduled = false;
        mClosed = true;
        mQueue.clear();
        mQueuedOutputBytes = 0;
        notifyAll();
        return false;
    }

    private void drain(long scheduledGeneration) {
        int drainedOutputBytes = 0;
        while (true) {
            synchronized (this) {
                if (mClosed || scheduledGeneration != mGeneration) return;
                Event next = mQueue.pollFirst();
                if (next == null) {
                    mDrainScheduled = false;
                    return;
                }
                if (next.isOutput()) {
                    mQueuedOutputBytes -= next.data.length;
                    notifyAll();
                }
                // close() 与 callback 串行：close 返回后，不会有已出队但尚未调用的旧
                // generation 事件触达 TerminalSession 或 Activity。
                mConsumer.onEvent(next.sessionId, next.name, next.timestampMs, next.data,
                    next.exitCode, next.message, next.extra);
                if (next.isOutput()) {
                    mDeliveredBytes += next.data.length;
                    drainedOutputBytes += next.data.length;
                }
                if (!mClosed && drainedOutputBytes >= MAX_DRAIN_OUTPUT_BYTES && !mQueue.isEmpty()) {
                    if (mScheduler.post(() -> drain(scheduledGeneration))) {
                        mDrainRunnableCount++;
                        return;
                    }

                    mDrainScheduled = false;
                    mClosed = true;
                    mQueue.clear();
                    mQueuedOutputBytes = 0;
                    notifyAll();
                    return;
                }
            }
        }
    }

    long getDeliveredBytesForTest() {
        synchronized (this) {
            return mDeliveredBytes;
        }
    }

    Diagnostics getDiagnostics() {
        synchronized (this) {
            return new Diagnostics(mDeliveredBytes, mPeakQueuedOutputBytes, mDrainRunnableCount,
                mMergedOutputChunkCount, mCopiedInputBytes, mMergeAllocationBytes);
        }
    }

    private static byte[] copyBytes(byte[] data, int len) {
        if (data == null || len <= 0) return new byte[0];
        int safeLen = Math.min(len, data.length);
        byte[] copy = new byte[safeLen];
        System.arraycopy(data, 0, copy, 0, safeLen);
        return copy;
    }
}
