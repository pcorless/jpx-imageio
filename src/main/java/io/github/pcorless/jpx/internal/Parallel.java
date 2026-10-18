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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caller-helps parallel loops. Items are claimed from a shared counter by the calling thread and
 * by up to parallelism - 1 helpers submitted to the executor. The caller waits only for items
 * that a helper has already claimed, never for a helper still queued, so a bounded executor the
 * caller itself runs on can't deadlock: with no free worker the caller simply does everything.
 */
public final class Parallel {

    /** Runs items; one instance per participating thread, so it may hold scratch buffers. */
    public interface Worker {
        void run(int item) throws JpxException;
    }

    public interface WorkerFactory {
        Worker newWorker();
    }

    /** Called on the calling thread only, with the number of items done; false stops the loop. */
    public interface Progress {
        boolean done(int items);
    }

    /**
     * Tests only: spread work over threads even below the size thresholds, so small fixtures
     * exercise the parallel paths. Not part of the API (this package isn't exported).
     */
    public static volatile boolean ignoreThresholds;

    private Parallel() {
    }

    /** True when the work is big enough (or {@link #ignoreThresholds}) and threads are available. */
    public static boolean worthIt(Jpeg2000Threads threads, boolean bigEnough) {
        return threads.parallelism() > 1 && (bigEnough || ignoreThresholds);
    }

    public static void forEach(Jpeg2000Threads threads, int n, WorkerFactory factory) throws JpxException {
        forEach(threads, n, factory, null);
    }

    /**
     * Runs items 0..n-1. Returns early, with the remaining items not run, when {@code progress}
     * returns false; rethrows the first exception an item threw (after the running items end).
     */
    public static void forEach(Jpeg2000Threads threads, int n, WorkerFactory factory, Progress progress)
            throws JpxException {
        if (n <= 0) return;
        int helpers = Math.min(threads.parallelism() - 1, n - 1);
        if (helpers <= 0 || threads.executor() == null) {
            Worker w = factory.newWorker();
            for (int i = 0; i < n; i++) {
                w.run(i);
                if (progress != null && !progress.done(i + 1)) return;
            }
            return;
        }
        Loop loop = new Loop(n, factory);
        for (int h = 0; h < helpers; h++) {
            try {
                threads.executor().execute(loop::help);
            } catch (RejectedExecutionException e) {
                break; // the caller does the rest
            }
        }
        Worker w = null;
        boolean aborted = false;
        while (!loop.stop) {
            loop.inFlight.incrementAndGet();
            int i = loop.next.getAndIncrement();
            try {
                if (i >= n) break;
                if (w == null) w = factory.newWorker();
                w.run(i);
                loop.done.incrementAndGet();
            } catch (Throwable t) {
                loop.fail(t);
            } finally {
                loop.leave();
            }
            if (progress != null && !progress.done(loop.done.get())) {
                aborted = true;
                loop.stop = true;
            }
        }
        loop.awaitRunning();
        Throwable t = loop.error.get();
        if (t != null) {
            if (t instanceof JpxException) throw (JpxException) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new IllegalStateException(t);
        }
        if (progress != null && !aborted) progress.done(loop.done.get());
    }

    private static final class Loop {
        final int n;
        final WorkerFactory factory;
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        /** Threads between claiming an item and finishing it (or finding none left). */
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile boolean stop;

        Loop(int n, WorkerFactory factory) {
            this.n = n;
            this.factory = factory;
        }

        void help() {
            Worker w = null;
            while (!stop) {
                inFlight.incrementAndGet();
                try {
                    int i = next.getAndIncrement();
                    if (i >= n) return;
                    if (w == null) w = factory.newWorker();
                    w.run(i);
                    done.incrementAndGet();
                } catch (Throwable t) {
                    fail(t);
                } finally {
                    leave();
                }
            }
        }

        void fail(Throwable t) {
            error.compareAndSet(null, t);
            stop = true;
        }

        void leave() {
            if (inFlight.decrementAndGet() == 0) {
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        /** Waits for the items helpers have claimed; helpers not yet started will find none. */
        synchronized void awaitRunning() {
            stop = true; // nothing new is claimed once the caller is done
            boolean interrupted = false;
            while (inFlight.get() > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
