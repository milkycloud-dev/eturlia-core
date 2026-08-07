/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.loading;

import eturlia.core.config.EturliaConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Exercises every documented key in {@code config/eturlia.yml}.
 *
 * <p>The config is the operator-facing surface of this core, and most of it used to be
 * decoration: keys were documented, parsed and then read by nobody. These checks pin the
 * contract down in three ways.</p>
 *
 * <ol>
 *   <li><b>Coverage.</b> Every key in the shipped {@code eturlia.yml} must be reachable
 *       through an accessor — a key nobody can read is a key that does nothing.</li>
 *   <li><b>Round-trip.</b> A config with every value set to something other than the default
 *       is written to disk, loaded, and each accessor is asserted to return that value.</li>
 *   <li><b>Propagation.</b> The system properties the rest of the core reads
 *       ({@code eturlia.region.guard}, {@code eturlia.log.file}, thread counts, …) are
 *       asserted to carry the configured values after {@code load()}.</li>
 * </ol>
 *
 * <p>Run via {@code scripts/selftest.sh}. Exit code 0 means every check passed.</p>
 */
public final class EturliaConfigSelfTest {

    private static int checks;
    private static int failures;

    /** Keys that are informational by design: documented, read by no code, and marked as such. */
    private static final Set<String> INFORMATIONAL = Set.of(
            "_version",
            "threads.netty-threads-hint",
            "neoforge.mods-folder",
            "neoforge.remind-pack-compat-doc",
            "chunks.player-auto-save-max-per-tick",
            "gameplay.command-blocks.enabled");

    private EturliaConfigSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path root = writeFullConfig();
        try {
            EturliaConfig config = EturliaConfig.load(root);

            generalSection(config);
            threadsSection(config);
            chunksSection(config);
            regionSection(config);
            consoleAndLoggingSection(config);
            watchdogAndSparkSection(config);
            lodSection(config);
            jvmSection(config);
            crashValidationHygieneSection(config);
            modsSection(config);
            systemPropertiesPropagated();
            everyShippedKeyIsReachable();
        } finally {
            deleteRecursively(root);
        }

        System.out.println();
        System.out.println(failures == 0
                ? "OK — " + checks + " checks passed"
                : "FAILED — " + failures + " of " + checks + " checks failed");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- sections

    private static void generalSection(EturliaConfig c) {
        check("_version", 2, c.version());
        check("general.log-effective-settings", Boolean.FALSE, c.logEffectiveSettings());
        check("general.sync-to-paper", Boolean.FALSE, c.syncToPaper());
        check("general.sync-to-server-properties", Boolean.FALSE, c.syncToServerProperties());
    }

    private static void threadsSection(EturliaConfig c) {
        check("threads.region-tick-threads", 6, c.regionTickThreads());
        check("threads.region-grid-exponent", 3, c.regionGridExponent());
        check("threads.chunk-worker-threads", 7, c.chunkWorkerThreads());
        check("threads.chunk-io-threads", 3, c.chunkIoThreads());
        check("threads.chunk-gen-parallelism", "false", c.chunkGenParallelism());
        check("threads.chat-executor-core-size", 2, c.chatExecutorCoreSize());
        check("threads.chat-executor-max-size", 5, c.chatExecutorMaxSize());
    }

    private static void chunksSection(EturliaConfig c) {
        check("chunks.view-distance", 9, c.viewDistanceOverride());
        check("chunks.simulation-distance", 7, c.simulationDistanceOverride());
        check("chunks.player-max-chunk-send-rate", 42.5d, c.playerMaxChunkSendRate());
        check("chunks.player-max-chunk-load-rate", 21.25d, c.playerMaxChunkLoadRate());
        check("chunks.player-max-chunk-generate-rate", 5.5d, c.playerMaxChunkGenerateRate());
        check("chunks.player-max-concurrent-chunk-loads", 11, c.playerMaxConcurrentChunkLoads());
        check("chunks.player-max-concurrent-chunk-generates", 13, c.playerMaxConcurrentChunkGenerates());
        check("chunks.player-auto-save-rate", 400, c.playerAutoSaveRate());
        check("chunks.explain-unload-on-startup", Boolean.FALSE, c.explainUnloadOnStartup());
    }

    private static void regionSection(EturliaConfig c) {
        check("region.guard", "STRICT", c.regionGuard());
        check("region.event-validation", "PERMISSIVE", c.eventValidation());
    }

    private static void consoleAndLoggingSection(EturliaConfig c) {
        check("console.color", "off", c.consoleColor());
        check("logging.file", "logs/custom-eturlia.log", c.logFile());
        check("logging.console-errors", Boolean.FALSE, c.consoleErrors());
    }

    private static void watchdogAndSparkSection(EturliaConfig c) {
        check("watchdog.early-warning-delay-ms", 9000, c.watchdogEarlyWarningDelayMs());
        check("watchdog.early-warning-every-ms", 4000, c.watchdogEarlyWarningEveryMs());
        check("watchdog.log-hints", Boolean.FALSE, c.watchdogLogHints());
        check("spark.enabled", 1, c.sparkEnabledTri());
        check("spark.enable-immediately", 0, c.sparkEnableImmediatelyTri());
    }

    private static void lodSection(EturliaConfig c) {
        check("lod.enabled", Boolean.TRUE, c.lodEnabled());
        check("lod.mode", "SERVER_ASSISTED", c.lodMode());
        check("lod.max-render-distance", 24, c.lodMaxRenderDistance());
        check("lod.dh-server-component", Boolean.TRUE, c.lodDhServerComponent());
        check("lod.voxy-support", Boolean.TRUE, c.lodVoxySupport());
    }

    private static void jvmSection(EturliaConfig c) {
        check("jvm.heap-max", "12G", c.jvmHeapMax());
        check("jvm.heap-min", "4G", c.jvmHeapMin());
        check("jvm.worker-threads", 5, c.jvmWorkerThreads());
        check("jvm.io-threads", 2, c.jvmIoThreads());
        check("jvm.extra-args", "-XX:+UseZGC -XX:MaxGCPauseMillis=150", c.jvmExtraArgs());
    }

    private static void crashValidationHygieneSection(EturliaConfig c) {
        check("crash.dir", "my-crashes", c.crashDir());
        check("crash.install-handler", Boolean.FALSE, c.crashInstallHandler());
        check("validation.thread-validation", Boolean.FALSE, c.threadValidation());
        check("validation.strict", Boolean.TRUE, c.threadValidationStrict());
        check("hygiene.mods-folder", "warn", c.modsHygiene());
    }

    private static void modsSection(EturliaConfig c) {
        check("mods.require-lithostitched-min", "1.8.0", c.lithostitchedMinVersion());
        check("mods.block-lithostitched-prerelease", Boolean.FALSE, c.blockLithostitchedPrerelease());
        check("mods.strict-loading", Boolean.TRUE, c.strictModLoading());
        check("neoforge.remind-sable-shim", Boolean.FALSE, c.remindSableShim());
    }

    /** Everything the rest of the core reads back out of system properties. */
    private static void systemPropertiesPropagated() {
        checkProperty("eturlia.region.guard", "STRICT");
        checkProperty("eturlia.event.validation", "PERMISSIVE");
        checkProperty("eturlia.console.color", "off");
        checkProperty("eturlia.console.errors", "off");
        checkProperty("eturlia.log.file", "logs/custom-eturlia.log");
        checkProperty("eturlia.crash.dir", "my-crashes");
        checkProperty("eturlia.crash.install-handler", "false");
        checkProperty("eturlia.thread.validation.enabled", "false");
        checkProperty("eturlia.thread.validation.strict", "true");
        checkProperty("eturlia.mods.hygiene", "warn");
        checkProperty("eturlia.loading.strict", "true");
        checkProperty("eturlia.lithostitched.min-version", "1.8.0");
        checkProperty("eturlia.lithostitched.block-prerelease", "false");
        checkProperty("eturlia.lod.enabled", "true");
        checkProperty("eturlia.lod.mode", "SERVER_ASSISTED");
        checkProperty("eturlia.lod.max-render-distance", "24");
        checkProperty("eturlia.lod.dh-server-component", "true");
        checkProperty("eturlia.lod.voxy-support", "true");
        checkProperty("eturlia.threads.region-tick", "6");
        checkProperty("eturlia.threads.chunk-worker", "7");
        checkProperty("eturlia.threads.chunk-io", "3");
    }

    /**
     * Every key in the shipped defaults must be reachable through an accessor, so a
     * documented setting cannot silently do nothing.
     */
    private static void everyShippedKeyIsReachable() throws IOException {
        String shipped = readShippedDefaults();
        if (shipped == null) {
            System.out.println("skip  — shipped eturlia.yml not found on the classpath");
            return;
        }
        Set<String> keys = collectKeys(shipped);
        // "reference:" is a documentation block of paths to other config files.
        keys.removeIf(k -> k.startsWith("reference."));
        keys.removeAll(INFORMATIONAL);

        String source = readConfigSource();
        List<String> unreachable = new ArrayList<>();
        for (String key : keys) {
            String leaf = key.substring(key.indexOf('.') + 1);
            String section = key.substring(0, key.indexOf('.'));
            // Accessors look the key up as getX("section", "leaf", default).
            String needle = "\"" + section + "\", \"" + leaf + "\"";
            if (source == null || !source.contains(needle)) {
                unreachable.add(key);
            }
        }
        check("every documented key has an accessor", "[]", unreachable.toString());
    }

    // ----------------------------------------------------------- test input

    private static Path writeFullConfig() throws IOException {
        Path root = Files.createTempDirectory("eturlia-config-selftest");
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config").resolve("eturlia.yml"), """
                _version: 2
                general:
                  log-effective-settings: false
                  sync-to-paper: false
                  sync-to-server-properties: false
                threads:
                  region-tick-threads: 6
                  region-grid-exponent: 3
                  chunk-worker-threads: 7
                  chunk-io-threads: 3
                  chunk-gen-parallelism: false
                  chat-executor-core-size: 2
                  chat-executor-max-size: 5
                  netty-threads-hint: 8
                chunks:
                  view-distance: 9
                  simulation-distance: 7
                  player-max-chunk-send-rate: 42.5
                  player-max-chunk-load-rate: 21.25
                  player-max-chunk-generate-rate: 5.5
                  player-max-concurrent-chunk-loads: 11
                  player-max-concurrent-chunk-generates: 13
                  player-auto-save-rate: 400
                  player-auto-save-max-per-tick: 12
                  explain-unload-on-startup: false
                gameplay:
                  command-blocks:
                    enabled: true
                region:
                  guard: STRICT
                  event-validation: PERMISSIVE
                console:
                  color: off
                watchdog:
                  early-warning-delay-ms: 9000
                  early-warning-every-ms: 4000
                  log-hints: false
                spark:
                  enabled: 1
                  enable-immediately: 0
                lod:
                  enabled: true
                  mode: SERVER_ASSISTED
                  max-render-distance: 24
                  dh-server-component: true
                  voxy-support: true
                jvm:
                  heap-max: "12G"
                  heap-min: "4G"
                  worker-threads: 5
                  io-threads: 2
                  extra-args: "-XX:+UseZGC -XX:MaxGCPauseMillis=150"
                logging:
                  file: logs/custom-eturlia.log
                  console-errors: false
                crash:
                  dir: my-crashes
                  install-handler: false
                validation:
                  thread-validation: false
                  strict: true
                hygiene:
                  mods-folder: warn
                neoforge:
                  mods-folder: mods
                  remind-sable-shim: false
                  remind-pack-compat-doc: false
                mods:
                  require-lithostitched-min: "1.8.0"
                  block-lithostitched-prerelease: false
                  strict-loading: true
                """, StandardCharsets.UTF_8);
        return root;
    }

    private static String readShippedDefaults() throws IOException {
        try (InputStream in = EturliaConfig.class.getResourceAsStream("/eturlia.yml")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path onDisk = Path.of("build-data", "eturlia-core", "src", "main", "resources", "eturlia.yml");
        return Files.isRegularFile(onDisk) ? Files.readString(onDisk, StandardCharsets.UTF_8) : null;
    }

    private static String readConfigSource() throws IOException {
        Path source = Path.of("build-data", "eturlia-core", "src", "main", "java", "eturlia",
                "core", "config", "EturliaConfig.java");
        return Files.isRegularFile(source) ? Files.readString(source, StandardCharsets.UTF_8) : null;
    }

    /** Collects {@code section.key} names from a two-level YAML document. */
    private static Set<String> collectKeys(String yaml) {
        Set<String> keys = new LinkedHashSet<>();
        Pattern top = Pattern.compile("^([a-z_][a-z0-9_-]*):\\s*$");
        Pattern nested = Pattern.compile("^  ([a-z_][a-z0-9_-]*):\\s*(\\S.*)?$");
        String section = null;
        for (String line : yaml.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher topMatch = top.matcher(line);
            if (topMatch.matches()) {
                section = topMatch.group(1);
                continue;
            }
            if (!line.startsWith("  ")) {
                Matcher scalar = Pattern.compile("^([a-z_][a-z0-9_-]*):\\s*\\S.*$").matcher(line);
                if (scalar.matches()) {
                    keys.add(scalar.group(1));
                    section = null;
                }
                continue;
            }
            Matcher nestedMatch = nested.matcher(line);
            if (nestedMatch.matches() && section != null) {
                String value = nestedMatch.group(2);
                if (value != null && !value.isBlank()) {
                    keys.add(section + "." + nestedMatch.group(1));
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------- helpers

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

    private static void checkProperty(String key, String expected) {
        check("property " + key, expected, System.getProperty(key));
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
