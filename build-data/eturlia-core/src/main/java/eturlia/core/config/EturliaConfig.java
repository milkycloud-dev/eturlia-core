/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads {@code config/eturlia.yml} — Eturlia's brand-facing server config.
 *
 * <p>Uses a small indentation-based YAML subset parser (maps + scalars only)
 * so we do not depend on SnakeYAML across ModLauncher module layers.</p>
 *
 * <p>Must be {@link #load(Path) loaded} before Paper's
 * {@code initializeGlobalConfiguration} so thread overrides can be applied in
 * Folia's {@code @PostProcess} hooks before region/chunk pools start.</p>
 */
public final class EturliaConfig {

    private static final Logger LOGGER = Logger.getLogger("EturliaConfig");
    private static final String RESOURCE = "/eturlia.yml";
    /** Bumped when keys are added/renamed; a mismatch warns that new keys are missing. */
    private static final int CURRENT_VERSION = 2;

    private static volatile EturliaConfig INSTANCE = new EturliaConfig();

    private final Map<String, Object> root;

    private EturliaConfig() {
        this.root = new LinkedHashMap<>();
    }

    private EturliaConfig(Map<String, Object> root) {
        this.root = root != null ? root : new LinkedHashMap<>();
    }

    public static EturliaConfig get() {
        return INSTANCE;
    }

    /**
     * Load {@code <root>/config/eturlia.yml}, creating it from the embedded
     * default on first run.
     */
    public static synchronized EturliaConfig load(Path serverRoot) {
        Objects.requireNonNull(serverRoot, "serverRoot");
        Path configDir = serverRoot.resolve("config");
        Path file = configDir.resolve("eturlia.yml");
        try {
            Files.createDirectories(configDir);
            if (!Files.isRegularFile(file)) {
                try (InputStream in = EturliaConfig.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) {
                        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
                    }
                    Files.copy(in, file);
                    LOGGER.info("Created default " + file.toAbsolutePath());
                }
            }
            Map<String, Object> map;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                map = SimpleYaml.loadMap(reader);
            }
            INSTANCE = new EturliaConfig(map);
            INSTANCE.applySystemProperties();
            int loadedVersion = INSTANCE.version();
            LOGGER.info("Loaded Eturlia config from " + file.toAbsolutePath()
                    + " (_version=" + loadedVersion + ")");
            if (loadedVersion != CURRENT_VERSION) {
                LOGGER.warning("config/eturlia.yml has _version=" + loadedVersion
                        + " but this build expects " + CURRENT_VERSION
                        + ". New keys are NOT merged into an existing file — compare against"
                        + " the defaults shipped in the jar (/eturlia.yml) after upgrading.");
            }
            return INSTANCE;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load eturlia.yml — using empty defaults", e);
            INSTANCE = new EturliaConfig();
            return INSTANCE;
        }
    }

    public int version() {
        return getInt("", "_version", CURRENT_VERSION);
    }

    public boolean logEffectiveSettings() {
        return getBoolean("general", "log-effective-settings", true);
    }

    public boolean syncToPaper() {
        return getBoolean("general", "sync-to-paper", true);
    }

    public boolean syncToServerProperties() {
        return getBoolean("general", "sync-to-server-properties", true);
    }

    public int regionTickThreads() {
        return getInt("threads", "region-tick-threads", -1);
    }

    public int regionGridExponent() {
        return getInt("threads", "region-grid-exponent", 2);
    }

    public int chunkWorkerThreads() {
        return getInt("threads", "chunk-worker-threads", -1);
    }

    public int chunkIoThreads() {
        return getInt("threads", "chunk-io-threads", -1);
    }

    public String chunkGenParallelism() {
        return getString("threads", "chunk-gen-parallelism", "default");
    }

    public int chatExecutorCoreSize() {
        return getInt("threads", "chat-executor-core-size", -1);
    }

    public int chatExecutorMaxSize() {
        return getInt("threads", "chat-executor-max-size", -1);
    }

    public int viewDistanceOverride() {
        return getInt("chunks", "view-distance", -1);
    }

    public int simulationDistanceOverride() {
        return getInt("chunks", "simulation-distance", -1);
    }

    public double playerMaxChunkSendRate() {
        return getDouble("chunks", "player-max-chunk-send-rate", -1.0);
    }

    public double playerMaxChunkLoadRate() {
        return getDouble("chunks", "player-max-chunk-load-rate", -1.0);
    }

    public double playerMaxChunkGenerateRate() {
        return getDouble("chunks", "player-max-chunk-generate-rate", -1.0);
    }

    public int playerMaxConcurrentChunkLoads() {
        return getInt("chunks", "player-max-concurrent-chunk-loads", -2);
    }

    public int playerMaxConcurrentChunkGenerates() {
        return getInt("chunks", "player-max-concurrent-chunk-generates", -2);
    }

    public int playerAutoSaveRate() {
        return getInt("chunks", "player-auto-save-rate", -1);
    }

    public int playerAutoSaveMaxPerTick() {
        return getInt("chunks", "player-auto-save-max-per-tick", -1);
    }

    public boolean explainUnloadOnStartup() {
        return getBoolean("chunks", "explain-unload-on-startup", true);
    }

    public Boolean commandBlocksEnabledOverride() {
        if (!syncToServerProperties()) {
            return null;
        }
        Object raw = map("gameplay", "command-blocks").get("enabled");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "null".equals(s) || "default".equals(s) || "inherit".equals(s)) {
            return null;
        }
        if ("true".equals(s) || "yes".equals(s) || "on".equals(s) || "1".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "no".equals(s) || "off".equals(s) || "0".equals(s)) {
            return Boolean.FALSE;
        }
        LOGGER.warning("gameplay.command-blocks.enabled=" + raw + " not recognized; ignoring override");
        return null;
    }

    public String regionGuard() {
        return getString("region", "guard", "WARN");
    }

    public String eventValidation() {
        return getString("region", "event-validation", "WARN");
    }

    public String consoleColor() {
        return getString("console", "color", "calm");
    }

    public int watchdogEarlyWarningDelayMs() {
        return getInt("watchdog", "early-warning-delay-ms", -1);
    }

    public int watchdogEarlyWarningEveryMs() {
        return getInt("watchdog", "early-warning-every-ms", -1);
    }

    public boolean watchdogLogHints() {
        return getBoolean("watchdog", "log-hints", true);
    }

    public int sparkEnabledTri() {
        return getInt("spark", "enabled", -1);
    }

    public int sparkEnableImmediatelyTri() {
        return getInt("spark", "enable-immediately", -1);
    }

    public boolean lodEnabled() {
        return getBoolean("lod", "enabled", false);
    }

    public String lodMode() {
        return getString("lod", "mode", "DISABLED");
    }

    public boolean remindSableShim() {
        return getBoolean("neoforge", "remind-sable-shim", true);
    }

    public int lodMaxRenderDistance() {
        return getInt("lod", "max-render-distance", -1);
    }

    public boolean lodDhServerComponent() {
        return getBoolean("lod", "dh-server-component", false);
    }

    public boolean lodVoxySupport() {
        return getBoolean("lod", "voxy-support", false);
    }

    // -------------------------------------------------------------------------
    // jvm: read by the launcher before the server JVM is started
    // -------------------------------------------------------------------------

    public String jvmHeapMax() {
        return getString("jvm", "heap-max", "");
    }

    public String jvmHeapMin() {
        return getString("jvm", "heap-min", "");
    }

    public int jvmWorkerThreads() {
        return getInt("jvm", "worker-threads", 0);
    }

    public int jvmIoThreads() {
        return getInt("jvm", "io-threads", 0);
    }

    public String jvmExtraArgs() {
        return getString("jvm", "extra-args", "");
    }

    // -------------------------------------------------------------------------
    // logging / crash / validation / hygiene
    // -------------------------------------------------------------------------

    public String logFile() {
        return getString("logging", "file", "logs/eturlia.log");
    }

    public boolean consoleErrors() {
        return getBoolean("logging", "console-errors", true);
    }

    public String crashDir() {
        return getString("crash", "dir", "eturlia-crash-reports");
    }

    public boolean crashInstallHandler() {
        return getBoolean("crash", "install-handler", true);
    }

    public boolean threadValidation() {
        return getBoolean("validation", "thread-validation", true);
    }

    public boolean threadValidationStrict() {
        return getBoolean("validation", "strict", false);
    }

    public String modsHygiene() {
        return getString("hygiene", "mods-folder", "skip");
    }

    public boolean strictModLoading() {
        return getBoolean("mods", "strict-loading", false);
    }

    public String lithostitchedMinVersion() {
        return getString("mods", "require-lithostitched-min", "");
    }

    public boolean blockLithostitchedPrerelease() {
        return getBoolean("mods", "block-lithostitched-prerelease", true);
    }

    public void applySystemProperties() {
        String guard = regionGuard();
        if (guard != null && !guard.isBlank()) {
            System.setProperty("eturlia.region.guard", guard.trim().toUpperCase(Locale.ROOT));
            try {
                eturlia.core.region.CrossRegionInvocationGuard.setMode(
                        eturlia.core.region.CrossRegionInvocationGuard.Mode.valueOf(
                                guard.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        String validation = eventValidation();
        if (validation != null && !validation.isBlank()) {
            System.setProperty("eturlia.event.validation", validation.trim().toUpperCase(Locale.ROOT));
        }
        String color = consoleColor();
        if (color != null && !color.isBlank()) {
            System.setProperty("eturlia.console.color", color.trim().toLowerCase(Locale.ROOT));
        }
        if (lodEnabled()) {
            System.setProperty("eturlia.lod.enabled", "true");
            System.setProperty("eturlia.lod.mode", lodMode());
            // These three were documented in eturlia.yml but never reached ClientLodConfig.
            int maxRender = lodMaxRenderDistance();
            if (maxRender >= 0) {
                System.setProperty("eturlia.lod.max-render-distance", Integer.toString(maxRender));
            }
            System.setProperty("eturlia.lod.dh-server-component", Boolean.toString(lodDhServerComponent()));
            System.setProperty("eturlia.lod.voxy-support", Boolean.toString(lodVoxySupport()));
        }
        String lithoMin = lithostitchedMinVersion();
        if (lithoMin != null && !lithoMin.isBlank()) {
            System.setProperty("eturlia.lithostitched.min-version", lithoMin.trim());
        }
        System.setProperty("eturlia.lithostitched.block-prerelease",
                Boolean.toString(blockLithostitchedPrerelease()));

        // Logging / console. EturliaConsole reads these when it installs.
        String log = logFile();
        if (log != null && !log.isBlank()) {
            System.setProperty("eturlia.log.file", log.trim());
        }
        System.setProperty("eturlia.console.errors", consoleErrors() ? "show" : "off");

        // Crash reports.
        String crash = crashDir();
        if (crash != null && !crash.isBlank()) {
            System.setProperty("eturlia.crash.dir", crash.trim());
        }
        System.setProperty("eturlia.crash.install-handler",
                Boolean.toString(crashInstallHandler()));

        // Region thread validation (read by RegionThreadValidatorHooks, which resolves these
        // lazily precisely so a config loaded after class-init still takes effect).
        System.setProperty("eturlia.thread.validation.enabled",
                Boolean.toString(threadValidation()));
        System.setProperty("eturlia.thread.validation.strict",
                Boolean.toString(threadValidationStrict()));
        eturlia.core.mixin.server.RegionThreadValidatorHooks.reloadModeFlags();

        // mods/ hygiene.
        String hygiene = modsHygiene();
        if (hygiene != null && !hygiene.isBlank()) {
            System.setProperty("eturlia.mods.hygiene", hygiene.trim().toLowerCase(Locale.ROOT));
        }

        // Mod compatibility manifest enforcement.
        System.setProperty("eturlia.loading.strict", Boolean.toString(strictModLoading()));

        // Thread counts. The launcher turns the jvm.* keys into flags on the server JVM;
        // these mirror the threads.* values for anything reading them in-process.
        int workers = chunkWorkerThreads();
        if (workers >= 1) {
            System.setProperty("eturlia.threads.chunk-worker", Integer.toString(workers));
        }
        int io = chunkIoThreads();
        if (io >= 1) {
            System.setProperty("eturlia.threads.chunk-io", Integer.toString(io));
        }
        int regionThreads = regionTickThreads();
        if (regionThreads >= 1) {
            System.setProperty("eturlia.threads.region-tick", Integer.toString(regionThreads));
        }
    }

    public void applyToPaperGlobal(Object globalConfiguration) {
        if (!syncToPaper() || globalConfiguration == null) {
            return;
        }
        try {
            Class<?> gcClass = globalConfiguration.getClass();

            // Folia region threading. threads.region-tick-threads / region-grid-exponent were
            // documented but never applied to anything: GlobalConfiguration.threadedRegions is
            // what TickRegions actually reads, and it is initialised from a @PostProcess hook,
            // so the values have to be re-published through TickRegions.init after the edit.
            try {
                Object threaded = gcClass.getField("threadedRegions").get(globalConfiguration);
                if (threaded != null) {
                    boolean changed = false;
                    int regionThreads = regionTickThreads();
                    if (regionThreads >= 1) {
                        setIntField(threaded, "threads", regionThreads);
                        changed = true;
                    }
                    int gridExponent = regionGridExponent();
                    if (gridExponent >= 0) {
                        setIntField(threaded, "gridExponent", gridExponent);
                        changed = true;
                    }
                    if (changed) {
                        reinitTickRegions(threaded);
                        LOGGER.info("Eturlia: region threading set to "
                                + (regionThreads >= 1 ? regionThreads + " threads" : "auto threads")
                                + ", grid exponent " + gridExponent);
                    }
                }
            } catch (NoSuchFieldException e) {
                LOGGER.fine("Eturlia: GlobalConfiguration.threadedRegions absent — not a Folia build?");
            }

            Object chunkBasic = gcClass.getField("chunkLoadingBasic").get(globalConfiguration);
            if (chunkBasic != null) {
                double send = playerMaxChunkSendRate();
                if (send >= 0) {
                    chunkBasic.getClass().getField("playerMaxChunkSendRate").setDouble(chunkBasic, send);
                }
                double load = playerMaxChunkLoadRate();
                if (load >= 0) {
                    chunkBasic.getClass().getField("playerMaxChunkLoadRate").setDouble(chunkBasic, load);
                }
                double gen = playerMaxChunkGenerateRate();
                if (gen >= 0) {
                    chunkBasic.getClass().getField("playerMaxChunkGenerateRate").setDouble(chunkBasic, gen);
                }
            }

            Object chunkAdv = gcClass.getField("chunkLoadingAdvanced").get(globalConfiguration);
            if (chunkAdv != null) {
                int concLoad = playerMaxConcurrentChunkLoads();
                if (concLoad != -2) {
                    chunkAdv.getClass().getField("playerMaxConcurrentChunkLoads").setInt(chunkAdv, concLoad);
                }
                int concGen = playerMaxConcurrentChunkGenerates();
                if (concGen != -2) {
                    chunkAdv.getClass().getField("playerMaxConcurrentChunkGenerates").setInt(chunkAdv, concGen);
                }
            }

            Object playerAutoSave = gcClass.getField("playerAutoSave").get(globalConfiguration);
            if (playerAutoSave != null) {
                int rate = playerAutoSaveRate();
                if (rate >= 0) {
                    playerAutoSave.getClass().getField("rate").setInt(playerAutoSave, rate);
                }
            }

            Object spark = gcClass.getField("spark").get(globalConfiguration);
            if (spark != null) {
                int en = sparkEnabledTri();
                if (en == 0 || en == 1) {
                    spark.getClass().getField("enabled").setBoolean(spark, en == 1);
                }
                int imm = sparkEnableImmediatelyTri();
                if (imm == 0 || imm == 1) {
                    spark.getClass().getField("enableImmediately").setBoolean(spark, imm == 1);
                }
            }

            try {
                Object watchdog = gcClass.getField("watchdog").get(globalConfiguration);
                if (watchdog != null) {
                    int delay = watchdogEarlyWarningDelayMs();
                    if (delay >= 0) {
                        watchdog.getClass().getField("earlyWarningDelay").setInt(watchdog, delay);
                    }
                    int every = watchdogEarlyWarningEveryMs();
                    if (every >= 0) {
                        watchdog.getClass().getField("earlyWarningEvery").setInt(watchdog, every);
                    }
                }
            } catch (NoSuchFieldException ignored) {
            }

            Object misc = gcClass.getField("misc").get(globalConfiguration);
            if (misc != null) {
                Object chat = misc.getClass().getField("chatThreads").get(misc);
                if (chat != null) {
                    int core = chatExecutorCoreSize();
                    if (core >= 0) {
                        setIntField(chat, "chatExecutorCoreSize", core);
                    }
                    int max = chatExecutorMaxSize();
                    if (max >= 0) {
                        setIntField(chat, "chatExecutorMaxSize", max);
                    }
                }
            }

            LOGGER.info("Eturlia: applied config/eturlia.yml overrides to Paper global configuration");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Field type/visibility drift in Paper throws IllegalArgumentException /
            // SecurityException, which are NOT ReflectiveOperationException. Letting those
            // escape aborted server configuration loading entirely.
            LOGGER.log(Level.WARNING, "Eturlia: failed to apply paper-global overrides from eturlia.yml", e);
        }
    }

    /**
     * Re-runs {@code TickRegions.init(threadedRegions)} after the thread count or grid
     * exponent was changed, because Folia only reads them from that call.
     *
     * <p>Only meaningful before the region scheduler starts ticking; afterwards Folia ignores
     * a changed thread count until restart, which is why this is logged rather than silent.</p>
     */
    private static void reinitTickRegions(Object threadedRegions) {
        try {
            Class<?> tickRegions = Class.forName("io.papermc.paper.threadedregions.TickRegions");
            for (java.lang.reflect.Method method : tickRegions.getMethods()) {
                if ("init".equals(method.getName()) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(threadedRegions)) {
                    method.invoke(null, threadedRegions);
                    return;
                }
            }
            LOGGER.fine("Eturlia: TickRegions.init(ThreadedRegions) not found — thread count "
                    + "will apply on the next restart");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Eturlia: could not re-init TickRegions", e);
        }
    }

    private static void setIntField(Object target, String name, int value) throws ReflectiveOperationException {
        try {
            target.getClass().getField(name).setInt(target, value);
        } catch (NoSuchFieldException e) {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, value);
        }
    }

    public void logStartupSummary(boolean commandBlocksEffective, int viewDistance, int simulationDistance) {
        if (!logEffectiveSettings()) {
            return;
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        LOGGER.info("========== Eturlia effective settings ==========");
        LOGGER.info("CPU logical processors: " + cpus);
        LOGGER.info("threads.region-tick-threads: " + regionTickThreads()
                + " ( -1 = Folia auto: ~ max(1, cpus/2 then /4 if >4) )");
        LOGGER.info("threads.region-grid-exponent: " + regionGridExponent()
                + " → region cell " + (1 << Math.max(0, Math.min(31, regionGridExponent()))) + "×"
                + (1 << Math.max(0, Math.min(31, regionGridExponent()))) + " chunks");
        LOGGER.info("threads.chunk-worker-threads: " + chunkWorkerThreads()
                + " | chunk-io-threads: " + chunkIoThreads()
                + " | gen-parallelism: " + chunkGenParallelism());
        LOGGER.info("sync-to-paper=" + syncToPaper() + " sync-to-server-properties=" + syncToServerProperties());
        LOGGER.info("command-blocks effective: " + commandBlocksEffective
                + " (override=" + commandBlocksEnabledOverride() + ")");
        LOGGER.info("view-distance=" + viewDistance + " simulation-distance=" + simulationDistance);
        LOGGER.info("region.guard=" + regionGuard() + " event-validation=" + eventValidation()
                + " console.color=" + consoleColor());
        if (explainUnloadOnStartup()) {
            LOGGER.info("Chunk unload (Folia/Moonrise): ticket-driven via ChunkHolderManager; "
                    + "not classic Spigot delay-chunk-unloads alone. "
                    + "Lower simulation-distance to shrink ticking footprint; "
                    + "view-distance controls client send radius. "
                    + "See bukkit.yml chunk-gc + paper-world chunks.* and docs in this file.");
        }
        if (watchdogLogHints()) {
            LOGGER.info("Watchdog: tune config/paper-global.yml watchdog.early-warning-delay "
                    + "(or watchdog.* in eturlia.yml with sync-to-paper). "
                    + "First-world-gen sync loads often trip early warnings — raise delay on weak hosts.");
        }
        if (remindSableShim()) {
            LOGGER.info("NeoForge: use arclight_sable_patch-*-eturlia-shim.jar, never the original Arclight patch.");
        }
        LOGGER.info("Full reference: config/eturlia.yml  |  config/paper-global.yml  |  server.properties");
        LOGGER.info("================================================");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String... path) {
        Map<String, Object> cur = root;
        for (String key : path) {
            Object next = cur.get(key);
            if (!(next instanceof Map<?, ?> m)) {
                return Collections.emptyMap();
            }
            cur = (Map<String, Object>) m;
        }
        return cur;
    }

    private Object get(String section, String key) {
        if (section == null || section.isEmpty()) {
            return root.get(key);
        }
        return map(section).get(key);
    }

    private int getInt(String section, String key, int def) {
        Object v = get(section, key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private double getDouble(String section, String key, double def) {
        Object v = get(section, key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private boolean getBoolean(String section, String key, boolean def) {
        Object v = get(section, key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        if (v instanceof String s) {
            // Accept every YAML 1.1 spelling: the parser only coerces true/false so that
            // string-valued settings such as console.color: off survive intact.
            String t = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(t) || "yes".equals(t) || "on".equals(t) || "1".equals(t)) {
                return true;
            }
            if ("false".equals(t) || "no".equals(t) || "off".equals(t) || "0".equals(t)) {
                return false;
            }
            LOGGER.warning("eturlia.yml: " + section + "." + key + "=" + s
                    + " is not a boolean — using the default (" + def + ")");
        }
        return def;
    }

    private String getString(String section, String key, String def) {
        Object v = get(section, key);
        return v != null ? String.valueOf(v) : def;
    }

    /**
     * Minimal YAML subset: nested maps via indentation, scalars, {@code #} comments.
     * Sufficient for eturlia.yml; not a full YAML 1.1 implementation.
     */
    static final class SimpleYaml {
        private SimpleYaml() {}

        static Map<String, Object> loadMap(Reader reader) throws IOException {
            BufferedReader br = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
            Map<String, Object> root = new LinkedHashMap<>();
            Deque<Map<String, Object>> maps = new ArrayDeque<>();
            Deque<Integer> indents = new ArrayDeque<>();
            maps.push(root);
            indents.push(-1);

            String line;
            while ((line = br.readLine()) != null) {
                String raw = line;
                int hash = indexOfComment(raw);
                if (hash >= 0) {
                    raw = raw.substring(0, hash);
                }
                if (raw.isBlank()) {
                    continue;
                }
                if (raw.startsWith("\t") || raw.startsWith(" \t")) {
                    LOGGER.warning("eturlia.yml: TAB indentation is not supported by the built-in"
                            + " parser (YAML forbids tabs for indentation) — line ignored: " + line.trim());
                    continue;
                }
                int indent = leadingSpaces(raw);
                String content = raw.trim();
                if (content.isEmpty()) {
                    continue;
                }
                if (content.startsWith("- ") || "-".equals(content)) {
                    LOGGER.warning("eturlia.yml: sequences (\"- item\") are not supported by the"
                            + " built-in parser — line ignored: " + content);
                    continue;
                }
                while (indents.size() > 1 && indent <= indents.peek()) {
                    indents.pop();
                    maps.pop();
                }
                int colon = content.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = content.substring(0, colon).trim();
                String value = content.substring(colon + 1).trim();
                if (key.isEmpty()) {
                    continue;
                }
                if (value.isEmpty()) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    maps.peek().put(key, child);
                    maps.push(child);
                    indents.push(indent);
                } else {
                    maps.peek().put(key, parseScalar(value));
                }
            }
            return root;
        }

        private static int indexOfComment(String line) {
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !inDouble) {
                    inSingle = !inSingle;
                } else if (c == '"' && !inSingle) {
                    inDouble = !inDouble;
                } else if (c == '#' && !inSingle && !inDouble) {
                    return i;
                }
            }
            return -1;
        }

        private static int leadingSpaces(String s) {
            int i = 0;
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
            return i;
        }

        private static Object parseScalar(String value) {
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
            // Only "true"/"false" become booleans here. YAML 1.1 also treats on/off/yes/no as
            // booleans, but this config uses those words as *values* — console.color: off and
            // hygiene.mods-folder: off are strings — and coercing them turned the setting into
            // the string "false". getBoolean() still accepts all of the spellings.
            if ("true".equalsIgnoreCase(value)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(value)) {
                return Boolean.FALSE;
            }
            if ("null".equalsIgnoreCase(value) || "~".equals(value)) {
                return null;
            }
            try {
                if (value.contains(".") || value.contains("e") || value.contains("E")) {
                    return Double.parseDouble(value);
                }
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
    }
}
