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
package org.icepdf.jpx;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How a decode may use threads: at most {@link #parallelism()} threads in total, the calling
 * thread included, helpers taken from {@link #executor()}. The calling thread always works too
 * and never waits on a helper that hasn't started, so any executor is safe, including a
 * bounded one the caller itself runs on. Results don't depend on the thread count.
 * <p>
 * The default ({@link #defaults()}) is a shared pool owned by this library, sized by the
 * system property {@value #PARALLELISM_PROPERTY} (default {@code min(available processors, 8)};
 * {@code 1} turns threads off).
 */
public final class Jpeg2000Threads {

    public static final String PARALLELISM_PROPERTY = "org.icepdf.jpx.parallelism";
    /** Upper bound of the default parallelism (DECISIONS.md D-21). */
    static final int DEFAULT_CAP = 8;

    /** The calling thread only. */
    public static final Jpeg2000Threads NONE = new Jpeg2000Threads(null, 1);

    private static volatile Jpeg2000Threads defaults;

    private final Executor executor;
    private final int parallelism;

    private Jpeg2000Threads(Executor executor, int parallelism) {
        this.executor = executor;
        this.parallelism = parallelism;
    }

    /** Up to {@code parallelism} threads in total, the caller included, helpers from {@code executor}. */
    public static Jpeg2000Threads of(Executor executor, int parallelism) {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism " + parallelism);
        return executor == null || parallelism == 1 ? NONE : new Jpeg2000Threads(executor, parallelism);
    }

    /** The process-wide default; built on first use from {@value #PARALLELISM_PROPERTY}. */
    public static Jpeg2000Threads defaults() {
        Jpeg2000Threads d = defaults;
        if (d == null) {
            synchronized (Jpeg2000Threads.class) {
                d = defaults;
                if (d == null) defaults = d = fromProperty();
            }
        }
        return d;
    }

    /** Replaces the default, e.g. with {@link #NONE} on small hardware or with a host's own pool. */
    public static void setDefaults(Jpeg2000Threads threads) {
        if (threads == null) throw new IllegalArgumentException("null threads");
        defaults = threads;
    }

    static int defaultParallelism() {
        int cores = Runtime.getRuntime().availableProcessors();
        int p = Math.min(cores, DEFAULT_CAP);
        String v = System.getProperty(PARALLELISM_PROPERTY);
        if (v != null) {
            try {
                p = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                // keep the default
            }
        }
        return Math.max(1, p);
    }

    private static Jpeg2000Threads fromProperty() {
        int p = defaultParallelism();
        if (p <= 1) return NONE;
        AtomicInteger n = new AtomicInteger();
        // helpers only: the calling thread is the p-th worker
        ForkJoinPool pool = new ForkJoinPool(p - 1, f -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(f);
            t.setName("icepdf-jpx-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, null, false);
        return new Jpeg2000Threads(pool, p);
    }

    /** Where helpers run; null for {@link #NONE}. */
    public Executor executor() {
        return executor;
    }

    public int parallelism() {
        return parallelism;
    }

    @Override
    public String toString() {
        return executor == null ? "Jpeg2000Threads.NONE" : "Jpeg2000Threads(" + parallelism + ")";
    }
}
