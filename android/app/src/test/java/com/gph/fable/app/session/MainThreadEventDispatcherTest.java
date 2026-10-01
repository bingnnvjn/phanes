package com.gph.fable.app.session;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 工单 44：主线程事件 drain 的输出完整性与关闭代际契约。 */
public class MainThreadEventDispatcherTest {

    private static final class RecordingScheduler implements MainThreadEventDispatcher.Scheduler {
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        int postCount;

        @Override
        public boolean post(Runnable runnable) {
            postCount++;
            queued.addLast(runnable);
            return true;
        }

        void runNext() {
            assertFalse("应有一个已投递的 drain", queued.isEmpty());
            queued.removeFirst().run();
        }

        void runAll() {
            while (!queued.isEmpty()) runNext();
        }
    }

    @Test
    public void continuousOutputUsesOneDrainAndPreservesEveryByte() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> output = new ArrayList<>();
        MainThreadEventDispatcher dispatcher = new MainThreadEventDispatcher(
            scheduler,
            (sessionId, event, ts, data, exitCode, message, extra) ->
                output.add(new String(data, StandardCharsets.UTF_8)));

        dispatcher.enqueueOutput("alpha".getBytes(StandardCharsets.UTF_8), 5);
        dispatcher.enqueueOutput("-beta".getBytes(StandardCharsets.UTF_8), 5);
        dispatcher.enqueueOutput("-gamma".getBytes(StandardCharsets.UTF_8), 6);

        assertEquals("连续 chunk 只能投递一个主线程 drain", 1, scheduler.postCount);
        scheduler.runAll();

        assertEquals(1, output.size());
        assertEquals("alpha-beta-gamma", output.get(0));
        assertEquals(16, dispatcher.getDeliveredBytesForTest());
    }

    @Test
    public void closeInvalidatesQueuedGenerationBeforeItCanReachOutput() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> output = new ArrayList<>();
        MainThreadEventDispatcher dispatcher = new MainThreadEventDispatcher(
            scheduler,
            (sessionId, event, ts, data, exitCode, message, extra) ->
                output.add(new String(data, StandardCharsets.UTF_8)));

        dispatcher.enqueueOutput("stale".getBytes(StandardCharsets.UTF_8), 5);
        assertEquals(1, scheduler.postCount);

        dispatcher.close();
        scheduler.runAll();

        assertTrue("close 后旧 generation 不得投递输出", output.isEmpty());
        assertFalse(dispatcher.enqueueOutput("later".getBytes(StandardCharsets.UTF_8), 5));
    }

    @Test
    public void fullQueueBackpressuresProducerInsteadOfDroppingOutput() throws Exception {
        RecordingScheduler scheduler = new RecordingScheduler();
        MainThreadEventDispatcher dispatcher = new MainThreadEventDispatcher(
            scheduler,
            (sessionId, event, ts, data, exitCode, message, extra) -> {
            });
        byte[] fullBatch = new byte[MainThreadEventDispatcher.MAX_QUEUED_OUTPUT_BYTES];
        assertTrue(dispatcher.enqueueOutput(fullBatch, fullBatch.length));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch enqueued = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            started.countDown();
            assertTrue(dispatcher.enqueueOutput(new byte[] { 42 }, 1));
            enqueued.countDown();
        });
        producer.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertFalse("满队列时生产者应等待主线程消费", enqueued.await(200, TimeUnit.MILLISECONDS));

        scheduler.runNext();
        assertTrue("drain 后生产者应继续，且不丢字节", enqueued.await(1, TimeUnit.SECONDS));
        scheduler.runAll();
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertEquals(MainThreadEventDispatcher.MAX_QUEUED_OUTPUT_BYTES + 1L,
            dispatcher.getDeliveredBytesForTest());
    }

    @Test
    public void highOutputYieldsToAnotherDrainAfterBoundedBatch() {
        RecordingScheduler scheduler = new RecordingScheduler();
        MainThreadEventDispatcher dispatcher = new MainThreadEventDispatcher(
            scheduler,
            (sessionId, event, ts, data, exitCode, message, extra) -> {
            });
        byte[] output = new byte[MainThreadEventDispatcher.MAX_DRAIN_OUTPUT_BYTES * 2];

        assertTrue(dispatcher.enqueueOutput(output, output.length));
        assertEquals(1, scheduler.postCount);
        scheduler.runNext();

        assertEquals("首个 drain 达到预算后应让出主线程并投递下一轮", 2, scheduler.postCount);
        assertEquals("诊断值应计入续投的 drain runnable", 2,
            dispatcher.getDiagnostics().drainRunnableCount);
        assertEquals(MainThreadEventDispatcher.MAX_DRAIN_OUTPUT_BYTES,
            dispatcher.getDeliveredBytesForTest());
        scheduler.runAll();
        assertEquals(output.length, dispatcher.getDeliveredBytesForTest());
    }

    @Test
    public void closeWaitsForAnInFlightCallbackBeforeReturning() throws Exception {
        RecordingScheduler scheduler = new RecordingScheduler();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        MainThreadEventDispatcher dispatcher = new MainThreadEventDispatcher(
            scheduler,
            (sessionId, event, ts, data, exitCode, message, extra) -> {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        assertTrue(dispatcher.enqueueOutput(new byte[] { 1 }, 1));

        Thread drain = new Thread(scheduler::runNext);
        drain.start();
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            dispatcher.close();
            closeReturned.countDown();
        });
        closer.start();
        assertFalse("close 需等待已开始的 callback 结束", closeReturned.await(200, TimeUnit.MILLISECONDS));

        releaseCallback.countDown();
        assertTrue(closeReturned.await(1, TimeUnit.SECONDS));
        drain.join(1000);
        closer.join(1000);
    }

    @Test
    public void diagnosticsReportQueuePeakDrainsMergesAndAllocationProxy() {
        RecordingScheduler scheduler = new RecordingScheduler();
        MainThreadEventDispatcher dispatcher = new MainThreadEventDispatcher(
            scheduler,
            (sessionId, event, ts, data, exitCode, message, extra) -> {
            });

        dispatcher.enqueueOutput("alpha".getBytes(StandardCharsets.UTF_8), 5);
        dispatcher.enqueueOutput("beta".getBytes(StandardCharsets.UTF_8), 4);
        dispatcher.enqueueOutput("gamma".getBytes(StandardCharsets.UTF_8), 5);
        scheduler.runAll();

        MainThreadEventDispatcher.Diagnostics diagnostics = dispatcher.getDiagnostics();
        assertEquals(14, diagnostics.deliveredBytes);
        assertEquals(14, diagnostics.peakQueuedOutputBytes);
        assertEquals(1, diagnostics.drainRunnableCount);
        assertEquals(2, diagnostics.mergedOutputChunkCount);
        assertEquals(14, diagnostics.copiedInputBytes);
        assertEquals(23, diagnostics.mergeAllocationBytes);
    }
}
