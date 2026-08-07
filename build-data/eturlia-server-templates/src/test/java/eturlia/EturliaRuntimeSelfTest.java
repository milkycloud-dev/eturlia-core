/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Boots the Eturlia runtime the way the launch handler does, without Minecraft.
 *
 * <p>{@code EturliaServer.installRuntime} is what the launch handler now calls before handing
 * control to CraftBukkit: it prints the banner, installs the quiet console and diagnostics
 * log, the region-aware event bus and the crash handler. None of that needs a server, so it
 * can be exercised for real here — the closest thing to a dev-mode boot that runs in seconds.</p>
 *
 * <p>Checks: the banner renders (and stays plain when colour is off), Eturlia warnings go to
 * {@code logs/eturlia.log} instead of the console, {@code INFO} still reaches the console as a
 * single tidy line, a second install is a no-op, and FML argument stripping behaves.</p>
 *
 * <p>Console output captured during the run is echoed at the end so a human can eyeball the
 * startup screen. Run via {@code scripts/selftest.sh}.</p>
 */
public final class EturliaRuntimeSelfTest {

    private static int checks;
    private static int failures;
    private static PrintStream realOut;

    private EturliaRuntimeSelfTest() {}

    public static void main(String[] args) throws Exception {
        realOut = System.out;

        // Colour off so the assertions look at plain text; the colour path is covered by
        // asserting that no escape byte appears here.
        System.setProperty("eturlia.console.color", "off");

        Path gameDir = Files.createTempDirectory("eturlia-runtime-selftest");
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        String console;
        try {
            // Both streams must be replaced before the first log record: JUL's ConsoleHandler
            // captures System.err when it is constructed.
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));

            EturliaServer first = EturliaServer.installRuntime(gameDir, "1.21.1", "21.1.248");
            EturliaServer second = EturliaServer.installRuntime(gameDir, "1.21.1", "21.1.248");

            Logger logger = Logger.getLogger("EturliaSelfTestComponent");
            logger.info("visible status line");
            logger.warning("noisy warning that must not reach the console");
            logger.log(Level.SEVERE, "failure with a stack trace",
                    new IllegalStateException("boom"));

            flushLogHandlers();

            console = outBuf.toString(StandardCharsets.UTF_8)
                    + errBuf.toString(StandardCharsets.UTF_8);

            checkSame("installRuntime is idempotent", first, second);
        } finally {
            System.setOut(realOut);
            System.setErr(realOut);
        }

        // ---- banner -------------------------------------------------------
        checkTrue("banner renders the E of ETURLIA", console.contains("███████╗"));
        checkTrue("banner names the project", console.contains("Eturlia v"));
        checkTrue("banner reports the Minecraft version", console.contains("1.21.1"));
        checkTrue("banner reports the NeoForge version", console.contains("21.1.248"));
        checkTrue("banner reports the JVM", console.contains("Java"));
        checkTrue("banner reports the heap", console.contains("Max heap"));
        checkTrue("no ANSI escapes with eturlia.console.color=off",
                console.indexOf((char) 27) < 0);
        checkEquals("banner printed exactly once", 1, countOccurrences(console, "Eturlia v"));

        // ---- console routing ----------------------------------------------
        checkTrue("INFO reaches the console", console.contains("visible status line"));
        checkTrue("INFO is a single tidy line", console.contains("[Eturlia] visible status line"));
        checkTrue("WARNING stays off the console",
                !console.contains("noisy warning that must not reach the console"));
        checkTrue("SEVERE stays off the console",
                !console.contains("failure with a stack trace"));
        checkTrue("console points at the diagnostics log",
                console.contains("diagnostics are being written to"));

        // ---- diagnostics log ----------------------------------------------
        Path log = gameDir.resolve("logs").resolve("eturlia.log");
        checkTrue("diagnostics log created", Files.isRegularFile(log));
        String logText = Files.isRegularFile(log)
                ? Files.readString(log, StandardCharsets.UTF_8)
                : "";
        checkTrue("WARNING written to the log",
                logText.contains("noisy warning that must not reach the console"));
        checkTrue("SEVERE written to the log", logText.contains("failure with a stack trace"));
        checkTrue("stack trace written to the log",
                logText.contains("java.lang.IllegalStateException: boom"));
        checkTrue("log records the level", logText.contains("[WARNING]"));

        // ---- argument handling ---------------------------------------------
        String[] stripped = EturliaServer.stripFmlArgs(new String[]{
                "--nogui", "--fml.mcVersion", "1.21.1", "--world", "world", "--fml.forgeGroup"});
        checkEquals("fml flags stripped", "[--nogui, --world, world]",
                java.util.Arrays.toString(stripped));

        deleteRecursively(gameDir);

        realOut.println();
        realOut.println("---- captured startup console ----");
        realOut.println(console.strip());
        realOut.println("---- end captured console ----");
        realOut.println();
        realOut.println(failures == 0
                ? "OK — " + checks + " checks passed"
                : "FAILED — " + failures + " of " + checks + " checks failed");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void flushLogHandlers() {
        for (java.util.logging.Handler handler : Logger.getLogger("").getHandlers()) {
            handler.flush();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private static void checkTrue(String label, boolean condition) {
        checkEquals(label, Boolean.TRUE, condition);
    }

    private static void checkSame(String label, Object expected, Object actual) {
        checks++;
        if (expected == actual) {
            realOut.println("pass  " + label);
        } else {
            failures++;
            realOut.println("FAIL  " + label);
        }
    }

    private static void checkEquals(String label, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            realOut.println("pass  " + label);
        } else {
            failures++;
            realOut.println("FAIL  " + label + " — expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }
}
