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
 *   <li>Eturlia {@code WARNING}/{@code SEVERE} do <b>not</b> go to the console; they are written
 *       in full — stack traces included — to {@code logs/eturlia.log}</li>
 *   <li>One pointer line is printed the first time something is written there, and a one-line
 *       summary at shutdown</li>
 * </ul>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code -Deturlia.console.errors=show} — also print warnings/errors to the console</li>
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

            boolean showErrors = showErrorsOnConsole();
            for (Handler handler : root.getHandlers()) {
                if (!(handler instanceof ConsoleHandler)) {
                    continue;
                }
                handler.setFormatter(new ConsoleFormatter());
                handler.setFilter(record -> {
                    if (!isEturliaRecord(record)) {
                        return true; // not ours — leave the server's own logging alone
                    }
                    if (!showErrors && record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        noteSuppressed();
                        return false;
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

    private static void noteSuppressed() {
        SUPPRESSED.incrementAndGet();
        if (logFile != null && POINTER_PRINTED.compareAndSet(false, true)) {
            System.out.println(dim() + "[Eturlia] diagnostics are being written to "
                    + logFile + " (console stays quiet; -D" + PROP_SHOW_ERRORS
                    + "=show to see them here)" + reset());
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

    private static boolean showErrorsOnConsole() {
        String value = System.getProperty(PROP_SHOW_ERRORS);
        return value != null && "show".equalsIgnoreCase(value.trim());
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

    /** One line per record: {@code [Eturlia] message}. No timestamps — the server adds its own. */
    private static final class ConsoleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String message = formatMessage(record);
            // Components already prefix some messages with "[Eturlia]"; do not double it.
            String prefix = message.startsWith("[Eturlia]") ? "" : "[Eturlia] ";
            boolean colour = useColour();
            StringBuilder sb = new StringBuilder(message.length() + 32);
            if (colour) {
                sb.append(ANSI_CYAN);
            }
            sb.append(prefix).append(message);
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
