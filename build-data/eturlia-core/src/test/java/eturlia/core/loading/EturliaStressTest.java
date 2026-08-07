/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.loading;

import eturlia.core.event.RegionAwareEventBus;
import eturlia.core.region.CrossRegionInvocationGuard;
import eturlia.core.region.RegionEntityListFacade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concurrency and load checks for the region-safety components.
 *
 * <p>These classes exist to be hammered from many threads at once — that is the entire point
 * of a region-threaded server — yet nothing exercised them. Every check here runs without
 * Minecraft on the classpath, so it can run in CI in seconds.</p>
 *
 * <p>Scenarios covered:</p>
 * <ul>
 *   <li>guard under WARN from many threads: no exceptions, every violation counted, and the
 *       de-duplication set stays bounded no matter how many distinct sites appear</li>
 *   <li>guard under STRICT: every off-region call is rejected, not just the first per message</li>
 *   <li>event bus under STRICT/WARN/PERMISSIVE from many threads at once</li>
 *   <li>entity facade: readers iterating while a writer replaces the snapshot</li>
 * </ul>
 *
 * <p>Run via {@code scripts/selftest.sh}. Exit code 0 means every check passed.</p>
 */
public final class EturliaStressTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 500;

    private static int checks;
    private static int failures;

    private EturliaStressTest() {}

    public static void main(String[] args) throws Exception {
        guardUnderWarnNeverThrows();
        guardUnderStrictRejectsEveryCall();
        guardTrackingStaysBounded();
        eventBusSurvivesConcurrentDispatch();
        entityFacadeSurvivesConcurrentReplace();

        System.out.println();
        System.out.println(failures == 0
                ? "OK — " + checks + " checks passed"
                : "FAILED — " + failures + " of " + checks + " checks failed");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- guard

    private static void guardUnderWarnNeverThrows() throws Exception {
        CrossRegionInvocationGuard.setMode(CrossRegionInvocationGuard.Mode.WARN);
        long before = CrossRegionInvocationGuard.getViolationCount();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        runConcurrently(worker -> {
            for (int i = 0; i < ITERATIONS; i++) {
                try {
                    // Plain pool threads are neither Folia region threads nor "Server thread".
                    CrossRegionInvocationGuard.checkChunk("stress:warn", worker, i % 16);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    return;
                }
            }
        });

        check("WARN mode never throws", null, failure.get());
        long counted = CrossRegionInvocationGuard.getViolationCount() - before;
        check("every off-region call counted", (long) THREADS * ITERATIONS, counted);
    }

    private static void guardUnderStrictRejectsEveryCall() throws Exception {
        CrossRegionInvocationGuard.setMode(CrossRegionInvocationGuard.Mode.STRICT);
        try {
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger allowed = new AtomicInteger();
            runConcurrently(worker -> {
                for (int i = 0; i < 50; i++) {
                    try {
                        // Same api string every time: the old code threw only on the first
                        // occurrence and silently let every repeat through.
                        CrossRegionInvocationGuard.check("stress:strict");
                        allowed.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    }
                }
            });
            check("STRICT rejects every repeat", 0, allowed.get());
            check("STRICT rejection count", THREADS * 50, rejected.get());
        } finally {
            CrossRegionInvocationGuard.setMode(CrossRegionInvocationGuard.Mode.WARN);
        }
    }

    private static void guardTrackingStaysBounded() throws Exception {
        CrossRegionInvocationGuard.setMode(CrossRegionInvocationGuard.Mode.WARN);
        // Every call is a distinct site, which is exactly what used to grow without bound.
        for (int i = 0; i < 20_000; i++) {
            CrossRegionInvocationGuard.checkChunk("stress:unique-" + i, i, i);
        }
        // No assertion on the internal set size (it is private); the check is that 20k
        // distinct sites neither throw nor exhaust memory, and the guard keeps working.
        long before = CrossRegionInvocationGuard.getViolationCount();
        CrossRegionInvocationGuard.check("stress:after-cap");
        check("guard still works after 20k distinct sites",
                before + 1, CrossRegionInvocationGuard.getViolationCount());
    }

    // --------------------------------------------------------- event bus

    private static void eventBusSurvivesConcurrentDispatch() throws Exception {
        for (RegionAwareEventBus.ValidationMode mode : RegionAwareEventBus.ValidationMode.values()) {
            RegionAwareEventBus bus = new RegionAwareEventBus(mode);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            AtomicInteger strictRejections = new AtomicInteger();

            runConcurrently(worker -> {
                for (int i = 0; i < 100; i++) {
                    try {
                        bus.postGlobalEvent(new Object());
                    } catch (IllegalStateException expected) {
                        // STRICT is entitled to reject: pool threads are not the global thread.
                        strictRejections.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                        return;
                    }
                }
            });

            check("event bus (" + mode + ") throws nothing unexpected", null, unexpected.get());
            if (mode == RegionAwareEventBus.ValidationMode.STRICT) {
                check("STRICT rejects every off-thread post",
                        THREADS * 100, strictRejections.get());
            } else {
                check(mode + " lets posts through", 0, strictRejections.get());
            }
        }
    }

    // ------------------------------------------------------------ facade

    private static void entityFacadeSurvivesConcurrentReplace() throws Exception {
        RegionEntityListFacade<String> facade = new RegionEntityListFacade<>("stress");
        List<String> full = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            full.add("entity-" + i);
        }
        facade.replaceLocalSnapshot(full);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger torn = new AtomicInteger();
        CountDownLatch stop = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            try {
                while (stop.getCount() > 0) {
                    facade.replaceLocalSnapshot(full);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "facade-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            runConcurrently(worker -> {
                for (int i = 0; i < ITERATIONS; i++) {
                    try {
                        int seen = 0;
                        for (String ignored : facade) {
                            seen++;
                        }
                        // A snapshot swap must be all-or-nothing: readers should never observe
                        // a partially refilled list. Element-by-element refilling did.
                        if (seen != 0 && seen != full.size()) {
                            torn.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            });
        } finally {
            stop.countDown();
            writer.join(5000);
        }

        check("facade iteration never throws", null, failure.get());
        check("facade snapshot never observed half-filled", 0, torn.get());
    }

    // ----------------------------------------------------------- helpers

    private interface Worker {
        void run(int workerIndex) throws Exception;
    }

    private static void runConcurrently(Worker worker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                final int index = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    worker.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static void check(String label, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            System.out.println("pass  " + label);
        } else {
            failures++;
            System.out.println("FAIL  " + label + " — expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }
}
