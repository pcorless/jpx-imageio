/*
 * Copyright 2026 Patrick Corless
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Provenance: ORIGINAL.
 */
package io.github.pcorless.jpx.internal;

import io.github.pcorless.jpx.Jpeg2000Threads;
import io.github.pcorless.jpx.JpxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ParallelTest {

    static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void everyItemOnce(Jpeg2000Threads threads, int n) throws JpxException {
        AtomicIntegerArray runs = new AtomicIntegerArray(n);
        Parallel.forEach(threads, n, () -> runs::incrementAndGet);
        for (int i = 0; i < n; i++) assertEquals(1, runs.get(i), "item " + i);
    }

    @Test
    public void runsEveryItemOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int n : new int[]{0, 1, 2, 7, 1000}) {
                everyItemOnce(Jpeg2000Threads.NONE, n);
                everyItemOnce(Jpeg2000Threads.of(pool, 4), n);
                everyItemOnce(Jpeg2000Threads.of(pool, 64), n);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void usesHelpers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            AtomicReference<Thread> caller = new AtomicReference<>(Thread.currentThread());
            AtomicInteger byHelpers = new AtomicInteger();
            Parallel.forEach(Jpeg2000Threads.of(pool, 4), 200, () -> i -> {
                if (Thread.currentThread() != caller.get()) byHelpers.incrementAndGet();
                pause();
            });
            assertTrue(byHelpers.get() > 0, "helpers ran items");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void executorThatNeverRunsAnything() throws Exception {
        // a saturated pool: tasks queue but never start; the caller must do all the work
        everyItemOnce(Jpeg2000Threads.of(task -> { }, 8), 100);
    }

    @Test
    public void rejectingExecutor() throws Exception {
        everyItemOnce(Jpeg2000Threads.of(task -> {
            throw new RejectedExecutionException("full");
        }, 8), 100);
    }

    @Test
    public void callerOnItsOwnSingleThreadPool() throws Exception {
        // the decode runs on the only worker of the pool it hands its helpers to: no deadlock
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Jpeg2000Threads threads = Jpeg2000Threads.of(pool, 4);
            Future<?> f = pool.submit(() -> {
                everyItemOnce(threads, 500);
                // nested: an item that runs its own parallel loop
                Parallel.forEach(threads, 20, () -> i -> everyItemOnce(threads, 50));
                return null;
            });
            f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void firstErrorIsRethrownAfterRunningItemsEnd() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            JpxException boom = new JpxException("item 37");
            AtomicInteger running = new AtomicInteger(), maxAfter = new AtomicInteger();
            JpxException got = assertThrows(JpxException.class, () ->
                    Parallel.forEach(Jpeg2000Threads.of(pool, 4), 1000, () -> i -> {
                        running.incrementAndGet();
                        try {
                            if (i == 37) throw boom;
                            pause();
                        } finally {
                            running.decrementAndGet();
                        }
                    }));
            assertSame(boom, got);
            assertEquals(0, running.get(), "no item still running when forEach returns");
            assertThrows(IllegalStateException.class, () ->
                    Parallel.forEach(Jpeg2000Threads.of(pool, 4), 10, () -> i -> {
                        throw new IllegalStateException("runtime");
                    }));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void progressRunsOnTheCallerAndCanStop() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Thread caller = Thread.currentThread();
            AtomicInteger calls = new AtomicInteger(), ran = new AtomicInteger(), last = new AtomicInteger();
            Parallel.forEach(Jpeg2000Threads.of(pool, 4), 100, () -> i -> ran.incrementAndGet(), done -> {
                assertSame(caller, Thread.currentThread());
                calls.incrementAndGet();
                last.set(done);
                return true;
            });
            assertEquals(100, ran.get());
            assertEquals(100, last.get(), "final report counts every item");
            AtomicInteger ran2 = new AtomicInteger();
            Parallel.forEach(Jpeg2000Threads.of(pool, 4), 10_000, () -> i -> {
                ran2.incrementAndGet();
                pause();
            }, done -> false);
            assertTrue(ran2.get() < 10_000, "stopped early: " + ran2.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void threadsSettings() {
        assertSame(Jpeg2000Threads.NONE, Jpeg2000Threads.of(null, 4));
        assertSame(Jpeg2000Threads.NONE, Jpeg2000Threads.of(r -> { }, 1));
        assertThrows(IllegalArgumentException.class, () -> Jpeg2000Threads.of(r -> { }, 0));
        assertTrue(Jpeg2000Threads.defaults().parallelism() >= 1);
    }
}
