package eturlia.core.logging;

/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 *
 * This file is part of the Eturlia project.
 * See https://github.com/ for license details.
 */

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Crash report wrapper that enriches a {@link Throwable} with Folia
 * region-specific context extracted from the crashing thread.
 *
 * <p>Paper/Folia-specific types are represented as {@link Object} placeholders.
 * Integration points are marked with {@code TODO} comments.</p>
 */
public final class RegionContextCrashReport {

    private static final Logger LOGGER = Logger.getLogger("EturliaCrashReport");

    private static final String TIMESTAMP_FMT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    /** Prefixes that identify Folia region threads (checked in order). */
    private static final String[] REGION_PREFIXES = {
            "Region-Ticking-Thread-",
            "Region-",
    };

    // =====================================================================
    // Internal structured data
    // =====================================================================

    /** Ordered map holding every piece of crash context keyed by name. */
    private final Map<String, Object> data;

    /** Parsed stack-trace frames of the crashed throwable (not the handler thread). */
    private final List<String> stackFrames;

    /** Full printable exception text including causes. */
    private final String exceptionText;

    // =====================================================================
    // Construction
    // =====================================================================

    private RegionContextCrashReport(Thread thread, Throwable cause) {
        String threadName = thread.getName();
        long threadId = thread.getId();
        String regionId = extractRegionId(threadName);
        String timestamp = new SimpleDateFormat(TIMESTAMP_FMT).format(new Date());

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();

        // Capture the REAL exception stack, not the crash-handler thread frames.
        this.stackFrames = captureStackFrames(cause);
        this.exceptionText = renderThrowable(cause);

        this.data = new LinkedHashMap<>();
        this.data.put("timestamp",            timestamp);
        this.data.put("threadName",           threadName);
        this.data.put("threadId",             threadId);
        this.data.put("regionId",             regionId);
        this.data.put("exceptionClass",       cause.getClass().getName());
        this.data.put("exceptionMessage",     cause.getMessage());
        this.data.put("jvmMaxMemoryMB",       mb(rt.maxMemory()));
        this.data.put("jvmUsedMemoryMB",      mb(used));
        this.data.put("jvmFreeMemoryMB",      mb(rt.freeMemory()));
        this.data.put("jvmAvailableProcessors", rt.availableProcessors());
        this.data.put("jvmVersion",           System.getProperty("java.version", "unknown"));
        this.data.put("osName",               System.getProperty("os.name", "unknown"));
        this.data.put("osArch",               System.getProperty("os.arch", "unknown"));

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("RegionContextCrashReport created: thread=" + threadName
                    + " region=" + regionId);
        }
    }

    // =====================================================================
    // Static factory
    // =====================================================================

    /**
     * Enriches the given exception with region context from the supplied thread.
     */
    public static RegionContextCrashReport enrich(Thread thread, Throwable cause) {
        return new RegionContextCrashReport(thread, cause);
    }

    /** Convenience overload using {@link Thread#currentThread()}. */
    public static RegionContextCrashReport enrich(Throwable cause) {
        return enrich(Thread.currentThread(), cause);
    }

    // =====================================================================
    // Human-readable output
    // =====================================================================

    public String toHumanReadable() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("---- Eturlia Region-Aware Crash Report ----\n");
        sb.append("Time:        ").append(data.get("timestamp")).append('\n');
        sb.append("Description: ").append(data.get("exceptionClass"));
        Object msg = data.get("exceptionMessage");
        if (msg != null) sb.append(": ").append(msg);
        sb.append("\n\n");

        sb.append("-- Region Context --\n");
        sb.append("  Region ID:   ").append(data.get("regionId")).append('\n');
        sb.append("  Thread Name: ").append(data.get("threadName")).append('\n');
        sb.append("  Thread ID:   ").append(data.get("threadId")).append('\n');
        // TODO: Print owningEntity and affectedChunks when populated at runtime.
        sb.append('\n');

        sb.append("-- JVM Memory --\n");
        sb.append("  Max:  ").append(data.get("jvmMaxMemoryMB")).append(" MB\n");
        sb.append("  Used: ").append(data.get("jvmUsedMemoryMB")).append(" MB\n");
        sb.append("  Free: ").append(data.get("jvmFreeMemoryMB")).append(" MB\n");
        sb.append("  CPUs: ").append(data.get("jvmAvailableProcessors")).append('\n');
        sb.append('\n');

        sb.append("-- Crashed Thread Stack (").append(stackFrames.size())
                .append(" frames) --\n");
        for (String frame : stackFrames) {
            sb.append("  ").append(frame).append('\n');
        }
        sb.append('\n');

        sb.append("-- Exception --\n");
        sb.append(exceptionText);
        if (!exceptionText.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("-- System --\n");
        sb.append("  JVM: ").append(data.get("jvmVersion")).append('\n');
        sb.append("  OS:  ").append(data.get("osName"))
                .append(' ').append(data.get("osArch")).append('\n');
        sb.append('\n');
        sb.append("-- End Eturlia Crash Report --\n");
        return sb.toString();
    }

    // =====================================================================
    // JSON output (hand-built, no Gson/Jackson)
    // =====================================================================

    public String toJsonString() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");

        // Scalar fields
        jsonStr(sb, "timestamp",            str(data.get("timestamp")),        true);
        jsonStr(sb, "threadName",           str(data.get("threadName")),       true);
        jsonNum(sb, "threadId",             data.get("threadId"),              true);
        jsonStr(sb, "regionId",             str(data.get("regionId")),          true);
        jsonStr(sb, "exceptionClass",       str(data.get("exceptionClass")),    true);
        jsonNullable(sb, "exceptionMessage",  str(data.get("exceptionMessage")), true);

        // TODO: Serialise regionObject / affectedChunks / owningEntity when populated.

        jsonNum(sb, "jvmMaxMemoryMB",       data.get("jvmMaxMemoryMB"),        true);
        jsonNum(sb, "jvmUsedMemoryMB",      data.get("jvmUsedMemoryMB"),       true);
        jsonNum(sb, "jvmFreeMemoryMB",      data.get("jvmFreeMemoryMB"),       true);
        jsonNum(sb, "jvmAvailableProcessors", data.get("jvmAvailableProcessors"), true);
        jsonStr(sb, "jvmVersion",           str(data.get("jvmVersion")),       true);
        jsonStr(sb, "osName",               str(data.get("osName")),            true);
        jsonStr(sb, "osArch",               str(data.get("osArch")),            true);

        // stackFrames array
        sb.append("  \"stackFrames\": [\n");
        for (int i = 0; i < stackFrames.size(); i++) {
            sb.append("    \"").append(escapeJson(stackFrames.get(i))).append('"');
            if (i < stackFrames.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");

        sb.append("}\n");
        return sb.toString();
    }

    // =====================================================================
    // Accessors
    // =====================================================================

    // =====================================================================
    // Persistence — dedicated eturlia-crash-reports/ folder
    // =====================================================================

    /**
     * Default directory name for Eturlia region-enriched crash reports.
     * Separate from vanilla/Paper {@code crash-reports/}.
     */
    public static final String DEFAULT_DIR_NAME = "eturlia-crash-reports";

    /**
     * Writes human-readable and JSON reports under {@code dir}.
     * Creates the directory if needed. Filenames include region id.
     *
     * @param dir target directory (typically {@code eturlia-crash-reports})
     * @return the written {@code .txt} file, or {@code null} on failure
     */
    public java.io.File writeToDirectory(java.io.File dir) {
        if (dir == null) {
            dir = new java.io.File(DEFAULT_DIR_NAME);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warning("Could not create Eturlia crash-reports directory: " + dir.getAbsolutePath());
            return null;
        }
        String region = str(data.get("regionId")).replaceAll("[^a-zA-Z0-9._-]", "_");
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        String base = "crash-" + timestamp + "-region-" + region;
        java.io.File txt = new java.io.File(dir, base + ".txt");
        java.io.File json = new java.io.File(dir, base + ".json");
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(txt))) {
            pw.print(toHumanReadable());
        } catch (java.io.IOException e) {
            LOGGER.warning("Failed to write " + txt.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(json))) {
            pw.print(toJsonString());
        } catch (java.io.IOException e) {
            LOGGER.warning("Failed to write " + json.getAbsolutePath() + ": " + e.getMessage());
        }
        LOGGER.info("Eturlia crash report written to " + txt.getAbsolutePath()
                + " (regionId=" + data.get("regionId") + ")");
        return txt;
    }

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    public List<String> getStackFrames() {
        return Collections.unmodifiableList(stackFrames);
    }

    // =====================================================================
    // Region-ID extraction from thread name
    // =====================================================================

    /**
     * Extracts a region identifier from the thread name by matching known
     * Folia region-thread prefixes.
     *
     * <p>TODO: At runtime, obtain the canonical region ID from the
     * {@code RegionizedWorldThread} instead of parsing the thread name.</p>
     */
    static String extractRegionId(String threadName) {
        if (threadName == null) return "non-region";
        for (String prefix : REGION_PREFIXES) {
            if (threadName.startsWith(prefix)) {
                return threadName;
            }
        }
        return "non-region";
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private static List<String> captureStackFrames(Throwable cause) {
        List<String> frames = new ArrayList<>();
        Throwable current = cause;
        while (current != null) {
            if (current != cause) {
                frames.add("Caused by: " + current);
            }
            StackTraceElement[] elements = current.getStackTrace();
            for (StackTraceElement e : elements) {
                frames.add("at " + e);
            }
            current = current.getCause();
        }
        return frames;
    }

    private static String renderThrowable(Throwable cause) {
        StringWriter sw = new StringWriter();
        cause.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String mb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String str(Object v) {
        return v != null ? v.toString() : "null";
    }

    // -- JSON helpers (no external library) --

    private static void jsonStr(StringBuilder sb, String key, String val, boolean comma) {
        sb.append("  \"").append(escapeJson(key)).append("\": \"")
                .append(escapeJson(val)).append('"');
        if (comma) sb.append(',');
        sb.append('\n');
    }

    private static void jsonNullable(StringBuilder sb, String key, String val, boolean comma) {
        sb.append("  \"").append(escapeJson(key)).append("\": ");
        if (val != null) {
            sb.append('"').append(escapeJson(val)).append('"');
        } else {
            sb.append("null");
        }
        if (comma) sb.append(',');
        sb.append('\n');
    }

    private static void jsonNum(StringBuilder sb, String key, Object num, boolean comma) {
        sb.append("  \"").append(escapeJson(key)).append("\": ").append(num);
        if (comma) sb.append(',');
        sb.append('\n');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:   out.append(c);      break;
            }
        }
        return out.toString();
    }
}