/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.region;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    public enum Mode {
        STRICT, WARN, OFF;

        static Mode fromProperty() {
            String raw = System.getProperty(PROP, "WARN").trim().toUpperCase();
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
     * Returns true if the current thread looks like a Folia region/global tick thread.
     */
    public static boolean isRegionOrGlobalThread() {
        String name = Thread.currentThread().getName();
        return name.contains("Region")
                || "Server thread".equals(name)
                || name.contains("Server");
    }

    /**
     * Validates that the caller may touch world/entity state.
     *
     * @param api short label of the API being entered (for logs)
     * @return {@code true} if the call may proceed
     */
    public static boolean check(String api) {
        if (mode == Mode.OFF) {
            return true;
        }
        if (isRegionOrGlobalThread()) {
            return true;
        }
        String key = Thread.currentThread().getName() + " -> " + api;
        if (SEEN.add(key)) {
            VIOLATIONS.incrementAndGet();
            String msg = "[Eturlia] Cross-region/off-tick call: " + key;
            if (mode == Mode.STRICT) {
                throw new IllegalStateException(msg);
            }
            LOGGER.log(Level.WARNING, msg);
        }
        return mode != Mode.STRICT;
    }

    /**
     * Same as {@link #check(String)} but attaches an optional spatial hint for crash reports.
     */
    public static boolean checkChunk(String api, int chunkX, int chunkZ) {
        return check(api + " @chunk[" + chunkX + "," + chunkZ + "]");
    }
}
