/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.region;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Guards against unsafe cross-region invocations on Folia.
 *
 * <p>Mods written for single-threaded NeoForge often call into world/entity
 * state from the wrong region thread. This guard records and optionally
 * rejects those calls so Create-style load can be diagnosed without silent
 * world corruption.</p>
 *
 * <p>Configure with:</p>
 * <ul>
 *   <li>{@code eturlia.region.guard=STRICT|WARN|OFF} (default {@code WARN})</li>
 * </ul>
 */
public final class CrossRegionInvocationGuard {

    private static final Logger LOGGER = Logger.getLogger("EturliaRegionGuard");
    private static final String PROP = "eturlia.region.guard";

    /**
     * Upper bound for the de-duplication set. Keys embed thread names and chunk
     * coordinates, so an unbounded set is an unbounded leak on a long-running server.
     */
    private static final int MAX_TRACKED_KEYS = 4096;

    /** Folia's tick-thread base class; used instead of thread-name guessing when present. */
    private static final String TICK_THREAD_CLASS = "ca.spottedleaf.moonrise.common.util.TickThread";

    /** Folia's regionized world thread; present on real Folia builds. */
    private static final String REGIONIZED_WORLD_THREAD_CLASS =
            "io.papermc.paper.threadedregions.RegionizedWorldThread";

    public enum Mode {
        STRICT, WARN, OFF;

        static Mode fromProperty() {
            String raw = System.getProperty(PROP, "WARN").trim().toUpperCase(Locale.ROOT);
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Unknown " + PROP + "=" + raw + ", defaulting to WARN");
                return WARN;
            }
        }
    }

    private static volatile Mode mode = Mode.fromProperty();
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicLong VIOLATIONS = new AtomicLong();
    private static final AtomicBoolean TRACKING_CAPPED = new AtomicBoolean();

    // Resolved once; null when the class is not on the classpath (non-Folia runtime).
    private static volatile Class<?> tickThreadClass;
    private static volatile Class<?> regionizedWorldThreadClass;
    private static final AtomicBoolean FOLIA_LOOKUP_STARTED = new AtomicBoolean();
    private static volatile boolean foliaLookupDone;

    private CrossRegionInvocationGuard() {}

    public static Mode getMode() {
        return mode;
    }

    public static void setMode(Mode newMode) {
        mode = Objects.requireNonNull(newMode, "mode");
    }

    public static long getViolationCount() {
        return VIOLATIONS.get();
    }

    /**
     * Returns true if the current thread is a Folia region/global tick thread.
     *
     * <p>On a real Folia runtime this is an exact {@code instanceof} check. Only when
     * the Folia classes are missing entirely (plain Paper, unit tests) does it fall back
     * to the exact vanilla thread name. It deliberately does <em>not</em> accept any
     * thread whose name merely contains {@code "Server"} — that matched Netty IO threads,
     * the console handler and the watchdog, i.e. exactly the callers this guard exists
     * to catch.</p>
     */
    public static boolean isRegionOrGlobalThread() {
        ensureFoliaClassesResolved();

        Thread current = Thread.currentThread();

        Class<?> regionized = regionizedWorldThreadClass;
        if (regionized != null && regionized.isInstance(current)) {
            return true;
        }
        Class<?> tickThread = tickThreadClass;
        if (tickThread != null && tickThread.isInstance(current)) {
            return true;
        }
        if (regionized != null || tickThread != null) {
            // Folia is present and the thread is not one of its tick threads.
            return false;
        }

        // Non-Folia fallback: vanilla / Paper single-threaded server.
        return "Server thread".equals(current.getName());
    }

    private static void ensureFoliaClassesResolved() {
        if (foliaLookupDone) {
            return;
        }
        if (FOLIA_LOOKUP_STARTED.compareAndSet(false, true)) {
            regionizedWorldThreadClass = tryLoad(REGIONIZED_WORLD_THREAD_CLASS);
            tickThreadClass = tryLoad(TICK_THREAD_CLASS);
            foliaLookupDone = true;
        }
    }

    private static Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, CrossRegionInvocationGuard.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Validates that the caller may touch world/entity state.
     *
     * @param api short label of the API being entered (for logs)
     * @return {@code true} if the call may proceed
     */
    public static boolean check(String api) {
        Mode current = mode;
        if (current == Mode.OFF) {
            return true;
        }
        if (isRegionOrGlobalThread()) {
            return true;
        }

        VIOLATIONS.incrementAndGet();
        String key = Thread.currentThread().getName() + " -> " + api;
        String msg = "[Eturlia] Cross-region/off-tick call: " + key;

        // STRICT always rejects — previously only the first occurrence of each key threw
        // and every repeat was silently swallowed.
        if (current == Mode.STRICT) {
            if (shouldReport(key)) {
                LOGGER.log(Level.SEVERE, msg);
            }
            throw new IllegalStateException(msg);
        }

        if (shouldReport(key)) {
            LOGGER.log(Level.WARNING, msg);
        }
        return true;
    }

    /** Log de-duplication with a hard cap so the tracking set cannot grow without bound. */
    private static boolean shouldReport(String key) {
        if (SEEN.contains(key)) {
            return false;
        }
        if (SEEN.size() >= MAX_TRACKED_KEYS) {
            if (TRACKING_CAPPED.compareAndSet(false, true)) {
                LOGGER.warning("[Eturlia] Cross-region violation tracking capped at "
                        + MAX_TRACKED_KEYS + " distinct sites; further sites are logged"
                        + " without de-duplication suppression state.");
            }
            return true;
        }
        return SEEN.add(key);
    }

    /**
     * Same as {@link #check(String)} but attaches an optional spatial hint for crash reports.
     */
    public static boolean checkChunk(String api, int chunkX, int chunkZ) {
        return check(api + " @chunk[" + chunkX + "," + chunkZ + "]");
    }
}
