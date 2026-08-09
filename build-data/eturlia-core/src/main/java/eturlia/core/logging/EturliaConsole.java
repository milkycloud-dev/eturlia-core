/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Keeps the Eturlia part of the server console readable.
 *
 * <p>Eturlia's own components log through {@code java.util.logging} (the server itself uses
 * Log4j2 and is untouched by this class). By default those records went to the console with
 * the JDK's two-line format, so a handful of harmless warnings — an unknown config key, a
 * reflective lookup that is expected to miss — produced a wall of red before the server had
 * even started.</p>
 *
 * <p>After {@link #install(Path)}:</p>
 * <ul>
 *   <li>Eturlia {@code INFO} and below print as a single tidy line: {@code [Eturlia] message}</li>
 *   <li>Eturlia {@code WARNING}/{@code SEVERE} also stay a single console line —
 *       {@code [Eturlia] WARN message — Exception: detail} — with no stack trace</li>
 *   <li>the full record, stack trace included, is written to {@code logs/eturlia.log}; one
 *       pointer line is printed the first time that happens, and a one-line summary at
 *       shutdown</li>
 * </ul>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code -Deturlia.console.errors=off} — keep warnings/errors out of the console
 *       entirely (they still reach the log file)</li>
 *   <li>{@code -Deturlia.console.color=off} — never emit ANSI colour</li>
 *   <li>{@code -Deturlia.log.file=<path>} — override the log file location</li>
 * </ul>
 *
 * <p>Crash reports are unaffected: a genuine crash still goes to the console and to
 * {@link RegionContextCrashReport}. Only routine diagnostics are moved out of the way.</p>
 */
public final class EturliaConsole {

    /** Records from loggers whose name starts with this are considered ours. */
    private static final String ETURLIA_LOGGER_PREFIX = "Eturlia";

    private static final String PROP_SHOW_ERRORS = "eturlia.console.errors";
    private static final String PROP_COLOR = "eturlia.console.color";
    private static final String PROP_LOG_FILE = "eturlia.log.file";

    private static final String ESC = String.valueOf((char) 27);
    private static final String ANSI_RESET = ESC + "[0m";
    private static final String ANSI_DIM = ESC + "[38;5;245m";
    private static final String ANSI_CYAN = ESC + "[38;5;44m";
    private static final String ANSI_YELLOW = ESC + "[38;5;214m";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean POINTER_PRINTED = new AtomicBoolean();
    private static final AtomicInteger SUPPRESSED = new AtomicInteger();

    private static volatile Path logFile;

    private EturliaConsole() {}

    /**
     * Installs the console filter and the diagnostics log. Never throws — a logging problem
     * must not stop a server from booting.
     *
     * @param gameDir server working directory; the log lands in {@code <gameDir>/logs}
     * @return the diagnostics log path, or {@code null} if it could not be opened
     */
    public static Path install(Path gameDir) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return logFile;
        }
        try {
            Logger root = Logger.getLogger("");

            Path target = resolveLogFile(gameDir);
            if (target != null) {
                Files.createDirectories(target.getParent());
                // Append to a single file with no size limit and no rotation: FileHandler
                // appends a generation number to the name whenever count > 1, which would
                // turn the documented logs/eturlia.log into logs/eturlia.log.0. Only Eturlia
                // diagnostics land here, so the file stays small.
                FileHandler file = new FileHandler(target.toAbsolutePath().toString(),
                        0, 1, true);
                file.setFormatter(new FileFormatter());
                file.setLevel(Level.ALL);
                file.setFilter(EturliaConsole::isEturliaRecord);
                root.addHandler(file);
                logFile = target;
            }

            boolean quiet = quietConsole();
            for (Handler handler : root.getHandlers()) {
                if (!(handler instanceof ConsoleHandler)) {
                    continue;
                }
                handler.setFormatter(new ConsoleFormatter());
                handler.setFilter(record -> {
                    if (!isEturliaRecord(record)) {
                        return true; // not ours — leave the server's own logging alone
                    }
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        noteDiagnostic();
                        return !quiet;
                    }
                    return true;
                });
            }

            Runtime.getRuntime().addShutdownHook(new Thread(EturliaConsole::printSummary,
                    "Eturlia-console-summary"));
        } catch (IOException | RuntimeException e) {
            System.out.println("[Eturlia] Could not install the diagnostics log ("
                    + e + ") — Eturlia messages stay on the console.");
        }
        return logFile;
    }

    /** Diagnostics log path, or {@code null} when it is not in use. */
    public static Path getLogFile() {
        return logFile;
    }

    /** Number of Eturlia warnings/errors kept off the console so far. */
    public static int getSuppressedCount() {
        return SUPPRESSED.get();
    }

    private static boolean isEturliaRecord(LogRecord record) {
        String name = record.getLoggerName();
        return name != null && name.startsWith(ETURLIA_LOGGER_PREFIX);
    }

    // ------------------------------------------------------------------ noise sink
    //
    // EturliaNoiseFilter moves third-party stack traces off the console. They go to their own file
    // rather than through java.util.logging: on this stack JUL can be bridged to log4j, and routing
    // suppressed log4j events back through log4j would recurse. A separate handle also keeps them
    // from interleaving with the JUL FileHandler that owns eturlia.log.

    private static final Object NOISE_LOCK = new Object();
    private static volatile java.io.Writer noiseWriter;
    private static volatile Path noiseFile;
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Path of the suppressed-noise log, or {@code null} before the first write. */
    public static Path getNoiseFile() {
        return noiseFile;
    }

    /** One console line from Eturlia itself (used for install banners). */
    public static void info(String message) {
        System.out.println(dim() + "[Eturlia] " + message + reset());
    }

    /** Appends a single suppressed line (typically one stack frame). */
    public static void appendDiagnostic(String logger, String line) {
        write(logger, line, null);
    }

    /** Appends a suppressed message together with its full stack trace. */
    public static void appendThrowable(String logger, String message, Throwable thrown) {
        write(logger, message, thrown);
    }

    private static void write(String logger, String message, Throwable thrown) {
        if (Boolean.TRUE.equals(INSIDE.get())) {
            return; // a logging failure must not re-enter the filter that called us
        }
        INSIDE.set(Boolean.TRUE);
        try {
            java.io.Writer w = writer();
            if (w == null) {
                return;
            }
            StringBuilder sb = new StringBuilder(256);
            sb.append(TIMESTAMP.format(java.time.LocalDateTime.now())).append(' ');
            if (logger != null && !logger.isEmpty()) {
                sb.append('[').append(logger).append("] ");
            }
            if (message != null) {
                sb.append(message);
            }
            sb.append(System.lineSeparator());
            if (thrown != null) {
                java.io.StringWriter trace = new java.io.StringWriter();
                thrown.printStackTrace(new java.io.PrintWriter(trace));
                sb.append(trace);
            }
            synchronized (NOISE_LOCK) {
                w.write(sb.toString());
                w.flush();
            }
        } catch (IOException | RuntimeException ignored) {
            // Dropping a suppressed line is strictly better than failing the caller's log call.
        } finally {
            INSIDE.set(Boolean.FALSE);
        }
    }

    private static java.io.Writer writer() throws IOException {
        java.io.Writer w = noiseWriter;
        if (w != null) {
            return w;
        }
        synchronized (NOISE_LOCK) {
            if (noiseWriter == null) {
                Path base = logFile != null
                        ? logFile.getParent()
                        : Path.of("logs").toAbsolutePath().normalize();
                Files.createDirectories(base);
                Path target = base.resolve("eturlia-noise.log");
                noiseWriter = Files.newBufferedWriter(target,
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                noiseFile = target;
            }
            return noiseWriter;
        }
    }

    /** DateTimeFormatter, not SimpleDateFormat: this is written from every server thread. */
    private static final java.time.format.DateTimeFormatter TIMESTAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    private static void noteDiagnostic() {
        SUPPRESSED.incrementAndGet();
        if (logFile != null && POINTER_PRINTED.compareAndSet(false, true)) {
            System.out.println(dim() + "[Eturlia] full diagnostics (with stack traces) go to "
                    + logFile + reset());
        }
    }

    private static void printSummary() {
        int count = SUPPRESSED.get();
        if (count > 0 && logFile != null) {
            System.out.println(dim() + "[Eturlia] " + count
                    + (count == 1 ? " diagnostic message was" : " diagnostic messages were")
                    + " written to " + logFile + reset());
        }
    }

    private static Path resolveLogFile(Path gameDir) {
        String configured = System.getProperty(PROP_LOG_FILE);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        Path base = gameDir != null ? gameDir : Path.of(".");
        return base.toAbsolutePath().normalize().resolve("logs").resolve("eturlia.log");
    }

    /** {@code -Deturlia.console.errors=off} hides warnings from the console entirely. */
    private static boolean quietConsole() {
        String value = System.getProperty(PROP_SHOW_ERRORS);
        return value != null
                && ("off".equalsIgnoreCase(value.trim()) || "quiet".equalsIgnoreCase(value.trim()));
    }

    static boolean useColour() {
        String configured = System.getProperty(PROP_COLOR);
        if (configured != null) {
            return !"off".equalsIgnoreCase(configured.trim());
        }
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return System.console() != null;
    }

    private static String dim() {
        return useColour() ? ANSI_DIM : "";
    }

    private static String reset() {
        return useColour() ? ANSI_RESET : "";
    }

    /**
     * Exactly one line per record: {@code [Eturlia] message}, or
     * {@code [Eturlia] WARN message — <exception>} when something was thrown.
     *
     * <p>No timestamp (the server prefixes its own) and never a stack trace: multi-line dumps
     * are what made routine warnings unreadable. The full record, stack trace included, is in
     * the diagnostics log.</p>
     */
    private static final class ConsoleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String message = formatMessage(record);
            // Components already prefix some messages with "[Eturlia]"; do not double it.
            String body = message.startsWith("[Eturlia]")
                    ? message.substring("[Eturlia]".length()).trim()
                    : message;
            // Collapse embedded newlines: one record must stay one console line.
            body = body.replace('\n', ' ').replace('\r', ' ');

            int level = record.getLevel().intValue();
            String tag = level >= Level.SEVERE.intValue() ? "ERROR "
                    : level >= Level.WARNING.intValue() ? "WARN " : "";

            Throwable thrown = record.getThrown();
            String cause = "";
            if (thrown != null) {
                String detail = thrown.getMessage();
                cause = " — " + thrown.getClass().getSimpleName()
                        + (detail != null && !detail.isBlank() ? ": " + detail.split("\\R", 2)[0] : "");
            }

            boolean colour = useColour();
            StringBuilder sb = new StringBuilder(body.length() + 48);
            if (colour) {
                sb.append(level >= Level.WARNING.intValue() ? ANSI_YELLOW : ANSI_CYAN);
            }
            sb.append("[Eturlia] ").append(tag).append(body).append(cause);
            if (colour) {
                sb.append(ANSI_RESET);
            }
            sb.append(System.lineSeparator());
            return sb.toString();
        }
    }

    /** Full detail for the file: timestamp, level, logger, message and stack trace. */
    private static final class FileFormatter extends Formatter {
        private final java.text.SimpleDateFormat stamp =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

        @Override
        public synchronized String format(LogRecord record) {
            StringBuilder sb = new StringBuilder(256);
            sb.append(stamp.format(new java.util.Date(record.getMillis())))
                    .append(" [").append(record.getLevel().getName()).append("] ")
                    .append(record.getLoggerName()).append(": ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                thrown.printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
