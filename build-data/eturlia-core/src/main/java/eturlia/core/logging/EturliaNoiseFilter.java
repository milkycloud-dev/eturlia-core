/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the console readable by sending stack-trace spam to {@code logs/eturlia-noise.log}.
 *
 * <h2>What this fixes</h2>
 *
 * <p>A boot of the ASIC pack produced 4463 {@code IllegalArgumentException: Whilst parsing command
 * …} traces from mod datapack functions, plus Folia watchdog dumps in which <em>every stack frame is
 * its own ERROR record</em>. The console filled with red and nothing readable survived, while the
 * information an operator needs — which datapack, which region — was one line in every forty.</p>
 *
 * <h2>What it does</h2>
 *
 * <p>Installed on Paper's console appender only, so files and other appenders keep the complete
 * text. For each event it decides:</p>
 *
 * <ul>
 *   <li><b>stack-frame line</b> ({@code at pkg.Cls.m(Cls.java:1)}, {@code TRANSFORMER/…},
 *       {@code java.base@21/…}, {@code Caused by:}, {@code … 12 more}) — dropped from the console,
 *       counted, forwarded to the diagnostics log;</li>
 *   <li><b>event carrying a throwable</b> — the message and the exception's first line stay on the
 *       console; the trace itself goes to the diagnostics log;</li>
 *   <li><b>anything else</b> — untouched.</li>
 * </ul>
 *
 * <p>A first line is never suppressed: whatever went wrong is still visible, with a pointer to the
 * file holding the rest. Disable with {@code -Deturlia.console.noise=off}.</p>
 */
public final class EturliaNoiseFilter extends AbstractFilter {

    private static final String PROP_MODE = "eturlia.console.noise";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong SUPPRESSED_FRAMES = new AtomicLong();
    private static final AtomicLong SUPPRESSED_TRACES = new AtomicLong();
    private static final AtomicBoolean POINTER_SHOWN = new AtomicBoolean();

    private EturliaNoiseFilter() {
        super(Result.NEUTRAL, Result.NEUTRAL);
    }

    /**
     * Attaches the filter to every console appender in the active log4j configuration.
     *
     * <p>Never throws: a logging problem must not stop a server from booting.</p>
     *
     * @return {@code true} when at least one console appender was wrapped
     */
    public static boolean install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return false;
        }
        if ("off".equalsIgnoreCase(System.getProperty(PROP_MODE, "on"))) {
            return false;
        }
        int wrapped = 0;
        java.util.logging.Logger diag = java.util.logging.Logger.getLogger("EturliaNoise");
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            EturliaNoiseFilter filter = new EturliaNoiseFilter();
            filter.start();

            // Attach to every LoggerConfig that owns a console appender. Paper's console is a
            // TerminalConsoleAppender declared on the root logger, and a Logger instance only
            // exists here if something already requested it — so walk the configuration, which
            // always has the full picture.
            org.apache.logging.log4j.core.config.Configuration cfg = ctx.getConfiguration();
            StringBuilder seen = new StringBuilder();
            for (Map.Entry<String, Appender> entry : cfg.getAppenders().entrySet()) {
                seen.append(entry.getKey()).append('=')
                        .append(entry.getValue().getClass().getSimpleName()).append(' ');
            }
            for (org.apache.logging.log4j.core.config.LoggerConfig lc : cfg.getLoggers().values()) {
                for (Appender appender : lc.getAppenders().values()) {
                    if (isConsole(appender)) {
                        lc.addFilter(filter);
                        wrapped++;
                        break;
                    }
                }
            }
            if (wrapped == 0) {
                // Root config may reference the appender without owning it in its own map.
                for (Appender appender : cfg.getAppenders().values()) {
                    if (isConsole(appender)) {
                        cfg.getRootLogger().addFilter(filter);
                        wrapped++;
                        break;
                    }
                }
            }
            ctx.updateLoggers();

            diag.info("Console noise filter: wrapped=" + wrapped + " appenders[" + seen.toString().trim() + "]");
            if (wrapped > 0) {
                EturliaConsole.info("Console noise filter active — stack traces go to "
                        + describeLog() + " (disable with -D" + PROP_MODE + "=off)");
            } else {
                diag.warning("Console noise filter found no console appender — traces stay on the console.");
            }
        } catch (RuntimeException | LinkageError e) {
            diag.warning("Console noise filter unavailable (" + e + ") — full stack traces stay on the console.");
        }
        return wrapped > 0;
    }

    private static boolean isConsole(Appender appender) {
        String cls = appender.getClass().getName().toLowerCase(Locale.ROOT);
        String name = String.valueOf(appender.getName()).toLowerCase(Locale.ROOT);
        return cls.contains("console") || cls.contains("terminal")
                || name.contains("console") || name.contains("terminal");
    }

    private static String describeLog() {
        return EturliaConsole.getNoiseFile() != null
                ? EturliaConsole.getNoiseFile().toString()
                : "logs/eturlia-noise.log";
    }

    /** Frames dropped from the console so far. */
    public static long suppressedFrames() {
        return SUPPRESSED_FRAMES.get();
    }

    /** Throwables whose trace was moved off the console so far. */
    public static long suppressedTraces() {
        return SUPPRESSED_TRACES.get();
    }

    // ------------------------------------------------------------------ filter implementation

    @Override
    public Result filter(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        Message msg = event.getMessage();
        String text = msg == null ? null : msg.getFormattedMessage();

        if (isStackFrameLine(text)) {
            SUPPRESSED_FRAMES.incrementAndGet();
            EturliaConsole.appendDiagnostic(event.getLoggerName(), text);
            notePointer();
            return Result.DENY;
        }

        Throwable thrown = event.getThrown();
        if (thrown != null) {
            SUPPRESSED_TRACES.incrementAndGet();
            EturliaConsole.appendThrowable(event.getLoggerName(), text, thrown);
            notePointer();
            // The first line still reaches the console through the summary below.
            printSummaryLine(event.getLevel(), event.getLoggerName(), text, thrown);
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return isStackFrameLine(msg) ? countedDeny(logger, msg) : Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return filterRaw(logger, msg == null ? null : String.valueOf(msg), t);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return filterRaw(logger, msg == null ? null : msg.getFormattedMessage(), t);
    }

    private Result filterRaw(Logger logger, String text, Throwable t) {
        if (isStackFrameLine(text)) {
            return countedDeny(logger, text);
        }
        if (t != null) {
            SUPPRESSED_TRACES.incrementAndGet();
            EturliaConsole.appendThrowable(name(logger), text, t);
            notePointer();
            printSummaryLine(Level.ERROR, name(logger), text, t);
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    private Result countedDeny(Logger logger, String text) {
        SUPPRESSED_FRAMES.incrementAndGet();
        EturliaConsole.appendDiagnostic(name(logger), text);
        notePointer();
        return Result.DENY;
    }

    private static String name(Logger logger) {
        return logger == null ? "" : logger.getName();
    }

    /**
     * Recognises a line that only makes sense as part of a stack trace.
     *
     * <p>Folia's watchdog logs thread dumps one frame per record, so these arrive as ordinary
     * messages with no throwable attached and cannot be spotted any other way.</p>
     */
    static boolean isStackFrameLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String trimmed = text.stripLeading();
        if (trimmed.length() == text.length() && !trimmed.startsWith("Caused by:")) {
            // Unindented lines are first lines — never suppress those.
            return false;
        }
        return trimmed.startsWith("at ")
                || trimmed.startsWith("Caused by:")
                || trimmed.startsWith("Suppressed:")
                || trimmed.startsWith("... ") && trimmed.endsWith("more")
                || trimmed.startsWith("TRANSFORMER/")
                || trimmed.contains("java.base@")
                || trimmed.contains("$$Lambda")
                || trimmed.startsWith("PID:")
                || trimmed.startsWith("Stack:");
    }

    /** Prints one readable line in place of a full trace. */
    private static void printSummaryLine(Level level, String logger, String text, Throwable t) {
        StringBuilder sb = new StringBuilder("[Eturlia] ");
        sb.append(level == null ? "ERROR" : level.name()).append(' ');
        if (logger != null && !logger.isEmpty()) {
            int dot = logger.lastIndexOf('.');
            sb.append(dot >= 0 ? logger.substring(dot + 1) : logger).append(": ");
        }
        if (text != null && !text.isBlank()) {
            sb.append(oneLine(text));
            sb.append(" — ");
        }
        sb.append(t.getClass().getSimpleName());
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
            sb.append(": ").append(oneLine(t.getMessage()));
        }
        System.out.println(sb);
    }

    private static String oneLine(String s) {
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() > 200 ? flat.substring(0, 197) + "..." : flat;
    }

    private static void notePointer() {
        if (POINTER_SHOWN.compareAndSet(false, true)) {
            System.out.println("[Eturlia] Full stack traces are being written to "
                    + describeLog() + " instead of the console.");
        }
    }
}
